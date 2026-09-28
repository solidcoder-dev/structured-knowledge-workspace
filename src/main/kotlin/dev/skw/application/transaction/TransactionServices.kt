package dev.skw.application.transaction

import dev.skw.application.accesscontrol.AuthorizeUseCase
import dev.skw.application.accesscontrol.requirePrincipal
import dev.skw.application.entry.CreateEntryCommand
import dev.skw.application.entry.CreateEntryService
import dev.skw.application.entry.DeleteEntryPropertyService
import dev.skw.application.entry.DeleteEntryService
import dev.skw.application.entry.InitialRelationshipCommand
import dev.skw.application.entry.SetEntryPropertyService
import dev.skw.application.port.out.TransactionRunner
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.application.relationship.CreateRelationshipCommand
import dev.skw.application.relationship.CreateRelationshipUseCase
import dev.skw.application.relationship.DeleteRelationshipUseCase
import dev.skw.application.relationship.GetRelationshipUseCase
import dev.skw.domain.Version
import dev.skw.domain.accesscontrol.Namespace
import dev.skw.domain.accesscontrol.Permission
import dev.skw.domain.accesscontrol.PrincipalId
import dev.skw.domain.entry.Entry
import dev.skw.domain.entry.EntryId
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.relationship.Relationship
import dev.skw.domain.relationship.RelationshipId
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId

private val LOCAL_REF_PATTERN = Regex("^[a-z][a-z0-9_-]{0,63}$")

class InvalidLocalRef(
    value: String,
) : RuntimeException("Invalid localRef '$value'")

class DuplicateLocalRef(
    ref: LocalEntryRef,
) : RuntimeException("localRef '${ref.value}' was already declared")

class UnknownLocalRef(
    ref: LocalEntryRef,
) : RuntimeException("Unknown localRef '${ref.value}'")

class InvalidTransactionReference(
    value: String,
) : RuntimeException("Invalid transaction entry reference '$value'")

class InvalidTransactionSize(
    size: Int,
) : RuntimeException("Transaction must contain between 1 and 100 mutations (got $size)")

data class LocalEntryRef(
    val value: String,
) {
    init {
        if (!LOCAL_REF_PATTERN.matches(value)) throw InvalidLocalRef(value)
    }
}

sealed interface TransactionEntryReference

data class PersistedEntryReference(
    val entryId: EntryId,
) : TransactionEntryReference

data class LocalEntryReference(
    val localRef: LocalEntryRef,
) : TransactionEntryReference

sealed interface TransactionMutation

data class CreateEntryMutation(
    val properties: Map<PropertyName, PropertyValue> = emptyMap(),
    val initialRelationships: List<InitialRelationshipCommand> = emptyList(),
    val localRef: LocalEntryRef? = null,
) : TransactionMutation

data class SetEntryPropertyMutation(
    val entryId: EntryId,
    val expectedVersion: Version,
    val property: PropertyName,
    val value: PropertyValue,
) : TransactionMutation

data class DeleteEntryPropertyMutation(
    val entryId: EntryId,
    val expectedVersion: Version,
    val property: PropertyName,
) : TransactionMutation

data class DeleteEntryMutation(
    val entryId: EntryId,
    val expectedVersion: Version,
) : TransactionMutation

data class CreateRelationshipMutation(
    val sourceEntryRef: TransactionEntryReference,
    val targetEntryRef: TransactionEntryReference,
    val type: RelationshipType,
) : TransactionMutation

data class DeleteRelationshipMutation(
    val relationshipId: RelationshipId,
) : TransactionMutation

data class TransactionCommand(
    val workspaceId: WorkspaceId,
    val mutations: List<TransactionMutation>,
    val principal: PrincipalId? = null,
)

data class TransactionResult(
    val createdEntryIds: Map<String, EntryId>,
    val entries: List<Entry>,
    val relationships: List<Relationship>,
    val deletedEntryIds: List<EntryId>,
    val deletedRelationshipIds: List<RelationshipId>,
)

class TransactionMutationFailed(
    val index: Int,
    val failure: RuntimeException,
) : RuntimeException(failure.message, failure)

class TransactionExecutionContext {
    private val localEntries = LinkedHashMap<LocalEntryRef, EntryId?>()
    private val affectedEntries = LinkedHashMap<EntryId, Entry>()
    private val affectedRelationships = LinkedHashMap<RelationshipId, Relationship>()
    private val deletedEntries = LinkedHashSet<EntryId>()
    private val deletedRelationships = LinkedHashSet<RelationshipId>()

    fun reserveLocal(ref: LocalEntryRef) {
        if (localEntries.containsKey(ref)) throw DuplicateLocalRef(ref)
        localEntries[ref] = null
    }

    fun bindLocal(
        ref: LocalEntryRef,
        entryId: EntryId,
    ) {
        localEntries[ref] = entryId
    }

    fun resolve(reference: TransactionEntryReference): EntryId =
        when (reference) {
            is PersistedEntryReference -> reference.entryId
            is LocalEntryReference -> localEntries[reference.localRef] ?: throw UnknownLocalRef(reference.localRef)
        }

    fun track(entry: Entry) {
        affectedEntries[entry.id] = entry
    }

    fun track(relationship: Relationship) {
        affectedRelationships[relationship.id] = relationship
    }

