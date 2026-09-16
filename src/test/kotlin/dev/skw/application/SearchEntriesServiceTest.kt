package dev.skw.application

import dev.skw.application.port.out.EntryRepository
import dev.skw.application.port.out.KnowledgeSearch
import dev.skw.application.port.out.SearchPlan
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.application.search.GraphCandidateFinder
import dev.skw.application.search.GraphDirection
import dev.skw.application.search.GraphFilter
import dev.skw.application.search.InvalidPropertyFilter
import dev.skw.application.search.InvalidSearchRequest
import dev.skw.application.search.PropertyFilter
import dev.skw.application.search.PropertyFilterOperator
import dev.skw.application.search.SearchEntriesService
import dev.skw.application.search.SearchMode
import dev.skw.application.search.SearchPage
import dev.skw.application.search.SearchQuery
import dev.skw.domain.entry.EntryId
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class SearchEntriesServiceTest {
    private val workspaceId = WorkspaceId(UUID.randomUUID())
    private val entries = mock(EntryRepository::class.java)
    private val workspaces = mock(WorkspaceRepository::class.java)
    private val search = RecordingSearch()
    private val graph = mock(GraphCandidateFinder::class.java)
    private val service = SearchEntriesService(workspaces, entries, search, graph)

    init {
        `when`(workspaces.findById(workspaceId)).thenReturn(mock())
    }

    @Test
    fun `empty request is rejected`() {
        assertThrows(InvalidSearchRequest::class.java) { service.search(SearchQuery(workspaceId, fingerprint = "fp")) }
    }

    @Test
    fun `omitted mode with query is unavailable`() {
        assertThrows(dev.skw.application.search.SearchCapabilityUnavailable::class.java) {
            service.search(SearchQuery(workspaceId, query = "text", fingerprint = "fp"))
        }
    }

    @Test
    fun `semantic and hybrid are unavailable while exact and filters are supported`() {
        assertThrows(dev.skw.application.search.SearchCapabilityUnavailable::class.java) {
            service.search(SearchQuery(workspaceId, "text", SearchMode.SEMANTIC, fingerprint = "fp"))
        }
        service.search(
            SearchQuery(workspaceId, filters = listOf(PropertyFilter("kind", PropertyFilterOperator.EXISTS)), fingerprint = "fp"),
        )
        service.search(SearchQuery(workspaceId, "text", SearchMode.EXACT, fingerprint = "fp"))
    }

    @Test
    fun `invalid filter combinations are rejected before persistence`() {
        assertThrows(InvalidPropertyFilter::class.java) {
            service.search(
                SearchQuery(
                    workspaceId,
                    filters = listOf(PropertyFilter("tags", PropertyFilterOperator.CONTAINS, PropertyValue.StringListValue(listOf("a")))),
                    fingerprint = "fp",
                ),
            )
        }
        assertEquals(null, search.lastPlan)
    }

    @Test
    fun `graph candidates are passed to search as an intersection`() {
        val start = EntryId(UUID.randomUUID())
        val neighbor = EntryId(UUID.randomUUID())
        `when`(entries.findById(workspaceId, start)).thenReturn(mock())
        `when`(
            graph.findAdjacent(workspaceId, setOf(start), GraphDirection.OUTGOING, setOf(RelationshipType("supports"))),
        ).thenReturn(setOf(neighbor))
        service.search(
            SearchQuery(
                workspaceId,
                filters = listOf(PropertyFilter("kind", PropertyFilterOperator.EXISTS)),
                graph = GraphFilter(start, GraphDirection.OUTGOING, setOf(RelationshipType("supports"))),
                fingerprint = "fp",
            ),
        )
        assertEquals(setOf(neighbor), search.lastPlan?.candidateIds)
    }

    private class RecordingSearch : KnowledgeSearch {
        var lastPlan: SearchPlan? = null

        override fun search(plan: SearchPlan): SearchPage {
            lastPlan = plan
            return SearchPage(emptyList(), null)
        }
    }
}
