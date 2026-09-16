package dev.skw.application

import dev.skw.application.port.out.DeleteResult
import dev.skw.application.port.out.EntryDeleteResult
import dev.skw.application.port.out.EntryPageRequest
import dev.skw.application.port.out.EntryRepository
import dev.skw.application.port.out.RelationshipRepository
import dev.skw.application.port.out.SaveResult
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.application.relationship.CreateRelationshipCommand
import dev.skw.application.relationship.CreateRelationshipService
import dev.skw.application.relationship.DeleteRelationshipService
import dev.skw.application.relationship.GetRelationshipService
import dev.skw.application.relationship.InvalidRelationshipCursor
import dev.skw.application.relationship.ListEntryRelationshipsQuery
import dev.skw.application.relationship.ListEntryRelationshipsService
import dev.skw.application.relationship.RelationshipCursor
import dev.skw.application.relationship.RelationshipDirection
import dev.skw.application.relationship.RelationshipNotFound
import dev.skw.application.relationship.RelationshipPage
import dev.skw.application.relationship.RelationshipPageRequest
import dev.skw.domain.Version
import dev.skw.domain.entry.Entry
import dev.skw.domain.entry.EntryId
import dev.skw.domain.relationship.Relationship
import dev.skw.domain.relationship.RelationshipId
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.Workspace
import dev.skw.domain.workspace.WorkspaceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RelationshipUseCasesTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `create validates workspace and both endpoints before delegating`() {
        val workspace = Workspace.create(now = now)
        val source = Entry.create(workspace.id, now = now)
        val target = Entry.create(workspace.id, now = now)
        val relationships = RecordingRelationships()
        val created =
            CreateRelationshipService(FakeWorkspaces(workspace), FakeEntries(source, target), relationships).create(
                CreateRelationshipCommand(workspace.id, source.id, target.id, RelationshipType("supports")),
            )
        assertEquals(created, relationships.created)
        assertThrows(dev.skw.application.relationship.RelationshipEndpointMissing::class.java) {
            CreateRelationshipService(FakeWorkspaces(workspace), FakeEntries(source), relationships).create(
                CreateRelationshipCommand(workspace.id, source.id, target.id, RelationshipType("supports")),
            )
        }
    }

    @Test
    fun `get and delete preserve workspace isolation and missing semantics`() {
        val workspace = Workspace.create(now = now)
        val otherWorkspace = Workspace.create(now = now)
        val edge = relationship(workspace.id)
        val relationships = RecordingRelationships(edge)
        assertEquals(edge, GetRelationshipService(relationships).get(workspace.id, edge.id))
        assertThrows(RelationshipNotFound::class.java) { GetRelationshipService(relationships).get(otherWorkspace.id, edge.id) }
        DeleteRelationshipService(relationships).delete(workspace.id, edge.id)
        assertThrows(RelationshipNotFound::class.java) { DeleteRelationshipService(relationships).delete(workspace.id, edge.id) }
    }

    @Test
    fun `list validates entry and cursor context`() {
        val workspace = Workspace.create(now = now)
        val entry = Entry.create(workspace.id, now = now)
        val relationships = RecordingRelationships()
        val service = ListEntryRelationshipsService(FakeWorkspaces(workspace), FakeEntries(entry), relationships)
        assertEquals(
            emptyList<Relationship>(),
            service.list(ListEntryRelationshipsQuery(workspace.id, entry.id, RelationshipDirection.BOTH, null, 25)).items,
        )
        val cursor =
            RelationshipCursor(workspace.id, entry.id, RelationshipDirection.OUTGOING, null, now, RelationshipId(UUID.randomUUID()))
        assertThrows(InvalidRelationshipCursor::class.java) {
            service.list(ListEntryRelationshipsQuery(workspace.id, entry.id, RelationshipDirection.INCOMING, null, 25, cursor))
        }
        assertThrows(dev.skw.application.entry.EntryNotFound::class.java) {
            service.list(ListEntryRelationshipsQuery(workspace.id, EntryId(UUID.randomUUID()), RelationshipDirection.BOTH, null, 25))
        }
    }

    private fun relationship(workspaceId: WorkspaceId) =
        Relationship(
            RelationshipId(UUID.randomUUID()),
            workspaceId,
            EntryId(UUID.randomUUID()),
            EntryId(UUID.randomUUID()),
            RelationshipType("supports"),
            now,
        )

    private class FakeWorkspaces(
        private val workspace: Workspace,
    ) : WorkspaceRepository {
        override fun save(workspace: Workspace) = workspace

        override fun findById(id: WorkspaceId) = workspace.takeIf { it.id == id }

        override fun saveIfVersion(
            workspace: Workspace,
            expectedVersion: Version,
        ) = SaveResult.SAVED

        override fun delete(
            id: WorkspaceId,
            expectedVersion: Version,
        ) = DeleteResult.DELETED

        override fun list(request: dev.skw.application.workspace.WorkspacePageRequest) =
            dev.skw.application.workspace
                .WorkspacePage(emptyList(), null)
    }

    private class FakeEntries(
        vararg entries: Entry,
    ) : EntryRepository {
        private val stored = entries.associateBy { it.id }

        override fun save(entry: Entry) = entry

        override fun findById(
            workspaceId: WorkspaceId,
            entryId: EntryId,
        ) = stored[entryId]?.takeIf { it.workspaceId == workspaceId }

        override fun findExistingIds(
            workspaceId: WorkspaceId,
            ids: Set<EntryId>,
        ) = stored.values
            .filter {
                it.workspaceId == workspaceId &&
                    it.id in ids
            }.map { it.id }
            .toSet()

        override fun saveIfVersion(
            entry: Entry,
            expectedVersion: Version,
        ) = SaveResult.SAVED

        override fun delete(
            workspaceId: WorkspaceId,
            entryId: EntryId,
            expectedVersion: Version,
        ) = EntryDeleteResult.DELETED

        override fun list(request: EntryPageRequest) =
            dev.skw.application.entry
                .EntryPage(emptyList(), null)
    }

    private class RecordingRelationships(
        vararg initial: Relationship,
    ) : RelationshipRepository {
        private val stored = initial.associateBy { it.id }.toMutableMap()
        var created: Relationship? = null

        override fun create(
            workspaceId: WorkspaceId,
            sourceEntryId: EntryId,
            targetEntryId: EntryId,
            type: RelationshipType,
        ): Relationship =
            Relationship(
                RelationshipId(UUID.randomUUID()),
                workspaceId,
                sourceEntryId,
                targetEntryId,
                type,
                Instant.parse("2026-01-01T00:00:00Z"),
            ).also {
                created = it
                stored[it.id] = it
            }

        override fun findById(
            workspaceId: WorkspaceId,
            relationshipId: RelationshipId,
        ) = stored[relationshipId]?.takeIf {
            it.workspaceId ==
                workspaceId
        }

        override fun delete(
            workspaceId: WorkspaceId,
            relationshipId: RelationshipId,
        ) = stored.remove(relationshipId)?.workspaceId == workspaceId

        override fun listForEntry(request: RelationshipPageRequest) = RelationshipPage(emptyList(), null)
    }
}