    fun deleteEntry(entryId: EntryId) {
        affectedEntries.remove(entryId)
        deletedEntries.add(entryId)
    }

    fun deleteRelationship(relationshipId: RelationshipId) {
        affectedRelationships.remove(relationshipId)
        deletedRelationships.add(relationshipId)
    }

    fun result(): TransactionResult =
        TransactionResult(
            localEntries.mapNotNull { (ref, entryId) -> entryId?.let { ref.value to it } }.toMap(LinkedHashMap()),
            affectedEntries.values.toList(),
            affectedRelationships.values.toList(),
            deletedEntries.toList(),
            deletedRelationships.toList(),
        )
}

fun interface ExecuteTransactionUseCase {
    fun execute(command: TransactionCommand): TransactionResult
}

class ExecuteTransactionService(
    private val workspaceRepository: WorkspaceRepository,
    private val createEntry: CreateEntryService,
    private val setEntryProperty: SetEntryPropertyService,
    private val deleteEntryProperty: DeleteEntryPropertyService,
    private val deleteEntry: DeleteEntryService,
    private val createRelationship: CreateRelationshipUseCase,
    private val getRelationship: GetRelationshipUseCase,
    private val deleteRelationship: DeleteRelationshipUseCase,
    private val transactionRunner: TransactionRunner,
    private val authorize: AuthorizeUseCase,
) : ExecuteTransactionUseCase {
    override fun execute(command: TransactionCommand): TransactionResult {
        if (command.mutations.size !in 1..100) throw InvalidTransactionSize(command.mutations.size)
        return transactionRunner.inTransaction {
            if (workspaceRepository.findById(command.workspaceId) == null) {
                throw dev.skw.application.workspace
                    .WorkspaceNotFound(command.workspaceId)
            }
            preAuthorize(command)
            val context = TransactionExecutionContext()
            command.mutations.forEachIndexed { index, mutation ->
                try {
                    executeMutation(command.workspaceId, command.principal, mutation, context)
                } catch (failure: RuntimeException) {
                    throw TransactionMutationFailed(index, failure)
                }
            }
            context.result()
        }
    }

    private fun preAuthorize(command: TransactionCommand) {
        val principal = requirePrincipal(command.principal)

        fun check(
            permission: Permission,
            namespace: Namespace? = null,
        ) = authorize.authorize(principal, command.workspaceId, permission, namespace)
        command.mutations.forEach { mutation ->
            when (mutation) {
                is CreateEntryMutation -> {
                    check(Permission.CREATE)
                    mutation.properties.keys.forEach { check(Permission.CREATE, Namespace.from(it.value)) }
                    mutation.initialRelationships.forEach { check(Permission.CREATE, Namespace.from(it.type.value)) }
                }
                is SetEntryPropertyMutation -> check(Permission.UPDATE, Namespace.from(mutation.property.value))
                is DeleteEntryPropertyMutation -> check(Permission.DELETE, Namespace.from(mutation.property.value))
                is DeleteEntryMutation -> check(Permission.DELETE)
                is CreateRelationshipMutation -> check(Permission.CREATE, Namespace.from(mutation.type.value))
                is DeleteRelationshipMutation ->
                    check(
                        Permission.DELETE,
                        Namespace.from(getRelationship.get(command.workspaceId, mutation.relationshipId).type.value),
                    )
            }
        }
    }

    private fun executeMutation(
        workspaceId: WorkspaceId,
        principal: PrincipalId?,
        mutation: TransactionMutation,
        context: TransactionExecutionContext,
    ) {
        when (mutation) {
            is CreateEntryMutation -> {
                mutation.localRef?.let(context::reserveLocal)
                val result =
                    createEntry.create(
                        CreateEntryCommand(workspaceId, mutation.properties, mutation.initialRelationships, principal),
                    )
                mutation.localRef?.let { ref ->
                    context.bindLocal(ref, result.entry.id)
                }
                context.track(result.entry)
                result.relationships.forEach(context::track)
            }
            is SetEntryPropertyMutation ->
                context.track(
                    setEntryProperty.set(
                        workspaceId,
                        mutation.entryId,
                        mutation.expectedVersion,
                        mutation.property,
                        mutation.value,
                        principal,
                    ),
                )
            is DeleteEntryPropertyMutation ->
                context.track(
                    deleteEntryProperty.delete(
                        workspaceId,
                        mutation.entryId,
                        mutation.expectedVersion,
                        mutation.property,
                        principal,
                    ),
                )
            is DeleteEntryMutation -> {
                deleteEntry.delete(workspaceId, mutation.entryId, mutation.expectedVersion, principal)
                context.deleteEntry(mutation.entryId)
            }
            is CreateRelationshipMutation ->
                context.track(
                    createRelationship.create(
                        CreateRelationshipCommand(
                            workspaceId,
                            context.resolve(mutation.sourceEntryRef),
                            context.resolve(mutation.targetEntryRef),
                            mutation.type,
                            principal,
                        ),
                    ),
                )
            is DeleteRelationshipMutation -> {
                getRelationship.get(workspaceId, mutation.relationshipId)
                deleteRelationship.delete(workspaceId, mutation.relationshipId, principal)
                context.deleteRelationship(mutation.relationshipId)
            }
        }
    }
}
