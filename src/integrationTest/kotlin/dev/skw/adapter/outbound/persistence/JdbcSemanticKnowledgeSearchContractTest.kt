package dev.skw.adapter.outbound.persistence

import dev.skw.application.search.PropertyFilter
import dev.skw.application.search.PropertyFilterOperator
import dev.skw.application.semantic.EmbeddingProfile
import dev.skw.application.semantic.EmbeddingVector
import dev.skw.application.semantic.SemanticSearchPlan
import dev.skw.domain.entry.EntryId
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.workspace.WorkspaceId
import dev.skw.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class JdbcSemanticKnowledgeSearchContractTest
    @Autowired
    constructor(
        private val jdbc: JdbcTemplate,
        private val search: JdbcSemanticKnowledgeSearch,
    ) : PostgresIntegrationTest() {
        private val profile = EmbeddingProfile("test/model-v1", 3)

        private fun workspace() =
            WorkspaceId(jdbc.queryForObject("INSERT INTO skw.workspaces DEFAULT VALUES RETURNING id", UUID::class.java)!!)

        private fun entry(
            workspace: WorkspaceId,
            properties: String,
        ): EntryId =
            EntryId(
                jdbc.queryForObject(
                    "INSERT INTO skw.entries (workspace_id, properties) VALUES (?, ?::jsonb) RETURNING id",
                    UUID::class.java,
                    workspace.value,
                    properties,
                )!!,
            )

        private fun projection(
            workspace: WorkspaceId,
            entry: EntryId,
            vector: String,
            version: Long = 1,
        ) {
            jdbc.update(
                """INSERT INTO skw.entry_semantic_embeddings
                   (workspace_id, entry_id, profile_id, source_version, content_hash, dimensions, embedding)
                   VALUES (?, ?, ?, ?, ?, ?, ?::public.vector)""",
                workspace.value,
                entry.value,
                profile.profileId,
                version,
                "a".repeat(64),
                profile.dimensions,
                vector,
            )
        }

        private fun plan(
            workspace: WorkspaceId,
            vector: List<Double>,
            limit: Int = 25,
            filters: List<PropertyFilter> = emptyList(),
            candidates: Set<EntryId>? = null,
            continuation: dev.skw.application.search.SearchCursor? = null,
        ) = SemanticSearchPlan(workspace, profile, EmbeddingVector.of(vector), filters, candidates, continuation, limit, "fp")

        @Test
        fun `ranks by cosine distance converts score and paginates with keyset`() {
            val ws = workspace()
            val a = entry(ws, "{\"kind\":\"a\"}")
            val b = entry(ws, "{\"kind\":\"b\"}")
            val c = entry(ws, "{\"kind\":\"c\"}")
            projection(ws, a, "[1,0,0]")
            projection(ws, b, "[0.8,0.2,0]")
            projection(ws, c, "[0,1,0]")
            val first = search.search(plan(ws, listOf(1.0, 0.0, 0.0), limit = 2))
            val second = search.search(plan(ws, listOf(1.0, 0.0, 0.0), limit = 2, continuation = first.nextCursor))
            assertEquals(listOf(a, b), first.items.map { it.entry.id })
            assertEquals(listOf(c), second.items.map { it.entry.id })
            assertTrue(first.items.zipWithNext().all { it.first.score > it.second.score })
            assertTrue(first.items.all { it.score in 0.0..1.0 })
        }

        @Test
        fun `filters and candidate ids are applied before ranking and stale projections are excluded`() {
            val ws = workspace()
            val excluded = entry(ws, "{\"kind\":\"no\"}")
            val included = entry(ws, "{\"kind\":\"yes\"}")
            projection(ws, excluded, "[1,0,0]")
            projection(ws, included, "[0.9,0.1,0]")
            val filter = PropertyFilter(PropertyName("kind"), PropertyFilterOperator.EQUALS, PropertyValue.StringValue("yes"))
            assertEquals(
                listOf(included),
                search.search(plan(ws, listOf(1.0, 0.0, 0.0), filters = listOf(filter))).items.map { it.entry.id },
            )
            assertEquals(
                listOf(included),
                search.search(plan(ws, listOf(1.0, 0.0, 0.0), candidates = setOf(included))).items.map { it.entry.id },
            )
            jdbc.update("UPDATE skw.entries SET version = 2 WHERE workspace_id = ? AND id = ?", ws.value, included.value)
            jdbc.update("UPDATE skw.entries SET version = 2 WHERE workspace_id = ? AND id = ?", ws.value, excluded.value)
            assertEquals(emptyList<EntryId>(), search.search(plan(ws, listOf(1.0, 0.0, 0.0))).items.map { it.entry.id })
        }
    }
