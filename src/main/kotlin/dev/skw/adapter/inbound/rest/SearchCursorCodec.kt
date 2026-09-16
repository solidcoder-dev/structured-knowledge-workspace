package dev.skw.adapter.inbound.rest

import dev.skw.application.search.InvalidSearchCursor
import dev.skw.application.search.SearchCursor
import dev.skw.domain.entry.EntryId
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

class SearchCursorCodec {
    fun encode(cursor: SearchCursor): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            "${cursor.version}|${cursor.fingerprint}|${cursor.sortValue ?: ""}|${cursor.entryId.value}".toByteArray(StandardCharsets.UTF_8),
        )

    fun decode(value: String): SearchCursor =
        try {
            val parts = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split('|')
            if (parts.size != 4 || parts[0] != "1") throw InvalidSearchCursor()
            val rank = parts[2].takeIf { it.isNotEmpty() }?.toDouble()?.also { if (!it.isFinite()) throw InvalidSearchCursor() }
            SearchCursor(1, parts[1], rank, EntryId(UUID.fromString(parts[3])))
        } catch (_: RuntimeException) {
            throw InvalidSearchCursor()
        }
}
