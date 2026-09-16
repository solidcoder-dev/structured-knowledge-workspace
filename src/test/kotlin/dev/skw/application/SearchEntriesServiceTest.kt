package dev.skw.application

import dev.skw.application.port.out.EntryRepository
import dev.skw.application.port.out.GraphCandidateFinder
import dev.skw.application.port.out.HybridKnowledgeSearch
import dev.skw.application.port.out.HybridSearchPlan
import dev.skw.application.port.out.KnowledgeSearch
import dev.skw.application.port.out.SearchPlan
import dev.skw.application.port.out.WorkspaceRepository
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
import dev.skw.application.semantic.EmbeddingProfile
import dev.skw.application.semantic.EmbeddingProvider
import dev.skw.application.semantic.EmbeddingVector
import dev.skw.domain.entry.EntryId
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
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
    fun `omitted mode with query uses hybrid`() {
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
            SearchQuery(
                workspaceId,
                filters = listOf(PropertyFilter(PropertyName("kind"), PropertyFilterOperator.EXISTS)),
                fingerprint = "fp",
            ),
        )
        service.search(SearchQuery(workspaceId, "text", SearchMode.EXACT, fingerprint = "fp"))
    }

    @Test
    fun `explicit and omitted hybrid embed once and delegate`() {
        val provider = RecordingProvider()
        val hybrid = RecordingHybrid()
        val hybridService = SearchEntriesService(workspaces, entries, search, graph, embeddingProvider = provider, hybridSearch = hybrid)
        hybridService.search(SearchQuery(workspaceId, query = "text", mode = SearchMode.HYBRID, fingerprint = "fp"))
        hybridService.search(SearchQuery(workspaceId, query = "text", fingerprint = "fp"))
        assertEquals(2, provider.calls)
        assertEquals(2, hybrid.calls)
        assertEquals("text", hybrid.lastPlan?.query)
    }

    @Test
    fun `invalid filter combinations are rejected before persistence`() {
        assertThrows(InvalidPropertyFilter::class.java) {
            service.search(
                SearchQuery(
                    workspaceId,
                    filters =
                        listOf(
                            PropertyFilter(
                                PropertyName("tags"),
                                PropertyFilterOperator.CONTAINS,
                                PropertyValue.StringListValue(listOf("a")),
                            ),
                        ),
                    fingerprint = "fp",
                ),
            )
        }
        assertEquals(
            null,
            search.lastPlan,
        )
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
                filters = listOf(PropertyFilter(PropertyName("kind"), PropertyFilterOperator.EXISTS)),
                graph = GraphFilter(start, GraphDirection.OUTGOING, setOf(RelationshipType("supports"))),
                fingerprint = "fp",
            ),
        )
        assertEquals(setOf(neighbor), search.lastPlan?.candidateIds)
    }

    @Test
    fun `graph traversal is bounded BFS with cycle and self link de duplication`() {
        val start = EntryId(UUID.randomUUID())
        val second = EntryId(UUID.randomUUID())
        val third = EntryId(UUID.randomUUID())
        `when`(entries.findById(workspaceId, start)).thenReturn(mock())
        `when`(graph.findAdjacent(workspaceId, setOf(start), GraphDirection.BOTH, emptySet())).thenReturn(setOf(start, second))
        `when`(graph.findAdjacent(workspaceId, setOf(second), GraphDirection.BOTH, emptySet())).thenReturn(setOf(start, second, third))
        service.search(
            SearchQuery(
                workspaceId,
                filters = listOf(PropertyFilter(PropertyName("kind"), PropertyFilterOperator.EXISTS)),
                graph = GraphFilter(start, maxDepth = 2),
                fingerprint = "fp",
            ),
        )
        assertEquals(setOf(second, third), search.lastPlan?.candidateIds)
        verify(graph).findAdjacent(workspaceId, setOf(start), GraphDirection.BOTH, emptySet())
        verify(graph).findAdjacent(workspaceId, setOf(second), GraphDirection.BOTH, emptySet())
    }

    @Test
    fun `empty graph candidates skip knowledge search`() {
        val start = EntryId(UUID.randomUUID())
        `when`(entries.findById(workspaceId, start)).thenReturn(mock())
        `when`(graph.findAdjacent(workspaceId, setOf(start), GraphDirection.OUTGOING, emptySet())).thenReturn(emptySet())
        service.search(SearchQuery(workspaceId, graph = GraphFilter(start, GraphDirection.OUTGOING), fingerprint = "fp"))
        assertEquals(0, search.calls)
    }

    @Test
    fun `graph limit is enforced before knowledge search`() {
        val start = EntryId(UUID.randomUUID())
        `when`(entries.findById(workspaceId, start)).thenReturn(mock())
        val many = (1..10_001).map { EntryId(UUID.randomUUID()) }.toSet()
        `when`(graph.findAdjacent(workspaceId, setOf(start), GraphDirection.BOTH, emptySet())).thenReturn(many)
        assertThrows(dev.skw.application.search.GraphLimitExceeded::class.java) {
            service.search(SearchQuery(workspaceId, graph = GraphFilter(start), fingerprint = "fp"))
        }
        assertEquals(null, search.lastPlan)
    }

    private class RecordingSearch : KnowledgeSearch {
        var lastPlan: SearchPlan? = null
        var calls = 0

        override fun search(plan: SearchPlan): SearchPage {
            calls++
            lastPlan = plan
            return SearchPage(emptyList(), null)
        }
    }

    private class RecordingProvider : EmbeddingProvider {
        var calls = 0
        private val active = EmbeddingProfile("test/profile", 2)

        override fun profile() = active

        override fun embed(documents: List<String>): List<EmbeddingVector> {
            calls++
            return documents.map { EmbeddingVector.of(listOf(1.0, 0.0)) }
        }
    }

    private class RecordingHybrid : HybridKnowledgeSearch {
        var calls = 0
        var lastPlan: HybridSearchPlan? = null

        override fun search(plan: HybridSearchPlan): SearchPage {
            calls++
            lastPlan = plan
            return SearchPage(emptyList(), null)
        }
    }
}
