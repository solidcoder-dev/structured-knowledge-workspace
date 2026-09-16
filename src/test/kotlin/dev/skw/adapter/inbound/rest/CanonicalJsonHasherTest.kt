package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CanonicalJsonHasherTest {
    private val mapper = ObjectMapper()
    private val hasher = CanonicalJsonHasher(mapper)

    @Test
    fun `object key order including nested objects is ignored`() {
        assertEquals(
            hasher.hash(mapper.readTree("{\"a\":1,\"b\":{\"x\":2,\"y\":3}}")),
            hasher.hash(mapper.readTree("{\"b\":{\"y\":3,\"x\":2},\"a\":1}")),
        )
    }

    @Test
    fun `scalar and array order changes the hash`() {
        val scalar = hasher.hash(mapper.readTree("{\"a\":1}"))
        assertNotEquals(scalar, hasher.hash(mapper.readTree("{\"a\":2}")))
        assertNotEquals(hasher.hash(mapper.readTree("[1,2]")), hasher.hash(mapper.readTree("[2,1]")))
    }
}
