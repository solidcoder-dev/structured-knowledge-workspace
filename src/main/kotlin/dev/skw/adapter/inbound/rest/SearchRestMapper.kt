package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.adapter.inbound.rest.generated.model.GraphFilter
import dev.skw.adapter.inbound.rest.generated.model.PropertyFilter
import dev.skw.adapter.inbound.rest.generated.model.SearchRequest
import dev.skw.adapter.inbound.rest.generated.model.SearchResponse
import dev.skw.application.search.GraphDirection
import dev.skw.application.search.InvalidPropertyFilter
import dev.skw.application.search.PropertyFilterOperator
import dev.skw.application.search.SearchMode
import dev.skw.application.search.SearchQuery
import dev.skw.domain.entry.EntryId
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId
import java.util.UUID

class SearchRestMapper(
    private val json: PropertyJsonMapper,
    private val objectMapper: ObjectMapper,
    private val entryMapper: EntryRestMapper,
    private val cursorCodec: SearchCursorCodec,
    private val hasher: CanonicalJsonHasher,
) {
    fun toQuery(
        workspaceId: UUID,
        request: SearchRequest,
    ): SearchQuery {
        val requestedMode = request.mode?.let { SearchMode.valueOf(it.value) }
        val mode = requestedMode ?: request.query?.let { SearchMode.HYBRID }
        val filters = request.filters.orEmpty().map(::toFilter)
        val graph =
            request.graph?.let {
                dev.skw.application.search.GraphFilter(
                    EntryId(it.entryId),
                    GraphDirection.valueOf(
                        (
                            it.direction
                                ?: GraphFilter.Direction.BOTH
                        ).value,
                    ),
                    it.relationshipTypes
                        .orEmpty()
                        .map(::RelationshipType)
                        .toSet(),
                    it.maxDepth ?: 1,
                )
            }
        return SearchQuery(
            WorkspaceId(workspaceId),
            request.query,
            mode,
            filters,
            graph,
            request.cursor?.let(cursorCodec::decode),
            fingerprint(workspaceId, request, mode, filters, graph),
            request.limit ?: 25,
        )
    }

    fun toResponse(page: dev.skw.application.search.SearchPage) =
        SearchResponse(
            page.items.map {
                dev.skw.adapter.inbound.rest.generated.model
                    .SearchHit(entryMapper.toRest(it.entry), it.score, null)
            },
            page.nextCursor?.let(cursorCodec::encode),
        )

    private fun toFilter(filter: PropertyFilter) =
        dev.skw.application.search.PropertyFilter(
            try {
                PropertyName(filter.`property`)
            } catch (
                _: IllegalArgumentException,
            ) {
                throw InvalidPropertyFilter("Invalid property name")
            },
            PropertyFilterOperator.valueOf(filter.`operator`.value),
            filter.value?.let {
                try {
                    json.value(it)
                } catch (_: RuntimeException) {
                    throw InvalidPropertyFilter("Invalid filter value")
                }
            },
        )

    private fun fingerprint(
        workspaceId: UUID,
        request: SearchRequest,
        mode: SearchMode?,
        filters: List<dev.skw.application.search.PropertyFilter>,
        graph: dev.skw.application.search.GraphFilter?,
    ): String {
        val root =
            objectMapper.createObjectNode().apply {
                put("workspaceId", workspaceId.toString())
                request.query?.let { put("query", it) }
                mode?.let { put("mode", it.name) }
                set<ArrayNode>(
                    "filters",
                    objectMapper.createArrayNode().apply {
                        filters
                            .sortedWith(
                                compareBy({ it.property.value }, { it.operator.name }, {
                                    it.value?.let { value -> canonicalValue(value) }
                                        ?: ""
                                }),
                            ).forEach { filter ->
                                addObject().apply {
                                    put("property", filter.property.value)
                                    put("operator", filter.operator.name)
                                    filter.value?.let { set<JsonNode>("value", json.json(it)) }
                                }
                            }
                    },
                )
                graph?.let {
                    set<ObjectNode>(
                        "graph",
                        objectMapper.createObjectNode().apply {
                            put("entryId", it.entryId.value.toString())
                            put("direction", it.direction.name)
                            put("maxDepth", it.maxDepth)
                            set<ArrayNode>(
                                "relationshipTypes",
                                objectMapper.createArrayNode().apply {
                                    it.relationshipTypes
                                        .map { type ->
                                            type.value
                                        }.sorted()
                                        .forEach(::add)
                                },
                            )
                        },
                    )
                }
            }
        return hasher.hash(root)
    }

    private fun canonicalValue(value: PropertyValue): String =
        when (value) {
            is PropertyValue.StringValue -> "string:${value.value}"
            is PropertyValue.NumberValue -> "number:${value.value.stripTrailingZeros().toPlainString()}"
            is PropertyValue.BooleanValue -> "boolean:${value.value}"
            is PropertyValue.StringListValue -> "strings:${value.value.joinToString("\u0000")}"
            is PropertyValue.NumberListValue -> "numbers:${value.value.joinToString("\u0000") { it.stripTrailingZeros().toPlainString() }}"
            is PropertyValue.BooleanListValue -> "booleans:${value.value.joinToString("\u0000")}"
            PropertyValue.EmptyListValue -> "empty-list"
        }
}
