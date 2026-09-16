package dev.skw.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.application.semantic.EmbeddingProfile
import dev.skw.application.semantic.EmbeddingVector
import dev.skw.application.semantic.SemanticProjection
import dev.skw.application.semantic.SemanticProjectionStore
import dev.skw.domain.Version
import dev.skw.domain.entry.Entry
import dev.skw.domain.entry.EntryId
import dev.skw.domain.workspace.WorkspaceId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class JdbcSemanticProjectionStore(
    private val jdbc: NamedParameterJdbcTemplate,
    objectMapper: ObjectMapper,
) : SemanticProjectionStore {
    private val json = PropertyJsonMapper(objectMapper)
    private val mapper = objectMapper

    override fun findPending(
        profile: EmbeddingProfile,
        limit: Int,
    ): List<Entry> =
        jdbc.query(
            """SELECT e.* FROM skw.entries e
               LEFT JOIN skw.entry_semantic_embeddings p ON p.workspace_id=e.workspace_id AND p.entry_id=e.id AND p.profile_id=:profileId
               WHERE p.entry_id IS NULL OR p.source_version <> e.version
               ORDER BY e.updated_at ASC, e.id ASC LIMIT :limit""",
            MapSqlParameterSource().addValue("profileId", profile.profileId).addValue("limit", limit),
            ::mapEntry,
        )

    override fun findProjection(
        workspaceId: WorkspaceId,
        entryId: EntryId,
        profile: EmbeddingProfile,
    ): SemanticProjection? =
        jdbc
            .query(
                "SELECT source_version, content_hash, embedding FROM skw.entry_semantic_embeddings WHERE workspace_id=:workspaceId AND entry_id=:entryId AND profile_id=:profileId",
                MapSqlParameterSource()
                    .addValue("workspaceId", workspaceId.value)
                    .addValue("entryId", entryId.value)
                    .addValue("profileId", profile.profileId),
            ) { rs, _ ->
                SemanticProjection(
                    workspaceId,
                    entryId,
                    profile,
                    Version.of(rs.getLong(1)),
                    rs.getString(2),
                    rs.getString(3)?.let(::parseVector)?.requireCompatibleWith(profile),
                )
            }.firstOrNull()

    override fun upsertIfCurrent(projection: SemanticProjection): Boolean {
        val params =
            MapSqlParameterSource()
                .addValue("workspaceId", projection.workspaceId.value)
                .addValue("entryId", projection.entryId.value)
                .addValue("profileId", projection.profile.profileId)
                .addValue("sourceVersion", projection.sourceVersion.value)
                .addValue("contentHash", projection.contentHash)
                .addValue("dimensions", projection.profile.dimensions)
                .addValue("embedding", projection.embedding?.let(::formatVector))
        return jdbc.update(
            """INSERT INTO skw.entry_semantic_embeddings
               (workspace_id, entry_id, profile_id, source_version, content_hash, dimensions, embedding)
               SELECT :workspaceId, :entryId, :profileId, :sourceVersion, :contentHash, :dimensions,
                      CAST(:embedding AS public.vector)
               WHERE EXISTS (SELECT 1 FROM skw.entries WHERE workspace_id=:workspaceId AND id=:entryId AND version=:sourceVersion)
               ON CONFLICT (workspace_id, entry_id, profile_id) DO UPDATE SET
                 source_version=EXCLUDED.source_version, content_hash=EXCLUDED.content_hash,
                 dimensions=EXCLUDED.dimensions, embedding=EXCLUDED.embedding, indexed_at=statement_timestamp()
               WHERE EXISTS (SELECT 1 FROM skw.entries WHERE workspace_id=:workspaceId AND id=:entryId AND version=:sourceVersion)""",
            params,
        ) == 1
    }

    private fun formatVector(vector: EmbeddingVector) = "[${vector.values.joinToString(",")}]"

    private fun parseVector(value: String): EmbeddingVector =
        EmbeddingVector.of(
            value
                .removePrefix("[")
                .removeSuffix("]")
                .split(',')
                .map(String::trim)
                .map(String::toDouble),
        )

    private fun mapEntry(
        rs: ResultSet,
        rowNum: Int,
    ) = Entry.restore(
        EntryId(rs.getObject("id", UUID::class.java)),
        WorkspaceId(rs.getObject("workspace_id", UUID::class.java)),
        json.toDomain(mapper.readTree(rs.getString("properties"))),
        Version.of(rs.getLong("version")),
        rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
        rs.getObject("updated_at", java.time.OffsetDateTime::class.java).toInstant(),
    )
}
