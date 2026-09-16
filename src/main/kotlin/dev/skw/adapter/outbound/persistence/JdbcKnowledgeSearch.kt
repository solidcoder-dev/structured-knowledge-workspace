package dev.skw.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.application.port.out.KnowledgeSearch
import dev.skw.application.port.out.SearchPlan
import dev.skw.application.search.SearchCursor
import dev.skw.application.search.SearchHit
import dev.skw.application.search.SearchMode
import dev.skw.application.search.SearchPage
import dev.skw.domain.Version
import dev.skw.domain.entry.Entry
import dev.skw.domain.entry.EntryId
import dev.skw.domain.workspace.WorkspaceId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class JdbcKnowledgeSearch(
    private val jdbc: NamedParameterJdbcTemplate,
    objectMapper: ObjectMapper,
) : KnowledgeSearch {
    private val json = PropertyJsonMapper(objectMapper)
    private val mapper = objectMapper
    private val propertyFilters = EntryPropertyFilterSql(objectMapper, json)

    override fun search(plan: SearchPlan): SearchPage {
        val params = MapSqlParameterSource().addValue("workspaceId", plan.workspaceId.value).addValue("limit", plan.limit + 1)
        val conditions = mutableListOf("e.workspace_id = :workspaceId")
        plan.candidateIds?.let {
            if (it.isEmpty()) return SearchPage(emptyList(), null)
            params.addValue("candidateIds", it.map { id -> id.value })
            conditions += "e.id IN (:candidateIds)"
        }
        propertyFilters.append(plan.filters, "e", params, conditions)
        val rankExpression = "ts_rank_cd(e.search_vector, websearch_to_tsquery('simple', :query))"
        if (plan.query != null) {
            params.addValue("query", plan.query)
            when (plan.mode) {
                SearchMode.EXACT -> {
                    params.addValue("exactQuery", plan.query)
                    conditions +=
                        "EXISTS (SELECT 1 FROM jsonb_each(e.properties) p WHERE jsonb_typeof(p.value) = 'string' AND p.value #>> '{}' = :exactQuery)"
                }
                SearchMode.TEXT -> conditions += "e.search_vector @@ websearch_to_tsquery('simple', :query)"
                else -> error("Unsupported search mode")
            }
        }
        val text = plan.mode == SearchMode.TEXT && plan.query != null
        plan.continuation?.let { after ->
            if (text) {
                params.addValue("afterRank", after.sortValue)
                params.addValue("afterId", after.entryId.value)
                conditions +=
                    "($rankExpression < CAST(:afterRank AS real) OR ($rankExpression = CAST(:afterRank AS real) AND e.id > :afterId))"
            } else {
                params.addValue("afterId", after.entryId.value)
                conditions += "e.id > :afterId"
            }
        }
        val rank = if (text) rankExpression else "1.0"
        val order = if (text) "raw_rank DESC, e.id ASC" else "e.id ASC"
        val rows =
            jdbc.query(
                "SELECT e.*, $rank AS raw_rank FROM skw.entries e WHERE ${conditions.joinToString(" AND ")} ORDER BY $order LIMIT :limit",
                params,
                ::mapRow,
            )
        val items =
            rows.take(plan.limit).map { row ->
                SearchHit(row.entry, if (text) row.sortValue / (1 + row.sortValue) else 1.0, if (text) row.sortValue else null)
            }
        val next =
            if (rows.size >
                plan.limit
            ) {
                items.lastOrNull()?.let { SearchCursor(fingerprint = plan.fingerprint, sortValue = it.sortValue, entryId = it.entry.id) }
            } else {
                null
            }
        return SearchPage(items, next)
    }

    private data class Row(
        val entry: Entry,
        val sortValue: Double,
    )

    private fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): Row =
        Row(
            Entry.restore(
                EntryId(rs.getObject("id", java.util.UUID::class.java)),
                WorkspaceId(rs.getObject("workspace_id", java.util.UUID::class.java)),
                json.toDomain(mapper.readTree(rs.getString("properties"))),
                Version.of(rs.getLong("version")),
                rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
                rs.getObject("updated_at", java.time.OffsetDateTime::class.java).toInstant(),
            ),
            rs.getDouble("raw_rank"),
        )
}
