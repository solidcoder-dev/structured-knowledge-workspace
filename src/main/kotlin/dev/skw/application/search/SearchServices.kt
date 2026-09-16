package dev.skw.application.search

import dev.skw.application.port.out.EntryRepository
import dev.skw.application.port.out.KnowledgeSearch
import dev.skw.application.port.out.SearchPlan
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.domain.entry.EntryId
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId

enum class SearchMode { EXACT, TEXT, SEMANTIC, HYBRID }

enum class PropertyFilterOperator { EQUALS, CONTAINS, EXISTS }

data class PropertyFilter(
    val property: String,
    val operator: PropertyFilterOperator,
    val value: PropertyValue? = null,
)

enum class GraphDirection { INCOMING, OUTGOING, BOTH }

data class GraphFilter(
    val entryId: EntryId,
    val direction: GraphDirection = GraphDirection.BOTH,
    val relationshipTypes: Set<RelationshipType> = emptySet(),
    val maxDepth: Int = 1,
)

data class SearchQuery(
    val workspaceId: WorkspaceId,
    val query: String? = null,
    val mode: SearchMode? = null,
    val filters: List<PropertyFilter> = emptyList(),
    val graph: GraphFilter? = null,
    val continuation: SearchCursor? = null,
    val fingerprint: String,
    val limit: Int = 25,
)

data class SearchCursor(
    val version: Int = 1,
    val fingerprint: String,
    val rawRank: Double? = null,
    val entryId: EntryId,
)

data class SearchHit(
    val entry: dev.skw.domain.entry.Entry,
    val score: Double,
    val rawRank: Double? = null,
)

data class SearchPage(
    val items: List<SearchHit>,
    val nextCursor: SearchCursor?,
)

fun interface SearchEntriesUseCase {
    fun search(request: SearchQuery): SearchPage
}

class InvalidSearchRequest(
    message: String,
) : RuntimeException(message)

class InvalidPropertyFilter(
    message: String,
) : RuntimeException(message)

class SearchCapabilityUnavailable : RuntimeException("Requested search capability is unavailable")

class GraphLimitExceeded : RuntimeException("Graph candidate limit exceeded")

class InvalidSearchCursor : RuntimeException("Invalid search cursor")

interface GraphCandidateFinder {
    fun findAdjacent(
        workspaceId: WorkspaceId,
        frontier: Set<EntryId>,
        direction: GraphDirection,
        relationshipTypes: Set<RelationshipType>,
    ): Set<EntryId>
}

class SearchEntriesService(
    private val workspaces: WorkspaceRepository,
    private val entries: EntryRepository,
    private val search: KnowledgeSearch,
    private val graphCandidates: GraphCandidateFinder,
) : SearchEntriesUseCase {
    override fun search(request: SearchQuery): SearchPage {
        validate(request)
        if (workspaces.findById(request.workspaceId) == null) {
            throw dev.skw.application.workspace
                .WorkspaceNotFound(request.workspaceId)
        }
        val effectiveMode = request.mode ?: if (request.query != null) SearchMode.HYBRID else null
        if (effectiveMode == SearchMode.SEMANTIC || effectiveMode == SearchMode.HYBRID) throw SearchCapabilityUnavailable()
        val candidates = request.graph?.let { findCandidates(request.workspaceId, it) }
        if (candidates != null && candidates.isEmpty()) return SearchPage(emptyList(), null)
        val page =
            search.search(
                SearchPlan(
                    request.workspaceId,
                    effectiveMode,
                    request.query,
                    request.filters,
                    candidates,
                    request.continuation,
                    request.limit,
                    request.fingerprint,
                ),
            )
        return page.copy(nextCursor = page.nextCursor?.copy(fingerprint = request.fingerprint))
    }

    private fun validate(request: SearchQuery) {
        if (request.query == null && request.mode != null) throw InvalidSearchRequest("Mode requires query")
        if (request.query != null && request.query.length !in 1..2000) throw InvalidSearchRequest("Query length is invalid")
        if (request.filters.size !in 0..50) throw InvalidSearchRequest("Filter count is invalid")
        if (request.limit !in 1..100) throw InvalidSearchRequest("Limit is invalid")
        if (request.query == null &&
            request.filters.isEmpty() &&
            request.graph == null
        ) {
            throw InvalidSearchRequest("Search requires query, filters or graph")
        }
        request.graph?.let {
            if (it.maxDepth !in 1..5 || it.relationshipTypes.size > 50) throw InvalidSearchRequest("Graph bounds are invalid")
        }
        request.filters.forEach { filter ->
            when (filter.operator) {
                PropertyFilterOperator.EQUALS, PropertyFilterOperator.CONTAINS ->
                    if (filter.value ==
                        null
                    ) {
                        throw InvalidPropertyFilter("${filter.operator} requires value")
                    }
                PropertyFilterOperator.EXISTS -> if (filter.value != null) throw InvalidPropertyFilter("EXISTS forbids value")
            }
            if (filter.operator == PropertyFilterOperator.CONTAINS &&
                filter.value !is PropertyValue.StringValue &&
                filter.value !is PropertyValue.NumberValue &&
                filter.value !is PropertyValue.BooleanValue
            ) {
                throw InvalidPropertyFilter("CONTAINS requires a scalar value")
            }
        }
        request.continuation?.let { if (it.fingerprint != request.fingerprint || it.version != 1) throw InvalidSearchCursor() }
        if (request.continuation != null &&
            request.mode == SearchMode.TEXT &&
            request.continuation.rawRank == null
        ) {
            throw InvalidSearchCursor()
        }
    }

    private fun findCandidates(
        workspaceId: WorkspaceId,
        filter: GraphFilter,
    ): Set<EntryId> {
        if (entries.findById(workspaceId, filter.entryId) == null) {
            throw dev.skw.application.entry
                .EntryNotFound(filter.entryId)
        }
        val visited = linkedSetOf(filter.entryId)
        var frontier = setOf(filter.entryId)
        repeat(filter.maxDepth) {
            if (frontier.isEmpty()) return@repeat
            val next = graphCandidates.findAdjacent(workspaceId, frontier, filter.direction, filter.relationshipTypes) - visited
            visited += next
            if (visited.size - 1 > 10_000) throw GraphLimitExceeded()
            frontier = next
        }
        return visited - filter.entryId
    }
}
