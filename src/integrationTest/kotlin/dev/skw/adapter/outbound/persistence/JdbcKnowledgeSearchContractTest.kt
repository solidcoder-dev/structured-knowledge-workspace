package dev.skw.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.application.port.out.SearchPlan
import dev.skw.application.search.PropertyFilter
import dev.skw.application.search.PropertyFilterOperator
import dev.skw.application.search.SearchMode
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.workspace.WorkspaceId
import dev.skw.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class JdbcKnowledgeSearchContractTest
    @Autowired
    constructor(
        private val jdbc: JdbcTemplate,
        private val objectMapper: ObjectMapper,
        private val search: JdbcKnowledgeSearch,
    ) : PostgresIntegrationTest() {
        private val json by lazy { PropertyJsonMapper(objectMapper) }

        private fun workspace(): WorkspaceId =
            WorkspaceId(
                jdbc.queryForObject("INSERT INTO skw.workspaces DEFAULT VALUES RETURNING id", UUID::class.java)!!,
            )

        private fun entry(
            workspace: WorkspaceId,
            properties: String,
        ): UUID =
            jdbc.queryForObject(
                "INSERT INTO skw.entries (workspace_id, properties) VALUES (?, ?::jsonb) RETURNING id",
                UUID::class.java,
                workspace.value,
                properties,
            )!!

        private fun plan(
            workspace: WorkspaceId,
            mode: SearchMode?,
            query: String? = null,
            filters: List<PropertyFilter> = emptyList(),
            limit: Int = 25,
        ) = SearchPlan(workspace, mode, query, filters, null, null, limit, "test")

        private fun count(
            workspace: WorkspaceId,
            filter: PropertyFilter,
        ) = search.search(plan(workspace, null, filters = listOf(filter))).items.size

        @Test
        fun `filters are typed exact ordered and ANDed`() {
            val ws = workspace()
            entry(ws, "{\"name\":\"Clean Architecture\",\"n\":1.00,\"ok\":true,\"tags\":[\"a\",\"b\"]}")
            entry(ws, "{\"name\":\"Other\",\"n\":1,\"ok\":true,\"tags\":[\"b\",\"a\"]}")
            val filters =
                listOf(
                    PropertyFilter(PropertyName("name"), PropertyFilterOperator.EQUALS, PropertyValue.StringValue("Clean Architecture")),
                    PropertyFilter(PropertyName("n"), PropertyFilterOperator.EQUALS, PropertyValue.NumberValue("1".toBigDecimal())),
                    PropertyFilter(PropertyName("ok"), PropertyFilterOperator.EQUALS, PropertyValue.BooleanValue(true)),
                    PropertyFilter(PropertyName("tags"), PropertyFilterOperator.EQUALS, PropertyValue.StringListValue(listOf("a", "b"))),
                )
            assertEquals(1, search.search(plan(ws, null, filters = filters)).items.size)
            val reordered =
                filters.dropLast(1) +
                    PropertyFilter(
                        PropertyName("tags"),
                        PropertyFilterOperator.EQUALS,
                        PropertyValue.StringListValue(listOf("b", "a")),
                    )
            assertEquals(0, search.search(plan(ws, null, filters = reordered)).items.size)
            val missing =
                PropertyFilter(
                    PropertyName("missing"),
                    PropertyFilterOperator.EQUALS,
                    PropertyValue.StringValue("x"),
                )
            assertEquals(0, count(ws, missing))
        }

        @Test
        fun `contains is exact scalar membership and exists ignores value`() {
            val ws = workspace()
            entry(
                ws,
                """{"tags":["ddd","kotlin"],""" +
                    "\"numbers\":[7]," +
                    """"flags":[true]}""",
            )
            val ddd = PropertyFilter(PropertyName("tags"), PropertyFilterOperator.CONTAINS, PropertyValue.StringValue("ddd"))
            val dd = PropertyFilter(PropertyName("tags"), PropertyFilterOperator.CONTAINS, PropertyValue.StringValue("dd"))
            val seven =
                PropertyFilter(
                    PropertyName("numbers"),
                    PropertyFilterOperator.CONTAINS,
                    PropertyValue.NumberValue("7".toBigDecimal()),
                )
            val flag = PropertyFilter(PropertyName("flags"), PropertyFilterOperator.CONTAINS, PropertyValue.BooleanValue(true))
            assertEquals(1, count(ws, ddd))
            assertEquals(0, count(ws, dd))
            assertEquals(1, count(ws, seven))
            assertEquals(1, count(ws, flag))
            assertEquals(1, count(ws, PropertyFilter(PropertyName("tags"), PropertyFilterOperator.EXISTS)))
        }

        @Test
        fun `exact searches only complete scalar strings and is case sensitive`() {
            val ws = workspace()
            entry(ws, "{\"name\":\"Clean Architecture\",\"tags\":[\"architecture\"],\"architecture\":\"other\"}")
            assertEquals(1, search.search(plan(ws, SearchMode.EXACT, "Clean Architecture")).items.size)
            assertEquals(0, search.search(plan(ws, SearchMode.EXACT, "Architecture")).items.size)
            assertEquals(0, search.search(plan(ws, SearchMode.EXACT, "architecture")).items.size)
        }

        @Test
        fun `text searches strings arrays update immediately and exclude non strings`() {
            val ws = workspace()
            val id = entry(ws, "{\"text\":\"alpha\",\"tags\":[\"kotlin\"],\"n\":123,\"flag\":true}")
            assertEquals(1, search.search(plan(ws, SearchMode.TEXT, "alpha")).items.size)
            assertEquals(1, search.search(plan(ws, SearchMode.TEXT, "kotlin")).items.size)
            assertEquals(0, search.search(plan(ws, SearchMode.TEXT, "123")).items.size)
            jdbc.update("UPDATE skw.entries SET properties = ?::jsonb WHERE id = ?", "{\"text\":\"beta\"}", id)
            assertEquals(0, search.search(plan(ws, SearchMode.TEXT, "alpha")).items.size)
            assertEquals(1, search.search(plan(ws, SearchMode.TEXT, "beta")).items.size)
            jdbc.update("DELETE FROM skw.entries WHERE id = ?", id)
            assertEquals(0, search.search(plan(ws, SearchMode.TEXT, "beta")).items.size)
        }

        @Test
        fun `workspace isolation and ranked keyset pagination are stable`() {
            val a = workspace()
            val b = workspace()
            repeat(4) { entry(a, "{\"text\":\"alpha alpha $it\"}") }
            entry(b, "{\"text\":\"alpha\"}")
            val first = search.search(plan(a, SearchMode.TEXT, "alpha", limit = 2))
            val second = search.search(plan(a, SearchMode.TEXT, "alpha", limit = 2).copy(continuation = first.nextCursor))
            assertEquals(2, first.items.size)
            assertEquals(2, second.items.size)
            assertTrue(
                first.items
                    .map { it.entry.id }
                    .intersect(second.items.map { it.entry.id })
                    .isEmpty(),
            )
            assertTrue(first.items.zipWithNext().all { it.first.rawRank!! >= it.second.rawRank!! })
            assertTrue(first.items.all { it.score in 0.0..1.0 })
            assertFalse(search.search(plan(a, SearchMode.TEXT, "alpha")).items.any { it.entry.workspaceId == b })
        }
    }
