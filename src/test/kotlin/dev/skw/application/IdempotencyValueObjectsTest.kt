package dev.skw.application

import dev.skw.application.idempotency.IdempotencyKey
import dev.skw.application.idempotency.RequestHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IdempotencyValueObjectsTest {
    @Test
    fun `key accepts visible ascii boundaries`() {
        assertEquals(1, IdempotencyKey("!").value.length)
        assertEquals(255, IdempotencyKey("x".repeat(255)).value.length)
    }

    @Test
    fun `key rejects whitespace non ascii and excessive length`() {
        listOf("", " ", "a\tb", "a\nb", "é", "x".repeat(256)).forEach {
            assertThrows<IllegalArgumentException> { IdempotencyKey(it) }
        }
    }

    @Test
    fun `hash only accepts lowercase sha256`() {
        RequestHash("0".repeat(64))
        assertThrows<IllegalArgumentException> { RequestHash("A".repeat(64)) }
        assertThrows<IllegalArgumentException> { RequestHash("0".repeat(63)) }
    }
}
