package dev.skw.adapter.outbound.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.application.idempotency.IdempotencyKey
import dev.skw.application.idempotency.IdempotencyScope
import dev.skw.application.idempotency.IdempotencyStore
import dev.skw.application.idempotency.ReplayableResponse
import dev.skw.application.idempotency.RequestHash
import dev.skw.application.idempotency.StoredIdempotencyResult
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.OffsetDateTime

@Repository
class JdbcIdempotencyStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : IdempotencyStore {
    override fun lock(
        scope: IdempotencyScope,
        key: IdempotencyKey,
    ) {
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(:lockKey)",
            MapSqlParameterSource("lockKey", advisoryKey(scope, key)),
        ) { _, _ -> true }
    }

    override fun findLive(
        scope: IdempotencyScope,
        key: IdempotencyKey,
    ): StoredIdempotencyResult? =
        jdbc
            .query(
                "SELECT request_hash, response_status, response_headers, response_body, expires_at FROM skw.idempotency_requests WHERE scope = :scope AND key = :key AND expires_at > statement_timestamp()",
                params(scope, key),
            ) { rs, _ ->
                StoredIdempotencyResult(
                    RequestHash(rs.getString("request_hash")),
                    ReplayableResponse(
                        rs.getInt("response_status"),
                        objectMapper.readValue(rs.getString("response_headers"), object : TypeReference<Map<String, List<String>>>() {}),
                        rs.getBytes("response_body"),
                    ),
                    rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                )
            }.firstOrNull()

    override fun removeExpired(
        scope: IdempotencyScope,
        key: IdempotencyKey,
    ) {
        jdbc.update(
            "DELETE FROM skw.idempotency_requests WHERE scope = :scope AND key = :key AND expires_at <= statement_timestamp()",
            params(scope, key),
        )
    }

    override fun store(
        scope: IdempotencyScope,
        key: IdempotencyKey,
        requestHash: RequestHash,
        response: ReplayableResponse,
    ) {
        jdbc.update(
            """INSERT INTO skw.idempotency_requests
                (scope, key, request_hash, response_status, response_headers, response_body, expires_at)
                VALUES (:scope, :key, :hash, :status, CAST(:headers AS jsonb), :body, statement_timestamp() + INTERVAL '24 hours')""",
            params(scope, key)
                .addValue("hash", requestHash.value)
                .addValue("status", response.status)
                .addValue("headers", objectMapper.writeValueAsString(response.headers))
                .addValue("body", response.body),
        )
    }

    private fun params(
        scope: IdempotencyScope,
        key: IdempotencyKey,
    ) = MapSqlParameterSource().addValue("scope", scope.value).addValue("key", key.value)

    private fun advisoryKey(
        scope: IdempotencyScope,
        key: IdempotencyKey,
    ): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest("${scope.value}\u0000${key.value}".toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
    }
}
