package dev.skw.domain.accesscontrol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NamespaceContractTest {
    @Test
    fun `extracts namespace without confusing namespace and qualified identifier`() {
        assertEquals(Namespace("design-definition"), Namespace.from("design-definition"))
        assertEquals(Namespace("design-definition"), Namespace.from("design-definition.satisfies"))
        assertEquals(Namespace("alpha"), Namespace.from("alpha.status"))
    }

    @Test
    fun `rejects malformed qualified identifiers`() {
        listOf("", ".status", "alpha.", "alpha..status", "Alpha.status", "alpha/status").forEach {
            assertThrows(IllegalArgumentException::class.java) { Namespace.from(it) }
        }
    }

    @Test
    fun `rejects dots and underscores in namespace values`() {
        assertThrows(IllegalArgumentException::class.java) { Namespace("alpha.beta") }
        assertThrows(IllegalArgumentException::class.java) { Namespace("alpha_beta") }
    }
}
