package dev.skw.application

import dev.skw.application.entry.CreateEntryCommand
import dev.skw.application.entry.CreateEntryService
import dev.skw.application.entry.Direction
import dev.skw.application.entry.InitialRelationshipCommand
import dev.skw.application.entry.RelationshipAlreadyExists
import dev.skw.application.port.out.EntryDeleteResult
import dev.skw.application.port.out.EntryPageRequest
import dev.skw.application.port.out.EntryRepository
import dev.skw.application.port.out.RelationshipRepository
import dev.skw.application.port.out.SaveResult
import dev.skw.application.port.out.TransactionRunner
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.application.workspace.WorkspacePage
import dev.skw.application.workspace.WorkspacePageRequest
import dev.skw.domain.Version
import dev.skw.domain.entry.Entry
import dev.skw.domain.entry.EntryId
import dev.skw.domain.relationship.Relationship
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.Workspace
import dev.skw.domain.workspace.WorkspaceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class EntryUseCasesTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `create maps directions and preserves relationship order`() {
        val workspace = Workspace.create(now = now)
        val other = Entry.create(workspace.id, now = now)
        val entries = FakeEntries(other)
        val relationships = FakeRelationships()
        val result =
            CreateEntryService(FakeWorkspaces(workspace), entries, relationships, ImmediateTransaction).create(
                CreateEntryCommand(
                    workspace.id,
                    initialRelationships =
                        listOf(
                            InitialRelationshipCommand(Direction.OUTGOING, other.id, RelationshipType("supports")),
                            InitialRelationshipCommand(Direction.INCOMING, other.id, RelationshipType("depends-on")),
                        ),
                ),
            )
        assertEquals(listOf(result.entry.id to other.id, other.id to result.entry.id), relationships.edges)
    }

    @Test
    fun `relationship failure is contained by transaction boundary`() {
        val workspace = Workspace.create(now = now)
        val entryRepository = FakeEntries()
        val runner = RecordingTransaction(RelationshipAlreadyExists())
        assertThrows(RelationshipAlreadyExists::class.java) {
            CreateEntryService(
                FakeWorkspaces(workspace),
                entryRepository,
                FakeRelationships(),
                runner,
            ).create(CreateEntryCommand(workspace.id))
        }
        assertEquals(1, runner.calls)
    }

    private object ImmediateTransaction : TransactionRunner {
        override fun <T> inTransaction(action: () -> T) = action()
    }

    private class RecordingTransaction(
        private val failure: RuntimeException,
    ) : TransactionRunner {
        var calls = 0

        override fun <T> inTransaction(action: () -> T): T {
            calls++
            throw failure
        }
    }

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
        ) = dev.skw.application.port.out.DeleteResult.DELETED

        override fun list(request: WorkspacePageRequest) = WorkspacePage(emptyList(), null)
    }

    private class FakeEntries(
        vararg initial: Entry,
    ) : EntryRepository {
        private val stored = initial.associateBy { it.id }.toMutableMap()

        override fun save(entry: Entry): Entry {
            stored[entry.id] = entry
            return entry
        }

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

    private class FakeRelationships : RelationshipRepository {
        val edges = mutableListOf<Pair<EntryId, EntryId>>()

        override fun create(
            workspaceId: WorkspaceId,
            sourceEntryId: EntryId,
            targetEntryId: EntryId,
            type: RelationshipType,
        ): Relationship {
            edges += sourceEntryId to targetEntryId
            return Relationship(
                dev.skw.domain.relationship
                    .RelationshipId(java.util.UUID.randomUUID()),
                workspaceId,
                sourceEntryId,
                targetEntryId,
                type,
                Instant.parse("2026-01-01T00:00:00Z"),
            )
        }
    }
}
