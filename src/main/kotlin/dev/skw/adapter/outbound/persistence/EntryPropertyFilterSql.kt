package dev.skw.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.application.search.PropertyFilter
import dev.skw.application.search.PropertyFilterOperator
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource

internal class EntryPropertyFilterSql(
    private val mapper: ObjectMapper,
    private val json: PropertyJsonMapper,
) {
    fun append(
        filters: List<PropertyFilter>,
        alias: String,
        params: MapSqlParameterSource,
        conditions: MutableList<String>,
    ) {
        filters.forEachIndexed { index, filter ->
            val property = "property$index"
            params.addValue(property, filter.property.value)
            val expression = "$alias.properties -> :$property"
            when (filter.operator) {
                PropertyFilterOperator.EXISTS -> conditions += "jsonb_exists($alias.properties, :$property)"
                PropertyFilterOperator.EQUALS -> {
                    params.addValue("value$index", mapper.writeValueAsString(json.json(filter.value!!)))
                    conditions += "$expression = CAST(:value$index AS jsonb)"
                }
                PropertyFilterOperator.CONTAINS -> {
                    params.addValue("value$index", mapper.writeValueAsString(json.json(filter.value!!)))
                    conditions += "jsonb_typeof($expression) = 'array' AND $expression @> jsonb_build_array(CAST(:value$index AS jsonb))"
                }
            }
        }
    }
}
