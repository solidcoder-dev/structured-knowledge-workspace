package dev.skw.adapter.outbound.persistence

import dev.skw.application.search.GraphCandidateFinder
import dev.skw.application.search.GraphDirection
import dev.skw.domain.entry.EntryId
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcGraphCandidateFinder(
    private val jdbc: NamedParameterJdbcTemplate,
) : GraphCandidateFinder {
    override fun findAdjacent(
        workspaceId: WorkspaceId,
        frontier: Set<EntryId>,
        direction: GraphDirection,
        relationshipTypes: Set<RelationshipType>,
    ): Set<EntryId> {
        if (frontier.isEmpty()) return emptySet()
        val params = MapSqlParameterSource().addValue("workspaceId", workspaceId.value).addValue("frontier", frontier.map { it.value })
        val predicate =
            when (direction) {
                GraphDirection.OUTGOING -> "r.source_entry_id IN (:frontier)"
                GraphDirection.INCOMING -> "r.target_entry_id IN (:frontier)"
                GraphDirection.BOTH -> "(r.source_entry_id IN (:frontier) OR r.target_entry_id IN (:frontier))"
            }
        val neighbor =
            when (direction) {
                GraphDirection.OUTGOING -> "r.target_entry_id"
                GraphDirection.INCOMING -> "r.source_entry_id"
                GraphDirection.BOTH -> "CASE WHEN r.source_entry_id IN (:frontier) THEN r.target_entry_id ELSE r.source_entry_id END"
            }
        val typePredicate =
            if (relationshipTypes.isEmpty()) {
                ""
            } else {
                params.addValue("types", relationshipTypes.map { it.value })
                " AND r.type IN (:types)"
            }
        return jdbc
            .queryForList(
                "SELECT DISTINCT $neighbor AS neighbor_id FROM skw.relationships r WHERE r.workspace_id = :workspaceId AND $predicate$typePredicate",
                params,
            ).map { it["neighbor_id"] as UUID }
            .map(::EntryId)
            .toSet()
    }
}
