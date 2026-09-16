package dev.skw.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.application.port.out.HybridKnowledgeSearch
import dev.skw.application.port.out.HybridSearchPlan
import dev.skw.application.search.SearchCursor
import dev.skw.application.search.SearchHit
import dev.skw.application.search.SearchPage
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
class JdbcHybridKnowledgeSearch(
    private val jdbc: NamedParameterJdbcTemplate,
    objectMapper: ObjectMapper,
) : HybridKnowledgeSearch {
    private val json = PropertyJsonMapper(objectMapper)
    private val mapper = objectMapper
    private val propertyFilters = EntryPropertyFilterSql(objectMapper, json)

    override fun search(plan: HybridSearchPlan): SearchPage {
        val params =
            MapSqlParameterSource()
                .addValue("workspaceId", plan.workspaceId.value)
                .addValue("query", plan.query)
                .addValue("profileId", plan.profile.profileId)
                .addValue("dimensions", plan.profile.dimensions)
                .addValue("queryVector", "[${plan.queryVector.values.joinToString(",")}]")
                .addValue("rrfK", plan.policy.rrfK)
                .addValue("limit", plan.limit + 1)
        val eligibleConditions = mutableListOf("e.workspace_id = :workspaceId")
        plan.candidateIds?.let {
            if (it.isEmpty()) return SearchPage(emptyList(), null)
            params.addValue("candidateIds", it.map(EntryId::value))
            eligibleConditions += "e.id IN (:candidateIds)"
        }
        propertyFilters.append(plan.filters, "e", params, eligibleConditions)
        val cursorCondition =
            plan.continuation?.let {
                params.addValue("afterScore", it.sortValue)
                params.addValue("afterId", it.entryId.value)
                "(fused.raw_score < CAST(:afterScore AS double precision) OR " +
                    "(fused.raw_score = CAST(:afterScore AS double precision) AND fused.id > :afterId))"
            }
        val rows =
            jdbc.query(
                """WITH eligible AS (
                   SELECT e.* FROM skw.entries e
                   WHERE ${eligibleConditions.joinToString(" AND ")}
               ), text_ranked AS (
                   SELECT id,
                          ROW_NUMBER() OVER (ORDER BY ts_rank_cd(search_vector, websearch_to_tsquery('simple', :query)) DESC, id ASC) AS text_position
                   FROM eligible
                   WHERE search_vector @@ websearch_to_tsquery('simple', :query)
               ), semantic_ranked AS (
                   SELECT e.id,
                          ROW_NUMBER() OVER (ORDER BY p.embedding <=> CAST(:queryVector AS public.vector) ASC, e.id ASC) AS semantic_position
                   FROM eligible e
                   JOIN skw.entry_semantic_embeddings p
                     ON p.workspace_id = e.workspace_id AND p.entry_id = e.id
                   WHERE p.profile_id = :profileId
                     AND p.source_version = e.version
                     AND p.dimensions = :dimensions
                     AND p.embedding IS NOT NULL
               ), fused AS (
                   SELECT COALESCE(t.id, s.id) AS id,
                          COALESCE(1.0 / (:rrfK + t.text_position), 0.0) +
                          COALESCE(1.0 / (:rrfK + s.semantic_position), 0.0) AS raw_score
                   FROM text_ranked t
                   FULL OUTER JOIN semantic_ranked s ON s.id = t.id
               )
               SELECT e.*, fused.raw_score
               FROM fused JOIN eligible e ON e.id = fused.id
               ${cursorCondition?.let { "WHERE $it" } ?: ""}
               ORDER BY fused.raw_score DESC, fused.id ASC
               LIMIT :limit""",
                params,
                ::mapRow,
            )
        val maxRrf = 2.0 / (plan.policy.rrfK + 1.0)
        val items =
            rows.take(plan.limit).map {
                SearchHit(it.entry, (it.rawScore / maxRrf).coerceIn(0.0, 1.0), it.rawScore)
            }
        val next =
            if (rows.size > plan.limit) {
                items.lastOrNull()?.let { SearchCursor(fingerprint = plan.fingerprint, sortValue = it.sortValue, entryId = it.entry.id) }
            } else {
                null
            }
        return SearchPage(items, next)
    }

    private data class Row(
        val entry: Entry,
        val rawScore: Double,
    )

    private fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ) = Row(
        Entry.restore(
            EntryId(rs.getObject("id", UUID::class.java)),
            WorkspaceId(rs.getObject("workspace_id", UUID::class.java)),
            json.toDomain(mapper.readTree(rs.getString("properties"))),
            Version.of(rs.getLong("version")),
            rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
            rs.getObject("updated_at", java.time.OffsetDateTime::class.java).toInstant(),
        ),
        rs.getDouble("raw_score"),
    )
}
