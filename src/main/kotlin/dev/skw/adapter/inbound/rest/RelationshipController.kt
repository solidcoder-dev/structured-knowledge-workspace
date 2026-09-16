package dev.skw.adapter.inbound.rest

import dev.skw.adapter.inbound.rest.generated.api.RelationshipsApi
import dev.skw.adapter.inbound.rest.generated.model.CreateRelationshipRequest
import dev.skw.adapter.inbound.rest.generated.model.Relationship
import dev.skw.adapter.inbound.rest.generated.model.RelationshipPage
import dev.skw.application.idempotency.IdempotencyScope
import dev.skw.application.relationship.CreateRelationshipUseCase
import dev.skw.application.relationship.DeleteRelationshipUseCase
import dev.skw.application.relationship.GetRelationshipUseCase
import dev.skw.application.relationship.ListEntryRelationshipsQuery
import dev.skw.application.relationship.ListEntryRelationshipsUseCase
import dev.skw.application.relationship.RelationshipDirection
import dev.skw.domain.entry.EntryId
import dev.skw.domain.relationship.RelationshipId
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
class RelationshipController(
    private val createRelationship: CreateRelationshipUseCase,
    private val getRelationship: GetRelationshipUseCase,
    private val deleteRelationship: DeleteRelationshipUseCase,
    private val listEntryRelationships: ListEntryRelationshipsUseCase,
    private val cursorCodec: RelationshipCursorCodec,
    private val idempotent: IdempotentRestExecutor,
) : RelationshipsApi {
    override fun createRelationship(
        workspaceId: UUID,
        idempotencyKey: String,
        createRelationshipRequest: CreateRelationshipRequest,
    ): ResponseEntity<Relationship> =
        idempotent.execute(
            IdempotencyScope("POST", "/api/v1/workspaces/{workspaceId}/relationships", workspaceId.toString()),
            idempotencyKey,
            createRelationshipRequest,
            Relationship::class.java,
        ) {
            val created = createRelationship.create(RelationshipRestMapper.toCreateCommand(workspaceId, createRelationshipRequest))
            ResponseEntity
                .created(URI.create("/api/v1/workspaces/$workspaceId/relationships/${created.id}"))
                .body(RelationshipRestMapper.toRest(created))
        }

    override fun getRelationship(
        workspaceId: UUID,
        relationshipId: UUID,
    ): ResponseEntity<Relationship> =
        ResponseEntity.ok(RelationshipRestMapper.toRest(getRelationship.get(WorkspaceId(workspaceId), RelationshipId(relationshipId))))

    override fun deleteRelationship(
        workspaceId: UUID,
        relationshipId: UUID,
    ): ResponseEntity<Unit> {
        deleteRelationship.delete(WorkspaceId(workspaceId), RelationshipId(relationshipId))
        return ResponseEntity.noContent().build()
    }

    override fun listEntryRelationships(
        workspaceId: UUID,
        entryId: UUID,
        direction: String,
        type: String?,
        cursor: String?,
        limit: Int,
    ): ResponseEntity<RelationshipPage> {
        val parsedDirection =
            try {
                RelationshipDirection.valueOf(direction)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid relationship direction", error)
            }
        val page =
            listEntryRelationships.list(
                ListEntryRelationshipsQuery(
                    WorkspaceId(workspaceId),
                    EntryId(entryId),
                    parsedDirection,
                    type?.let(::RelationshipType),
                    limit,
                    cursor?.let(cursorCodec::decode),
                ),
            )
        return ResponseEntity.ok(RelationshipRestMapper.toPage(page, page.nextCursor?.let(cursorCodec::encode)))
    }
}
