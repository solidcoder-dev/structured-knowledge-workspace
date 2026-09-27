package dev.skw.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.domain.property.PropertyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PropertyJsonMapperTest {
    private val mapper = PropertyJsonMapper(ObjectMapper())

    @Test
    fun `maps every supported property value shape`() {
        val cases =
            mapOf(
                "\"text\"" to PropertyValue.StringValue("text"),
                "42" to PropertyValue.NumberValue(BigDecimal("42")),
                "true" to PropertyValue.BooleanValue(true),
                "[]" to PropertyValue.EmptyListValue,
                "[\"a\",\"b\"]" to PropertyValue.StringListValue(listOf("a", "b")),
                "[1,2]" to PropertyValue.NumberListValue(listOf(BigDecimal.ONE, BigDecimal("2"))),
                "[true,false]" to PropertyValue.BooleanListValue(listOf(true, false)),
            )

        cases.forEach { (json, expected) -> assertEquals(expected, mapper.toDomainValue(ObjectMapper().readTree(json))) }
    }

    @Test
    fun `rejects values outside the property contract`() {
        listOf("null", "{\"name\":\"value\"}", "[1,\"two\"]", "[[1]]", "[true,1]").forEach { json ->
            assertThrows(RuntimeException::class.java) { mapper.toDomainValue(ObjectMapper().readTree(json)) }
        }
    }
}
