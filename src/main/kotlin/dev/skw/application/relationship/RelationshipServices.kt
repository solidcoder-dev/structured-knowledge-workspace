package dev.skw.application.relationship

import dev.skw.application.port.out.EntryRepository
import dev.skw.application.port.out.RelationshipRepository
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.application.workspace.WorkspaceNotFound
import dev.skw.domain.entry.EntryId
import dev.skw.domain.relationship.Relationship
import dev.skw.domain.relationship.RelationshipId
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId
import java.time.Instant

class RelationshipAlreadyExists : RuntimeException("Relationship already exists")

class RelationshipEndpointMissing : RuntimeException("A relationship endpoint was not found in this workspace")

class RelationshipNotFound(
    id: RelationshipId,
) : RuntimeException("Relationship $id was not found")

class InvalidRelationshipCursor : RuntimeException("Invalid relationship cursor")

data class CreateRelationshipCommand(
    val workspaceId: WorkspaceId,
    val sourceEntryId: EntryId,
    val targetEntryId: EntryId,
    val type: RelationshipType,
)

fun interface CreateRelationshipUseCase {
    fun create(command: CreateRelationshipCommand): Relationship
}

class CreateRelationshipService(
    private val workspaces: WorkspaceRepository,
    private val entries: EntryRepository,
    private val relationships: RelationshipRepository,
) : CreateRelationshipUseCase {
    override fun create(command: CreateRelationshipCommand): Relationship {
        if (workspaces.findById(command.workspaceId) == null) throw WorkspaceNotFound(command.workspaceId)
        val existing = entries.findExistingIds(command.workspaceId, setOf(command.sourceEntryId, command.targetEntryId))
        if (command.sourceEntryId !in existing || command.targetEntryId !in existing) throw RelationshipEndpointMissing()
        return relationships.create(command.workspaceId, command.sourceEntryId, command.targetEntryId, command.type)
    }
}

fun interface GetRelationshipUseCase {
    fun get(
        workspaceId: WorkspaceId,
        relationshipId: RelationshipId,
    ): Relationship
}

class GetRelationshipService(
    private val relationships: RelationshipRepository,
) : GetRelationshipUseCase {
    override fun get(
        workspaceId: WorkspaceId,
        relationshipId: RelationshipId,
    ) = relationships.findById(workspaceId, relationshipId) ?: throw RelationshipNotFound(relationshipId)
}

fun interface DeleteRelationshipUseCase {
    fun delete(
        workspaceId: WorkspaceId,
        relationshipId: RelationshipId,
    )
}

class DeleteRelationshipService(
    private val relationships: RelationshipRepository,
) : DeleteRelationshipUseCase {
    override fun delete(
        workspaceId: WorkspaceId,
        relationshipId: RelationshipId,
    ) {
        if (!relationships.delete(workspaceId, relationshipId)) throw RelationshipNotFound(relationshipId)
    }
}

enum class RelationshipDirection { INCOMING, OUTGOING, BOTH }

data class RelationshipCursor(
    val workspaceId: WorkspaceId,
    val entryId: EntryId,
    val direction: RelationshipDirection,
    val type: RelationshipType?,
    val createdAt: Instant,
    val relationshipId: RelationshipId,
)

data class ListEntryRelationshipsQuery(
    val workspaceId: WorkspaceId,
    val entryId: EntryId,
    val direction: RelationshipDirection,
    val type: RelationshipType?,
    val limit: Int,
    val cursor: RelationshipCursor? = null,
)

data class RelationshipPageRequest(
    val workspaceId: WorkspaceId,
    val entryId: EntryId,
    val direction: RelationshipDirection,
    val type: RelationshipType?,
    val limit: Int,
    val after: RelationshipCursor? = null,
)

data class RelationshipPage(
    val items: List<Relationship>,
    val nextCursor: RelationshipCursor?,
)

fun interface ListEntryRelationshipsUseCase {
    fun list(query: ListEntryRelationshipsQuery): RelationshipPage
}

class ListEntryRelationshipsService(
    private val workspaces: WorkspaceRepository,
    private val entries: EntryRepository,
    private val relationships: RelationshipRepository,
) : ListEntryRelationshipsUseCase {
    override fun list(query: ListEntryRelationshipsQuery): RelationshipPage {
        require(query.limit in 1..100)
        if (workspaces.findById(query.workspaceId) == null) throw WorkspaceNotFound(query.workspaceId)
        if (entries.findById(query.workspaceId, query.entryId) == null) {
            throw dev.skw.application.entry
                .EntryNotFound(query.entryId)
        }
        query.cursor?.let { cursor ->
            if (cursor.workspaceId != query.workspaceId ||
                cursor.entryId != query.entryId ||
                cursor.direction != query.direction ||
                cursor.type != query.type
            ) {
                throw InvalidRelationshipCursor()
            }
        }
        return relationships.listForEntry(
            RelationshipPageRequest(query.workspaceId, query.entryId, query.direction, query.type, query.limit, query.cursor),
        )
    }
}
