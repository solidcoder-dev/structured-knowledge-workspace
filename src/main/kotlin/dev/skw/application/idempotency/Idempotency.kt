package dev.skw.application.idempotency

import dev.skw.application.port.out.TransactionRunner
import java.time.Instant

@JvmInline
value class IdempotencyKey(
    val value: String,
) {
    init {
        require(value.length in 1..255 && value.all { it.code in 0x21..0x7E }) {
            "Idempotency-Key must contain 1 to 255 visible ASCII characters"
        }
    }
}

data class IdempotencyScope(
    val method: String,
    val route: String,
    val workspace: String? = null,
    val principal: String? = null,
) {
    val value: String =
        buildString {
            append(method).append('|').append(route)
            workspace?.let { append("|workspace=").append(it) }
            principal?.let { append("|principal=").append(it) }
        }

    init {
        require(value.length in 1..1024) { "Idempotency scope is too long" }
    }
}

@JvmInline
value class RequestHash(
    val value: String,
) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) { "Request hash must be lowercase SHA-256" }
    }
}

data class ReplayableResponse(
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray? = null,
) {
    init {
        require(status in 200..299) { "Only successful responses can be idempotent" }
    }

    override fun equals(other: Any?): Boolean =
        other is ReplayableResponse && status == other.status && headers == other.headers && body.contentEquals(other.body)

    override fun hashCode(): Int = 31 * (31 * status + headers.hashCode()) + (body?.contentHashCode() ?: 0)
}

data class StoredIdempotencyResult(
    val requestHash: RequestHash,
    val response: ReplayableResponse,
    val expiresAt: Instant,
)

class IdempotencyKeyReused : RuntimeException("Idempotency key was reused with a different request payload")

class IdempotentExecutionService(
    private val store: IdempotencyStore,
    private val transactions: TransactionRunner,
) {
    fun execute(
        scope: IdempotencyScope,
        key: IdempotencyKey,
        requestHash: RequestHash,
        action: () -> ReplayableResponse,
    ): ReplayableResponse =
        transactions.inTransaction {
            store.lock(scope, key)
            val stored = store.findLive(scope, key)
            if (stored != null) {
                if (stored.requestHash != requestHash) throw IdempotencyKeyReused()
                return@inTransaction stored.response
            }
            store.removeExpired(scope, key)
            val response = action()
            store.store(scope, key, requestHash, response)
            response
        }
}

interface IdempotencyStore {
    fun lock(
        scope: IdempotencyScope,
        key: IdempotencyKey,
    )

    fun findLive(
        scope: IdempotencyScope,
        key: IdempotencyKey,
    ): StoredIdempotencyResult?

    fun removeExpired(
        scope: IdempotencyScope,
        key: IdempotencyKey,
    )

    fun store(
        scope: IdempotencyScope,
        key: IdempotencyKey,
        requestHash: RequestHash,
        response: ReplayableResponse,
    )
}
