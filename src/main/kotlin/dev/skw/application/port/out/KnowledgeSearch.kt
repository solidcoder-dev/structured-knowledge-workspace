package dev.skw.application.port.out

import dev.skw.application.search.GraphDirection
import dev.skw.application.search.PropertyFilter
import dev.skw.application.search.SearchCursor
import dev.skw.application.search.SearchMode
import dev.skw.application.search.SearchPage
import dev.skw.domain.entry.EntryId
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId

data class SearchPlan(
    val workspaceId: WorkspaceId,
    val mode: SearchMode?,
    val query: String?,
    val filters: List<PropertyFilter>,
    val candidateIds: Set<EntryId>?,
    val continuation: SearchCursor?,
    val limit: Int,
    val fingerprint: String,
)

interface GraphCandidateFinder {
    fun findAdjacent(
        workspaceId: WorkspaceId,
        frontier: Set<EntryId>,
        direction: GraphDirection,
        relationshipTypes: Set<RelationshipType>,
    ): Set<EntryId>
}

interface KnowledgeSearch {
    fun search(plan: SearchPlan): SearchPage
}
