package dev.skw.adapter.inbound.rest

import dev.skw.application.entry.EntryCursor
import dev.skw.domain.entry.EntryId
import dev.skw.domain.workspace.WorkspaceId
import java.time.Instant
import java.util.Base64
import java.util.UUID

class InvalidEntryCursor : RuntimeException("Invalid entry cursor")

class EntryCursorCodec {
    fun encode(cursor: EntryCursor): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            "${cursor.workspaceId.value}|${cursor.createdAt}|${cursor.entryId.value}".toByteArray(),
        )

    fun decode(
        value: String,
        workspaceId: WorkspaceId,
    ): EntryCursor =
        try {
            val parts = String(Base64.getUrlDecoder().decode(value)).split('|')
            if (parts.size != 3 || UUID.fromString(parts[0]) != workspaceId.value) throw InvalidEntryCursor()
            EntryCursor(workspaceId, Instant.parse(parts[1]), EntryId(UUID.fromString(parts[2])))
        } catch (_: RuntimeException) {
            throw InvalidEntryCursor()
        }
}
