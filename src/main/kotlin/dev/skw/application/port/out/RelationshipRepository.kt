package dev.skw.application.port.out

import dev.skw.application.relationship.RelationshipPage
import dev.skw.application.relationship.RelationshipPageRequest
import dev.skw.domain.entry.EntryId
import dev.skw.domain.relationship.Relationship
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId

interface RelationshipRepository {
    fun create(
        workspaceId: WorkspaceId,
        sourceEntryId: EntryId,
        targetEntryId: EntryId,
        type: RelationshipType,
    ): Relationship

    fun findById(
        workspaceId: WorkspaceId,
        relationshipId: dev.skw.domain.relationship.RelationshipId,
    ): Relationship?

    fun delete(
        workspaceId: WorkspaceId,
        relationshipId: dev.skw.domain.relationship.RelationshipId,
    ): Boolean

    fun listForEntry(request: RelationshipPageRequest): RelationshipPage
}
