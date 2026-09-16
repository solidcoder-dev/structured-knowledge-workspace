package dev.skw.adapter.inbound.rest

import dev.skw.adapter.inbound.rest.generated.model.Mutation
import dev.skw.adapter.inbound.rest.generated.model.TransactionRequest
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
) {
    fun toCommand(
        workspaceId: UUID,
        request: TransactionRequest,
    ): TransactionCommand =
        TransactionCommand(
            WorkspaceId(workspaceId),
            request.mutations.map { toMutation(workspaceId, it) },
        )

    private fun toMutation(
        workspaceId: UUID,
        mutation: Mutation,
    ): dev.skw.application.transaction.TransactionMutation {
        val decoded = mutation as? BaseDecodedMutation ?: throw IllegalArgumentException("Unsupported transaction mutation")
        return when (decoded.kind) {
            DecodedMutationKind.CREATE_ENTRY -> {
                val command: CreateEntryCommand = entries.toCreateCommand(workspaceId, decoded.entry)
                CreateEntryMutation(command.properties, command.initialRelationships, decoded.localRef?.let(::LocalEntryRef))
            }
            DecodedMutationKind.SET_ENTRY_PROPERTY ->
                SetEntryPropertyMutation(
                    EntryId(decoded.entryId),
                    Version.of(decoded.expectedVersion),
                    PropertyName(decoded.`property`),
                    entries.toDomainValue(decoded.value),
                )
            DecodedMutationKind.DELETE_ENTRY_PROPERTY ->
                DeleteEntryPropertyMutation(
                    EntryId(decoded.entryId),
                    Version.of(decoded.expectedVersion),
                    PropertyName(decoded.`property`),
                )
            DecodedMutationKind.DELETE_ENTRY -> DeleteEntryMutation(EntryId(decoded.entryId), Version.of(decoded.expectedVersion))
            DecodedMutationKind.CREATE_RELATIONSHIP ->
                CreateRelationshipMutation(
                    toReference(decoded.relationship.sourceEntryRef),
                    toReference(decoded.relationship.targetEntryRef),
                    RelationshipType(decoded.relationship.type),
                )
            DecodedMutationKind.DELETE_RELATIONSHIP -> DeleteRelationshipMutation(RelationshipId(decoded.relationshipId))
        }
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
