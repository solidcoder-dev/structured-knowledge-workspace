package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

class CanonicalJsonHasher(
    private val objectMapper: ObjectMapper,
) {
    fun hash(node: JsonNode): String =
        MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(sort(node))).joinToString("") { "%02x".format(it) }

    private fun sort(node: JsonNode): JsonNode =
        when {
            node.isObject ->
                objectMapper.createObjectNode().also { target ->
                    node
                        .fieldNames()
                        .asSequence()
                        .toList()
                        .sorted()
                        .forEach { name -> target.set<JsonNode>(name, sort(node[name])) }
                }
            node.isArray -> objectMapper.createArrayNode().also { target -> node.forEach { target.add(sort(it)) } }
            else -> node
        }
}
