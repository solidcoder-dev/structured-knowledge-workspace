package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.adapter.inbound.rest.generated.model.GraphFilter
import dev.skw.adapter.inbound.rest.generated.model.SearchRequest
import dev.skw.application.search.SearchMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class SearchRestMapperTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val mapper =
        SearchRestMapper(
            PropertyJsonMapper(objectMapper),
            objectMapper,
            EntryRestMapper(PropertyJsonMapper(objectMapper), ResourceEtag()),
            SearchCursorCodec(),
            CanonicalJsonHasher(objectMapper),
        )

    @Test
    fun `mapper applies documented defaults and normalizes omitted query mode`() {
        val query = mapper.toQuery(UUID.randomUUID(), SearchRequest(query = "alpha"))
        assertEquals(SearchMode.HYBRID, query.mode)
        assertEquals(25, query.limit)
    }

    @Test
    fun `fingerprint canonicalizes filter and graph set order`() {
        val workspace = UUID.randomUUID()
        val entry = UUID.randomUUID()
        val first =
            SearchRequest(
                query = "alpha",
                filters =
                    listOf(
                        dev.skw.adapter.inbound.rest.generated.model.PropertyFilter(
                            "b",
                            dev.skw.adapter.inbound.rest.generated.model.PropertyFilter.Operator.EXISTS,
                        ),
                        dev.skw.adapter.inbound.rest.generated.model.PropertyFilter(
                            "a",
                            dev.skw.adapter.inbound.rest.generated.model.PropertyFilter.Operator.EXISTS,
                        ),
                    ),
                graph = GraphFilter(entry, relationshipTypes = setOf("supports", "depends")),
            )
        val second =
            first.copy(
                filters = first.filters?.reversed(),
                graph = GraphFilter(entry, relationshipTypes = setOf("depends", "supports")),
            )
        assertEquals(mapper.toQuery(workspace, first).fingerprint, mapper.toQuery(workspace, second).fingerprint)
        assertNotEquals(mapper.toQuery(workspace, first).fingerprint, mapper.toQuery(UUID.randomUUID(), first).fingerprint)
    }
}
