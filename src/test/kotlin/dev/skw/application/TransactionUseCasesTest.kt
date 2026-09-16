package dev.skw.application

import dev.skw.application.entry.CreateEntryService
import dev.skw.application.entry.DeleteEntryPropertyService
import dev.skw.application.entry.DeleteEntryService
import dev.skw.application.entry.SetEntryPropertyService
import dev.skw.application.port.out.DeleteResult
import dev.skw.application.port.out.EntryDeleteResult
import dev.skw.application.port.out.EntryPageRequest
import dev.skw.application.port.out.EntryRepository
import dev.skw.application.port.out.RelationshipRepository
import dev.skw.application.port.out.SaveResult
import dev.skw.application.port.out.TransactionRunner
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.application.relationship.CreateRelationshipService
import dev.skw.application.relationship.DeleteRelationshipService
import dev.skw.application.relationship.GetRelationshipService
import dev.skw.application.relationship.RelationshipPage
import dev.skw.application.relationship.RelationshipPageRequest
import dev.skw.application.transaction.CreateEntryMutation
import dev.skw.application.transaction.CreateRelationshipMutation
import dev.skw.application.transaction.ExecuteTransactionService
import dev.skw.application.transaction.LocalEntryRef
import dev.skw.application.transaction.LocalEntryReference
import dev.skw.application.transaction.SetEntryPropertyMutation
import dev.skw.application.transaction.TransactionCommand
import dev.skw.application.transaction.TransactionMutationFailed
import dev.skw.domain.Version
import dev.skw.domain.entry.Entry
import dev.skw.domain.entry.EntryId
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
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

class TransactionUseCasesTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `mutations execute in order and local refs resolve only to prior entries`() {
        val fixture = Fixture()
        val result =
            fixture.service.execute(
                TransactionCommand(
                    fixture.workspace.id,
                    listOf(
                        CreateEntryMutation(localRef = LocalEntryRef("a")),
                        CreateEntryMutation(localRef = LocalEntryRef("b")),
                        CreateRelationshipMutation(
                            LocalEntryReference(LocalEntryRef("a")),
                            LocalEntryReference(LocalEntryRef("b")),
                            RelationshipType("supports"),
                        ),
                    ),
                ),
            )
        assertEquals(listOf("a", "b"), result.createdEntryIds.keys.toList())
        assertEquals(2, result.entries.size)
        assertEquals(1, result.relationships.size)
        assertEquals(
            result.createdEntryIds.getValue("a").value,
            result.relationships
                .single()
                .sourceEntryId.value,
        )
        assertEquals(
            result.createdEntryIds.getValue("b").value,
            result.relationships
                .single()
                .targetEntryId.value,
        )
    }

    @Test
    fun `duplicate and forward local refs report the failing mutation index`() {
        val duplicate = Fixture()
        val duplicateFailure =
            assertThrows(TransactionMutationFailed::class.java) {
                duplicate.service.execute(
                    TransactionCommand(
                        duplicate.workspace.id,
                        listOf(CreateEntryMutation(localRef = LocalEntryRef("a")), CreateEntryMutation(localRef = LocalEntryRef("a"))),
                    ),
                )
            }
        assertEquals(1, duplicateFailure.index)

        val forward = Fixture()
        val forwardFailure =
            assertThrows(TransactionMutationFailed::class.java) {
                forward.service.execute(
                    TransactionCommand(
                        forward.workspace.id,
                        listOf(
                            CreateRelationshipMutation(
                                LocalEntryReference(LocalEntryRef("later")),
                                LocalEntryReference(LocalEntryRef("later")),
                                RelationshipType("supports"),
                            ),
                            CreateEntryMutation(localRef = LocalEntryRef("later")),
                        ),
                    ),
                )
            }
        assertEquals(0, forwardFailure.index)
    }

    @Test
    fun `effective versions advance sequentially while a no-op preserves the version`() {
        val fixture = Fixture()
        val entry = Entry.create(fixture.workspace.id, mapOf(PropertyName("x") to PropertyValue.StringValue("old")), now)
        fixture.entries.save(entry)
        val result =
            fixture.service.execute(
                TransactionCommand(
                    fixture.workspace.id,
                    listOf(
                        SetEntryPropertyMutation(entry.id, Version.of(1), PropertyName("x"), PropertyValue.StringValue("new")),
                        SetEntryPropertyMutation(entry.id, Version.of(2), PropertyName("y"), PropertyValue.StringValue("value")),
                        dev.skw.application.transaction
                            .DeleteEntryPropertyMutation(entry.id, Version.of(3), PropertyName("absent")),
                        SetEntryPropertyMutation(entry.id, Version.of(3), PropertyName("z"), PropertyValue.StringValue("last")),
                    ),
                ),
            )
        assertEquals(
            4,
            result.entries
                .single()
                .version.value,
        )
        assertEquals(
            setOf(PropertyName("x"), PropertyName("y"), PropertyName("z")),
            result.entries
                .single()
                .properties.keys,
        )
    }

    private inner class Fixture {
        val workspace = Workspace.create(now = now)
        val entries = FakeEntries()
        val relationships = FakeRelationships(entries)
        val service =
            ExecuteTransactionService(
                FakeWorkspaces(workspace),
                CreateEntryService(FakeWorkspaces(workspace), entries, relationships, ImmediateTransaction),
                SetEntryPropertyService(entries),
                DeleteEntryPropertyService(entries),
                DeleteEntryService(entries),
                CreateRelationshipService(FakeWorkspaces(workspace), entries, relationships),
                GetRelationshipService(relationships),
                DeleteRelationshipService(relationships),
                ImmediateTransaction,
            )
    }

    private object ImmediateTransaction : TransactionRunner {
        override fun <T> inTransaction(action: () -> T): T = action()
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
        ) = DeleteResult.DELETED

        override fun list(request: dev.skw.application.workspace.WorkspacePageRequest) =
            dev.skw.application.workspace
                .WorkspacePage(emptyList(), null)
    }

    private class FakeEntries : EntryRepository {
        private val stored = linkedMapOf<EntryId, Entry>()
        lateinit var relationships: FakeRelationships

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
            .filter { it.workspaceId == workspaceId && it.id in ids }
            .map { it.id }
            .toSet()

        override fun saveIfVersion(
            entry: Entry,
            expectedVersion: Version,
        ): SaveResult {
            val current = stored[entry.id] ?: return SaveResult.NOT_FOUND
            if (current.version != expectedVersion) return SaveResult.VERSION_CONFLICT
            stored[entry.id] = entry
            return SaveResult.SAVED
        }

        override fun delete(
            workspaceId: WorkspaceId,
            entryId: EntryId,
            expectedVersion: Version,
        ): EntryDeleteResult {
            val current = findById(workspaceId, entryId) ?: return EntryDeleteResult.NOT_FOUND
            if (current.version != expectedVersion) return EntryDeleteResult.VERSION_CONFLICT
            if (relationships.isConnected(entryId)) return EntryDeleteResult.CONNECTED
            stored.remove(entryId)
            return EntryDeleteResult.DELETED
        }

        override fun list(request: EntryPageRequest) =
            dev.skw.application.entry
                .EntryPage(emptyList(), null)
    }

    private class FakeRelationships(
        private val entries: FakeEntries,
    ) : RelationshipRepository {
        private val stored = linkedMapOf<RelationshipId, Relationship>()

        init {
            entries.relationships = this
        }

        fun isConnected(entryId: EntryId) = stored.values.any { it.sourceEntryId == entryId || it.targetEntryId == entryId }

        override fun create(
            workspaceId: WorkspaceId,
            sourceEntryId: EntryId,
            targetEntryId: EntryId,
            type: RelationshipType,
        ) = Relationship(
            RelationshipId(UUID.randomUUID()),
            workspaceId,
            sourceEntryId,
            targetEntryId,
            type,
            Instant.parse("2026-01-01T00:00:00Z"),
        ).also { stored[it.id] = it }

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
