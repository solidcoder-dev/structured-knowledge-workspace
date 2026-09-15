package dev.skw.adapter.inbound.rest

import dev.skw.application.workspace.WorkspaceCursor
import dev.skw.domain.workspace.WorkspaceId
import java.time.Instant
import java.util.Base64
import java.util.UUID

class InvalidWorkspaceCursor : RuntimeException("Invalid workspace cursor")

class WorkspaceCursorCodec {
    fun encode(cursor: WorkspaceCursor): String {
        val payload = "${cursor.createdAt}|${cursor.id.value}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    fun decode(value: String): WorkspaceCursor {
        try {
            val payload = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
            val parts = payload.split('|')
            if (parts.size != 2) throw InvalidWorkspaceCursor()
            return WorkspaceCursor(Instant.parse(parts[0]), WorkspaceId(UUID.fromString(parts[1])))
        } catch (_: InvalidWorkspaceCursor) {
            throw InvalidWorkspaceCursor()
        } catch (_: RuntimeException) {
            throw InvalidWorkspaceCursor()
        }
    }
}
