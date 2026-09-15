package dev.skw.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue

class PropertyJsonMapper(
    private val objectMapper: ObjectMapper,
) {
    fun toDomain(node: JsonNode): Map<PropertyName, PropertyValue> {
        require(node.isObject) { "Properties must be an object" }
        return node.fields().asSequence().associate { (name, value) -> PropertyName(name) to value.toDomainValue() }
    }

    fun toJson(properties: Map<PropertyName, PropertyValue>): String =
        objectMapper.writeValueAsString(
            objectMapper.createObjectNode().apply {
                properties.forEach { (name, value) -> set<JsonNode>(name.value, value.toJsonNode()) }
            },
        )

    fun value(node: JsonNode): PropertyValue = node.toDomainValue()

    fun json(value: PropertyValue): JsonNode = value.toJsonNode()

    private fun JsonNode.toDomainValue(): PropertyValue =
        when {
            isTextual -> PropertyValue.StringValue(textValue())
            isNumber -> PropertyValue.NumberValue(decimalValue())
            isBoolean -> PropertyValue.BooleanValue(booleanValue())
            isArray -> (this as ArrayNode).toListValue()
            else -> error("Property values must be scalar or homogeneous arrays")
        }

    private fun ArrayNode.toListValue(): PropertyValue {
        if (isEmpty) return PropertyValue.EmptyListValue
        require(all { it.isValueNode && !it.isNull })
        return when {
            all { it.isTextual } -> PropertyValue.StringListValue(map { it.textValue() })
            all { it.isNumber } -> PropertyValue.NumberListValue(map { it.decimalValue() })
            all { it.isBoolean } -> PropertyValue.BooleanListValue(map { it.booleanValue() })
            else -> error("Property arrays must be homogeneous")
        }
    }

    private fun PropertyValue.toJsonNode(): JsonNode =
        when (this) {
            is PropertyValue.StringValue -> objectMapper.nodeFactory.textNode(value)
            is PropertyValue.NumberValue -> objectMapper.nodeFactory.numberNode(value)
            is PropertyValue.BooleanValue -> objectMapper.nodeFactory.booleanNode(value)
            is PropertyValue.StringListValue -> objectMapper.valueToTree(value)
            is PropertyValue.NumberListValue -> objectMapper.valueToTree(value)
            is PropertyValue.BooleanListValue -> objectMapper.valueToTree(value)
            PropertyValue.EmptyListValue -> objectMapper.createArrayNode()
        }
}
