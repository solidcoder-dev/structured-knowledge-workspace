package dev.skw.adapter.outbound.persistence

import dev.skw.application.semantic.EmbeddingProfile
import dev.skw.application.semantic.EmbeddingProvider
import dev.skw.application.semantic.EmbeddingVector
import dev.skw.application.semantic.RefreshSemanticIndexService
import dev.skw.application.semantic.SemanticProjection
import dev.skw.domain.Version
import dev.skw.domain.entry.EntryId
import dev.skw.domain.workspace.WorkspaceId
import dev.skw.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class JdbcSemanticProjectionStoreContractTest
    @Autowired
    constructor(
        private val jdbc: JdbcTemplate,
        private val store: JdbcSemanticProjectionStore,
    ) : PostgresIntegrationTest() {
        private val profile = EmbeddingProfile("test/projection-v1", 2)

        private fun workspace() =
            WorkspaceId(jdbc.queryForObject("INSERT INTO skw.workspaces DEFAULT VALUES RETURNING id", UUID::class.java)!!)

        private fun entry(
            workspace: WorkspaceId,
            text: String,
        ): EntryId =
            EntryId(
                jdbc.queryForObject(
                    "INSERT INTO skw.entries (workspace_id, properties) VALUES (?, jsonb_build_object('text', ?)) RETURNING id",
                    UUID::class.java,
                    workspace.value,
                    text,
                )!!,
            )

        @Test
        fun `refresh indexes missing entries reuses same hash and detects version changes`() {
            val ws = workspace()
            val id = entry(ws, "hello")
            val calls = mutableListOf<List<String>>()
            val provider =
                object : EmbeddingProvider {
                    override fun profile() = profile

                    override fun embed(documents: List<String>): List<EmbeddingVector> {
                        calls += documents
                        return documents.map { EmbeddingVector.of(listOf(1.0, 0.0)) }
                    }
                }
            val refresh = RefreshSemanticIndexService(provider, store)
            assertTrue(store.findPending(profile, 100).any { it.id == id })
            assertTrue(refresh.refresh(100).indexed >= 1)
            assertEquals(1, calls.size)
            assertTrue(store.findPending(profile, 10).isEmpty())

            jdbc.update("UPDATE skw.entries SET version = 2 WHERE workspace_id = ? AND id = ?", ws.value, id.value)
            assertTrue(refresh.refresh(100).indexed >= 1)
            assertEquals(1, calls.size)
            assertEquals(
                2L,
                jdbc.queryForObject(
                    "SELECT source_version FROM skw.entry_semantic_embeddings WHERE workspace_id = ? AND entry_id = ?",
                    Long::class.java,
                    ws.value,
                    id.value,
                ),
            )
        }

        @Test
        fun `conditional upsert rejects stale work and deletion cascades`() {
            val ws = workspace()
            val id = entry(ws, "hello")
            val projection = SemanticProjection(ws, id, profile, Version.initial(), "a".repeat(64), EmbeddingVector.of(listOf(1.0, 0.0)))
            jdbc.update("UPDATE skw.entries SET version = 2 WHERE workspace_id = ? AND id = ?", ws.value, id.value)
            assertFalse(store.upsertIfCurrent(projection))
            assertEquals(
                0,
                jdbc.queryForObject(
                    "SELECT count(*) FROM skw.entry_semantic_embeddings WHERE workspace_id = ? AND entry_id = ?",
                    Int::class.java,
                    ws.value,
                    id.value,
                ),
            )
            jdbc.update(
                "INSERT INTO skw.entry_semantic_embeddings (workspace_id, entry_id, profile_id, source_version, content_hash, dimensions, embedding) VALUES (?, ?, ?, 2, ?, 2, '[1,0]'::public.vector)",
                ws.value,
                id.value,
                profile.profileId,
                "b".repeat(64),
            )
            jdbc.update("DELETE FROM skw.entries WHERE workspace_id = ? AND id = ?", ws.value, id.value)
            assertEquals(
                0,
                jdbc.queryForObject(
                    "SELECT count(*) FROM skw.entry_semantic_embeddings WHERE workspace_id = ? AND entry_id = ?",
                    Int::class.java,
                    ws.value,
                    id.value,
                ),
            )
        }
    }
