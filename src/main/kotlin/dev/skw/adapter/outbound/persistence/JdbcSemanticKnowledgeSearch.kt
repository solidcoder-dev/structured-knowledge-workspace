package dev.skw.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.application.search.SearchCursor
import dev.skw.application.search.SearchHit
import dev.skw.application.search.SearchPage
import dev.skw.application.semantic.SemanticKnowledgeSearch
import dev.skw.application.semantic.SemanticSearchPlan
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
class JdbcSemanticKnowledgeSearch(
    private val jdbc: NamedParameterJdbcTemplate,
    objectMapper: ObjectMapper,
) : SemanticKnowledgeSearch {
    private val json = PropertyJsonMapper(objectMapper)
    private val mapper = objectMapper
    private val propertyFilters = EntryPropertyFilterSql(objectMapper, json)

    override fun search(plan: SemanticSearchPlan): SearchPage {
        val params =
            MapSqlParameterSource()
                .addValue("workspaceId", plan.workspaceId.value)
                .addValue("profileId", plan.profile.profileId)
                .addValue("dimensions", plan.profile.dimensions)
                .addValue("queryVector", "[${plan.queryVector.values.joinToString(",")}]")
                .addValue("limit", plan.limit + 1)
        val conditions =
            mutableListOf(
                "e.workspace_id=:workspaceId",
                "p.profile_id=:profileId",
                "p.source_version=e.version",
                "p.dimensions=:dimensions",
                "p.embedding IS NOT NULL",
            )
        plan.candidateIds?.let { ids ->
            if (ids.isEmpty()) return SearchPage(emptyList(), null)
            params.addValue("candidateIds", ids.map(EntryId::value))
            conditions += "e.id IN (:candidateIds)"
        }
        propertyFilters.append(plan.filters, "e", params, conditions)
        plan.continuation?.let { after ->
            params.addValue("afterDistance", after.sortValue).addValue("afterId", after.entryId.value)
            conditions +=
                "(p.embedding <=> CAST(:queryVector AS public.vector) > :afterDistance OR (p.embedding <=> CAST(:queryVector AS public.vector) = :afterDistance AND e.id > :afterId))"
        }
        val rows =
            jdbc.query(
                """SELECT e.*, p.embedding <=> CAST(:queryVector AS public.vector) AS distance
               FROM skw.entries e JOIN skw.entry_semantic_embeddings p
                 ON p.workspace_id=e.workspace_id AND p.entry_id=e.id
               WHERE ${conditions.joinToString(" AND ")}
               ORDER BY distance ASC, e.id ASC LIMIT :limit""",
                params,
                ::mapRow,
            )
        val items = rows.take(plan.limit).map { SearchHit(it.entry, (1.0 - it.distance / 2.0).coerceIn(0.0, 1.0), it.distance) }
        val next =
            if (rows.size >
                plan.limit
            ) {
                items.lastOrNull()?.let { SearchCursor(fingerprint = plan.fingerprint, sortValue = it.rawRank, entryId = it.entry.id) }
            } else {
                null
            }
        return SearchPage(items, next)
    }

    private data class Row(
        val entry: Entry,
        val distance: Double,
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
        rs.getDouble("distance"),
    )
}
