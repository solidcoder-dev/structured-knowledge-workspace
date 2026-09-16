package dev.skw.application

import dev.skw.application.idempotency.IdempotencyKey
import dev.skw.application.idempotency.IdempotencyKeyReused
import dev.skw.application.idempotency.IdempotencyScope
import dev.skw.application.idempotency.IdempotencyStore
import dev.skw.application.idempotency.IdempotentExecutionService
import dev.skw.application.idempotency.ReplayableResponse
import dev.skw.application.idempotency.RequestHash
import dev.skw.application.idempotency.StoredIdempotencyResult
import dev.skw.application.port.out.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IdempotencyExecutionTest {
    private val scope = IdempotencyScope("POST", "/api/v1/workspaces")
    private val key = IdempotencyKey("abc")
    private val hash = RequestHash("a".repeat(64))
    private val response = ReplayableResponse(201, mapOf("Location" to listOf("/resource/1")), "body".toByteArray())

    @Test
    fun `first execution stores and replay does not invoke callback`() {
        val store = FakeStore()
        val service = IdempotentExecutionService(store, ImmediateTransaction)
        var invocations = 0

        assertEquals(
            response,
            service.execute(scope, key, hash) {
                invocations++
                response
            },
        )
        assertEquals(
            response,
            service.execute(scope, key, hash) {
                invocations++
                error("must not execute")
            },
        )
        assertEquals(1, invocations)
    }

    @Test
    fun `different hash conflicts without invoking callback`() {
        val store = FakeStore()
        val service = IdempotentExecutionService(store, ImmediateTransaction)
        service.execute(scope, key, hash) { response }

        assertThrows<IdempotencyKeyReused> {
            service.execute(scope, key, RequestHash("b".repeat(64))) { error("must not execute") }
        }
    }

    @Test
    fun `failed callback leaves no result`() {
        val store = FakeStore()
        val service = IdempotentExecutionService(store, ImmediateTransaction)
        assertThrows<IllegalStateException> { service.execute(scope, key, hash) { error("failure") } }
        assertNull(store.result)
    }

    private object ImmediateTransaction : TransactionRunner {
        override fun <T> inTransaction(action: () -> T): T = action()
    }

    private class FakeStore : IdempotencyStore {
        var result: StoredIdempotencyResult? = null

        override fun lock(
            scope: IdempotencyScope,
            key: IdempotencyKey,
        ) = Unit

        override fun findLive(
            scope: IdempotencyScope,
            key: IdempotencyKey,
        ) = result

        override fun removeExpired(
            scope: IdempotencyScope,
            key: IdempotencyKey,
        ) = Unit

        override fun store(
            scope: IdempotencyScope,
            key: IdempotencyKey,
            requestHash: RequestHash,
            response: ReplayableResponse,
        ) {
            result = StoredIdempotencyResult(requestHash, response, java.time.Instant.MAX)
        }
    }
}
