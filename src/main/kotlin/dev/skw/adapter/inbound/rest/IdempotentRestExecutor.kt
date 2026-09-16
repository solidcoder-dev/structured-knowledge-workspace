package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.application.idempotency.IdempotencyKey
import dev.skw.application.idempotency.IdempotencyScope
import dev.skw.application.idempotency.IdempotentExecutionService
import dev.skw.application.idempotency.ReplayableResponse
import dev.skw.application.idempotency.RequestHash
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.util.ContentCachingRequestWrapper

@Component
class IdempotentRestExecutor(
    private val execution: IdempotentExecutionService,
    private val objectMapper: ObjectMapper,
    private val request: HttpServletRequest,
    private val hasher: CanonicalJsonHasher = CanonicalJsonHasher(objectMapper),
) {
    fun <T> execute(
        scope: IdempotencyScope,
        key: String,
        requestBody: Any,
        responseType: Class<T>,
        action: () -> ResponseEntity<T>,
    ): ResponseEntity<T> {
        val hash = RequestHash(canonicalHash(requestBody))
        val result = execution.execute(scope, IdempotencyKey(key), hash) { toReplayable(action()) }
        return fromReplayable(result, responseType)
    }

    private fun canonicalHash(body: Any): String {
        val raw = (request as? ContentCachingRequestWrapper)?.contentAsByteArray
        val node = if (raw != null && raw.isNotEmpty()) objectMapper.readTree(raw) else objectMapper.valueToTree<JsonNode>(body)
        return hasher.hash(node)
    }

    private fun <T> toReplayable(entity: ResponseEntity<T>): ReplayableResponse {
        val headers =
            entity.headers.filterKeys {
                it.equals(HttpHeaders.LOCATION, true) ||
                    it.equals(HttpHeaders.ETAG, true) ||
                    it.equals(HttpHeaders.CONTENT_TYPE, true)
            }
        return ReplayableResponse(entity.statusCode.value(), headers, entity.body?.let(objectMapper::writeValueAsBytes))
    }

    private fun <T> fromReplayable(
        response: ReplayableResponse,
        type: Class<T>,
    ): ResponseEntity<T> {
        val builder = ResponseEntity.status(response.status)
        response.headers.forEach { (name, values) -> values.forEach { builder.header(name, it) } }
        return builder.body(response.body?.let { objectMapper.readValue(it, type) })
    }
}
