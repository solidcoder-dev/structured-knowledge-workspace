package dev.skw.adapter.inbound.rest

import dev.skw.adapter.PropertyJsonMapper
import dev.skw.adapter.inbound.rest.generated.model.TransactionResponse
import dev.skw.application.entry.CreateEntryCommand
import dev.skw.application.transaction.CreateEntryMutation
import dev.skw.application.transaction.CreateRelationshipMutation
import dev.skw.application.transaction.DeleteEntryMutation
import dev.skw.application.transaction.DeleteEntryPropertyMutation
import dev.skw.application.transaction.DeleteRelationshipMutation
import dev.skw.application.transaction.InvalidTransactionReference
import dev.skw.application.transaction.LocalEntryRef
import dev.skw.application.transaction.LocalEntryReference
import dev.skw.application.transaction.PersistedEntryReference
import dev.skw.application.transaction.SetEntryPropertyMutation
import dev.skw.application.transaction.TransactionCommand
import dev.skw.application.transaction.TransactionResult
import dev.skw.domain.Version
import dev.skw.domain.entry.EntryId
import dev.skw.domain.property.PropertyName
import dev.skw.domain.relationship.RelationshipId
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId
import java.util.UUID

class TransactionRestMapper(
    private val entries: EntryRestMapper,
    private val properties: PropertyJsonMapper,
) {
    fun toCommand(
        workspaceId: UUID,
        request: TransactionRequestDto,
    ): TransactionCommand =
        TransactionCommand(
            WorkspaceId(workspaceId),
            request.mutations.map { toMutation(workspaceId, it) },
        )

    private fun toMutation(
        workspaceId: UUID,
        mutation: TransactionMutationDto,
    ): dev.skw.application.transaction.TransactionMutation =
        when (mutation) {
            is CreateEntryDto -> {
                val command: CreateEntryCommand = entries.toCreateCommand(workspaceId, mutation.entry)
                CreateEntryMutation(command.properties, command.initialRelationships, mutation.localRef?.let(::LocalEntryRef))
            }
            is SetEntryPropertyDto ->
                SetEntryPropertyMutation(
                    EntryId(mutation.entryId),
                    Version.of(mutation.expectedVersion),
                    PropertyName(mutation.`property`),
                    properties.toDomainValue(mutation.value),
                )
            is DeleteEntryPropertyDto ->
                DeleteEntryPropertyMutation(
                    EntryId(mutation.entryId),
                    Version.of(mutation.expectedVersion),
                    PropertyName(mutation.`property`),
                )
            is DeleteEntryDto ->
                DeleteEntryMutation(EntryId(mutation.entryId), Version.of(mutation.expectedVersion))
            is CreateRelationshipDto ->
                CreateRelationshipMutation(
                    toReference(mutation.relationship.sourceEntryRef),
                    toReference(mutation.relationship.targetEntryRef),
                    RelationshipType(mutation.relationship.type),
                )
            is DeleteRelationshipDto ->
                DeleteRelationshipMutation(RelationshipId(mutation.relationshipId))
        }

    private fun toReference(value: String) =
        if (value.startsWith("@")) {
            LocalEntryReference(LocalEntryRef(value.substring(1)))
        } else {
            try {
                PersistedEntryReference(EntryId(UUID.fromString(value)))
            } catch (error: IllegalArgumentException) {
                throw InvalidTransactionReference(value)
            }
        }

    fun toResponse(result: TransactionResult): TransactionResponse =
        TransactionResponse(
            createdEntryIds = result.createdEntryIds.mapValues { it.value.value },
            propertyEntries = result.entries.map(entries::toRest),
            relationships = result.relationships.map(RelationshipRestMapper::toRest),
            deletedEntryIds = result.deletedEntryIds.map(EntryId::value),
            deletedRelationshipIds = result.deletedRelationshipIds.map(RelationshipId::value),
        )
}
