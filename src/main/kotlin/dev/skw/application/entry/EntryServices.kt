package dev.skw.application.entry

import dev.skw.application.port.out.EntryDeleteResult
import dev.skw.application.port.out.EntryPageRequest
import dev.skw.application.port.out.EntryRepository
import dev.skw.application.port.out.RelationshipRepository
import dev.skw.application.port.out.SaveResult
import dev.skw.application.port.out.TransactionRunner
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.application.relationship.RelationshipEndpointMissing
import dev.skw.application.workspace.WorkspaceNotFound
import dev.skw.domain.Version
import dev.skw.domain.entry.Entry
import dev.skw.domain.entry.EntryId
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.relationship.Relationship
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId
import java.time.Clock
import java.time.Instant

data class InitialRelationshipCommand(
    val direction: Direction,
    val otherEntryId: EntryId,
    val type: RelationshipType,
)

enum class Direction { OUTGOING, INCOMING }

data class CreateEntryCommand(
    val workspaceId: WorkspaceId,
    val properties: Map<PropertyName, PropertyValue> = emptyMap(),
    val initialRelationships: List<InitialRelationshipCommand> = emptyList(),
)

data class CreateEntryResult(
    val entry: Entry,
    val relationships: List<Relationship>,
)

class EntryNotFound(
    id: EntryId,
) : RuntimeException("Entry $id was not found")

class EntryVersionConflict(
    val id: EntryId,
    expected: Version,
) : RuntimeException("Entry $id does not have version ${expected.value}")

class EntryConnected(
    id: EntryId,
) : RuntimeException("Entry $id has relationships")

fun interface CreateEntryUseCase {
    fun create(command: CreateEntryCommand): CreateEntryResult
}

fun interface GetEntryUseCase {
    fun get(
        workspaceId: WorkspaceId,
        entryId: EntryId,
    ): Entry
}

fun interface ListEntriesUseCase {
    fun list(query: ListEntriesQuery): EntryPage
}

data class ListEntriesQuery(
    val workspaceId: WorkspaceId,
    val limit: Int,
    val cursor: EntryCursor? = null,
)

data class EntryCursor(
    val workspaceId: WorkspaceId,
    val createdAt: Instant,
    val entryId: EntryId,
)

data class EntryPage(
    val items: List<Entry>,
    val nextCursor: EntryCursor?,
)

class CreateEntryService(
    private val workspaceRepository: WorkspaceRepository,
    private val entryRepository: EntryRepository,
    private val relationshipRepository: RelationshipRepository,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) : CreateEntryUseCase {
    override fun create(command: CreateEntryCommand): CreateEntryResult {
        require(command.initialRelationships.size <= 100)
        if (workspaceRepository.findById(command.workspaceId) == null) throw WorkspaceNotFound(command.workspaceId)
        val ids = command.initialRelationships.map { it.otherEntryId }.toSet()
        val missing = ids - entryRepository.findExistingIds(command.workspaceId, ids)
        if (missing.isNotEmpty()) throw RelationshipEndpointMissing()
        return transactionRunner.inTransaction {
            val entry = entryRepository.save(Entry.create(command.workspaceId, command.properties, clock.instant()))
            val relationships =
                command.initialRelationships.map { relationship ->
                    val (source, target) =
                        if (relationship.direction ==
                            Direction.OUTGOING
                        ) {
                            entry.id to relationship.otherEntryId
                        } else {
                            relationship.otherEntryId to entry.id
                        }
                    relationshipRepository.create(command.workspaceId, source, target, relationship.type)
                }
            CreateEntryResult(entry, relationships)
        }
    }
}

class GetEntryService(
    private val repository: EntryRepository,
) : GetEntryUseCase {
    override fun get(
        workspaceId: WorkspaceId,
        entryId: EntryId,
    ) = repository.findById(workspaceId, entryId) ?: throw EntryNotFound(entryId)
}

class ListEntriesService(
    private val workspaceRepository: WorkspaceRepository,
    private val repository: EntryRepository,
) : ListEntriesUseCase {
    override fun list(query: ListEntriesQuery): EntryPage {
        require(query.limit in 1..100)
        if (workspaceRepository.findById(query.workspaceId) == null) throw WorkspaceNotFound(query.workspaceId)
        return repository.list(EntryPageRequest(query.workspaceId, query.limit, query.cursor))
    }
}

class SetEntryPropertyService(
    private val repository: EntryRepository,
    private val getEntry: GetEntryUseCase = GetEntryService(repository),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun set(
        workspaceId: WorkspaceId,
        entryId: EntryId,
        expectedVersion: Version,
        name: PropertyName,
        value: PropertyValue,
    ) = persist(getEntry.get(workspaceId, entryId).setProperty(name, value, clock.instant()), expectedVersion)

    private fun persist(
        entry: Entry,
        expected: Version,
    ) = when (repository.saveIfVersion(entry, expected)) {
        SaveResult.SAVED -> entry
        SaveResult.NOT_FOUND -> throw EntryNotFound(entry.id)
        SaveResult.VERSION_CONFLICT -> throw EntryVersionConflict(entry.id, expected)
    }
}

class DeleteEntryPropertyService(
    private val repository: EntryRepository,
    private val getEntry: GetEntryUseCase = GetEntryService(repository),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun delete(
        workspaceId: WorkspaceId,
        entryId: EntryId,
        expectedVersion: Version,
        name: PropertyName,
    ) = persist(getEntry.get(workspaceId, entryId).removeProperty(name, clock.instant()), expectedVersion)

    private fun persist(
        entry: Entry,
        expected: Version,
    ) = when (repository.saveIfVersion(entry, expected)) {
        SaveResult.SAVED -> entry
        SaveResult.NOT_FOUND -> throw EntryNotFound(entry.id)
        SaveResult.VERSION_CONFLICT -> throw EntryVersionConflict(entry.id, expected)
    }
}

fun interface DeleteEntryUseCase {
    fun delete(
        workspaceId: WorkspaceId,
        entryId: EntryId,
        expectedVersion: Version,
    )
}

class DeleteEntryService(
    private val repository: EntryRepository,
) : DeleteEntryUseCase {
    override fun delete(
        workspaceId: WorkspaceId,
        entryId: EntryId,
        expectedVersion: Version,
    ) = when (repository.delete(workspaceId, entryId, expectedVersion)) {
        EntryDeleteResult.DELETED -> Unit
        EntryDeleteResult.NOT_FOUND -> throw EntryNotFound(entryId)
        EntryDeleteResult.VERSION_CONFLICT -> throw EntryVersionConflict(entryId, expectedVersion)
        EntryDeleteResult.CONNECTED -> throw EntryConnected(entryId)
    }
}
