package dev.skw.application

import dev.skw.application.port.out.HybridRankingPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HybridRankingPolicyTest {
    @Test
    fun `rrf k must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { HybridRankingPolicy(0) }
    }

    @Test
    fun `identifier is stable and captures version and k`() {
        assertEquals(HybridRankingPolicy().identifier, HybridRankingPolicy().identifier)
        assertNotEquals(HybridRankingPolicy().identifier, HybridRankingPolicy(rrfK = 30).identifier)
        assertNotEquals(HybridRankingPolicy().identifier, HybridRankingPolicy(version = "rrf-v2").identifier)
    }
}
