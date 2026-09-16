package dev.skw.adapter.outbound.persistence

import dev.skw.application.port.out.HybridRankingPolicy
import dev.skw.application.port.out.HybridSearchPlan
import dev.skw.application.search.SearchCursor
import dev.skw.application.semantic.EmbeddingProfile
import dev.skw.application.semantic.EmbeddingVector
import dev.skw.domain.entry.EntryId
import dev.skw.domain.workspace.WorkspaceId
import dev.skw.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class JdbcHybridKnowledgeSearchContractTest
    @Autowired
    constructor(
        private val jdbc: JdbcTemplate,
        private val search: JdbcHybridKnowledgeSearch,
    ) : PostgresIntegrationTest() {
        private val profile = EmbeddingProfile("test/hybrid-v1", 3)
        private val policy = HybridRankingPolicy()

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
                "b".repeat(64),
                profile.dimensions,
                vector,
            )
        }

        private fun plan(
            workspace: WorkspaceId,
            query: String = "alpha",
            limit: Int = 25,
            after: SearchCursor? = null,
        ) = HybridSearchPlan(
            workspace,
            query,
            EmbeddingVector.of(listOf(1.0, 0.0, 0.0)),
            profile,
            emptyList(),
            null,
            after,
            limit,
            "fp",
            policy,
        )

        @Test
        fun `rrf includes lexical only semantic only and both once`() {
            val ws = workspace()
            val a = entry(ws, "{\"text\":\"alpha\"}")
            val b = entry(ws, "{\"text\":\"beta\"}")
            val c = entry(ws, "{\"text\":\"alpha alpha alpha\"}")
            projection(ws, b, "[1,0,0]")
            projection(ws, c, "[0.9,0.1,0]")
            val result = search.search(plan(ws))
            assertEquals(listOf(c, b, a), result.items.map { it.entry.id })
            assertEquals(3, result.items.distinctBy { it.entry.id }.size)
            assertTrue(result.items.zipWithNext().all { it.first.score > it.second.score })
            assertTrue(result.items.all { it.score in 0.0..1.0 })
        }

        @Test
        fun `filters and keyset pagination are applied to the fused ranking`() {
            val ws = workspace()
            val ids = (1..5).map { entry(ws, "{\"text\":\"alpha\",\"kind\":\"yes\"}") }
            ids.forEachIndexed { index, id -> projection(ws, id, if (index % 2 == 0) "[1,0,0]" else "[0,1,0]") }
            val first = search.search(plan(ws, limit = 2))
            val second = search.search(plan(ws, limit = 2, after = first.nextCursor))
            assertTrue(
                first.items
                    .map { it.entry.id }
                    .intersect(second.items.map { it.entry.id })
                    .isEmpty(),
            )
            assertEquals(2, first.items.size)
            assertEquals(2, second.items.size)
        }

        @Test
        fun `fresh text survives missing or stale semantic projection`() {
            val ws = workspace()
            val id = entry(ws, "{\"text\":\"beta\"}")
            projection(ws, id, "[1,0,0]")
            jdbc.update("UPDATE skw.entries SET properties = ?::jsonb, version = 2 WHERE id = ?", "{\"text\":\"alpha\"}", id.value)
            assertEquals(listOf(id), search.search(plan(ws)).items.map { it.entry.id })
        }
    }
