package dev.skw.adapter.inbound.rest

import dev.skw.application.search.InvalidSearchCursor
import dev.skw.application.search.SearchCursor
import dev.skw.domain.entry.EntryId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

class SearchCursorCodecTest {
    private val codec = SearchCursorCodec()

    @Test
    fun `version one round trips ranked and unranked continuations`() {
        val id = EntryId(UUID.randomUUID())
        val ranked = SearchCursor(fingerprint = "fp", sortValue = 1.25, entryId = id)
        val unranked = SearchCursor(fingerprint = "fp", entryId = id)
        assertEquals(ranked, codec.decode(codec.encode(ranked)))
        assertEquals(unranked, codec.decode(codec.encode(unranked)))
    }

    @Test
    fun `malformed cursor values are rejected`() {
        val malformed =
            listOf(
                "not-base64",
                encode("1|fp|1"),
                encode("2|fp||${UUID.randomUUID()}"),
                encode("1|fp|NaN|${UUID.randomUUID()}"),
                encode("1|fp|Infinity|${UUID.randomUUID()}"),
                encode("1|fp||not-a-uuid"),
            )
        malformed.forEach { assertThrows(InvalidSearchCursor::class.java) { codec.decode(it) } }
    }

    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}
