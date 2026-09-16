package dev.skw.adapter.outbound.persistence

import dev.skw.application.search.GraphDirection
import dev.skw.domain.entry.EntryId
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId
import dev.skw.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class JdbcGraphCandidateFinderContractTest
    @Autowired
    constructor(
        private val jdbc: JdbcTemplate,
        private val finder: JdbcGraphCandidateFinder,
    ) : PostgresIntegrationTest() {
        private fun workspace() =
            WorkspaceId(jdbc.queryForObject("INSERT INTO skw.workspaces DEFAULT VALUES RETURNING id", UUID::class.java)!!)

        private fun entry(workspace: WorkspaceId) =
            EntryId(
                jdbc.queryForObject(
                    "INSERT INTO skw.entries (workspace_id, properties) VALUES (?, '{}'::jsonb) RETURNING id",
                    UUID::class.java,
                    workspace.value,
                )!!,
            )

        private fun relation(
            workspace: WorkspaceId,
            source: EntryId,
            target: EntryId,
            type: String,
        ) {
            jdbc.update(
                "INSERT INTO skw.relationships (workspace_id, source_entry_id, target_entry_id, type) VALUES (?, ?, ?, ?)",
                workspace.value,
                source.value,
                target.value,
                type,
            )
        }

        @Test
        fun `direction type filtering and self links return technical neighbors once`() {
            val ws = workspace()
            val a = entry(ws)
            val b = entry(ws)
            val c = entry(ws)
            val d = entry(ws)
            relation(ws, a, b, "supports")
            relation(ws, b, c, "supports")
            relation(ws, d, b, "depends-on")
            relation(ws, b, b, "supports")
            relation(ws, c, a, "supports")
            assertEquals(setOf(b), finder.findAdjacent(ws, setOf(a), GraphDirection.OUTGOING, emptySet()))
            assertEquals(setOf(a, c, b, d), finder.findAdjacent(ws, setOf(b), GraphDirection.BOTH, emptySet()))
            assertEquals(setOf(a, b), finder.findAdjacent(ws, setOf(b), GraphDirection.INCOMING, setOf(RelationshipType("supports"))))
            assertEquals(setOf(b, c), finder.findAdjacent(ws, setOf(b), GraphDirection.OUTGOING, setOf(RelationshipType("supports"))))
        }
    }
