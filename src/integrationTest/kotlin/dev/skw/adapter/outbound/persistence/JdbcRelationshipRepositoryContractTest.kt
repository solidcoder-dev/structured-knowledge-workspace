package dev.skw.adapter.outbound.persistence

import dev.skw.application.relationship.RelationshipDirection
import dev.skw.application.relationship.RelationshipPageRequest
import dev.skw.domain.entry.EntryId
import dev.skw.domain.relationship.RelationshipId
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId
import dev.skw.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcRelationshipRepositoryContractTest
    @Autowired
    constructor(
        private val repository: JdbcRelationshipRepository,
        dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        private val jdbc = JdbcTemplate(dataSource)
        private val createdAt = Instant.parse("2026-01-01T00:00:00Z")
        private val workspaceId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val sourceEntryId = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private val targetEntryIds =
            listOf(
                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                UUID.fromString("00000000-0000-0000-0000-000000000012"),
                UUID.fromString("00000000-0000-0000-0000-000000000013"),
            )
        private val relationshipIds =
            listOf(
                UUID.fromString("00000000-0000-0000-0000-000000000021"),
                UUID.fromString("00000000-0000-0000-0000-000000000022"),
                UUID.fromString("00000000-0000-0000-0000-000000000023"),
            )

        @BeforeEach
        fun clean() {
            jdbc.update("DELETE FROM skw.relationships")
            jdbc.update("DELETE FROM skw.entries")
            jdbc.update("DELETE FROM skw.workspaces")
        }

        @Test
        fun `list continues to the next page using the returned cursor`() {
            jdbc.update("INSERT INTO skw.workspaces (id) VALUES (?)", workspaceId)
            (listOf(sourceEntryId) + targetEntryIds)
                .forEach { entryId ->
                    jdbc.update(
                        "INSERT INTO skw.entries (workspace_id, id) VALUES (?, ?)",
                        workspaceId,
                        entryId,
                    )
                }
            targetEntryIds.forEachIndexed { index, targetEntryId ->
                jdbc.update(
                    """INSERT INTO skw.relationships (workspace_id, id, source_entry_id, target_entry_id, type, created_at)
                       VALUES (?, ?, ?, ?, ?, ?)""",
                    workspaceId,
                    relationshipIds[index],
                    sourceEntryId,
                    targetEntryId,
                    "supports",
                    Timestamp.from(createdAt),
                )
            }

            val first =
                repository.listForEntry(
                    RelationshipPageRequest(
                        WorkspaceId(workspaceId),
                        EntryId(sourceEntryId),
                        RelationshipDirection.OUTGOING,
                        RelationshipType("supports"),
                        limit = 2,
                    ),
                )

            val second =
                repository.listForEntry(
                    RelationshipPageRequest(
                        WorkspaceId(workspaceId),
                        EntryId(sourceEntryId),
                        RelationshipDirection.OUTGOING,
                        RelationshipType("supports"),
                        limit = 2,
                        after = first.nextCursor,
                    ),
                )

            assertEquals(relationshipIds.take(2), first.items.map { it.id.value })
            assertEquals(listOf(relationshipIds[2]), second.items.map { it.id.value })
        }
    }
