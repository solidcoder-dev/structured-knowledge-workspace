package dev.skw.application.port.out

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
}
