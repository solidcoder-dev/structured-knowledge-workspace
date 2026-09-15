package dev.skw.domain.relationship

import dev.skw.domain.entry.EntryId
import dev.skw.domain.workspace.WorkspaceId
import java.time.Instant

data class Relationship(
    val id: RelationshipId,
    val workspaceId: WorkspaceId,
    val sourceEntryId: EntryId,
    val targetEntryId: EntryId,
    val type: RelationshipType,
    val createdAt: Instant,
)
