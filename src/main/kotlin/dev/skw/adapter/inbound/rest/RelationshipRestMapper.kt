package dev.skw.adapter.inbound.rest

import dev.skw.adapter.inbound.rest.generated.model.CreateRelationshipRequest
import dev.skw.adapter.inbound.rest.generated.model.Relationship
import dev.skw.adapter.inbound.rest.generated.model.RelationshipPage
import dev.skw.application.relationship.CreateRelationshipCommand
import dev.skw.domain.entry.EntryId
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import dev.skw.application.relationship.RelationshipPage as ApplicationRelationshipPage

object RelationshipRestMapper {
    fun toCreateCommand(
        workspaceId: UUID,
        request: CreateRelationshipRequest,
    ) = CreateRelationshipCommand(
        WorkspaceId(workspaceId),
        EntryId(request.sourceEntryId),
        EntryId(request.targetEntryId),
        RelationshipType(request.type),
    )

    fun toRest(relationship: dev.skw.domain.relationship.Relationship) =
        Relationship(
            relationship.id.value,
            relationship.workspaceId.value,
            relationship.sourceEntryId.value,
            relationship.targetEntryId.value,
            relationship.type.value,
            OffsetDateTime.ofInstant(relationship.createdAt, ZoneOffset.UTC),
        )

    fun toPage(
        page: ApplicationRelationshipPage,
        nextCursor: String?,
    ) = RelationshipPage(page.items.map(::toRest), nextCursor)
}
