package dev.skw.adapter.inbound.rest

import dev.skw.application.relationship.InvalidRelationshipCursor
import dev.skw.application.relationship.RelationshipCursor
import dev.skw.application.relationship.RelationshipDirection
import dev.skw.domain.entry.EntryId
import dev.skw.domain.relationship.RelationshipId
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId
import java.time.Instant
import java.util.Base64
import java.util.UUID

class RelationshipCursorCodec {
    fun encode(cursor: RelationshipCursor): String {
        val payload =
            listOf(
                "1",
                cursor.workspaceId.value,
                cursor.entryId.value,
                cursor.direction,
                cursor.type?.value.orEmpty(),
                cursor.createdAt,
                cursor.relationshipId.value,
            ).joinToString("|")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    fun decode(value: String): RelationshipCursor =
        try {
            val parts = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8).split("|", limit = 7)
            require(parts.size == 7 && parts[0] == "1")
            RelationshipCursor(
                WorkspaceId(UUID.fromString(parts[1])),
                EntryId(UUID.fromString(parts[2])),
                RelationshipDirection.valueOf(parts[3]),
                parts[4].takeIf(String::isNotEmpty)?.let(::RelationshipType),
                Instant.parse(parts[5]),
                RelationshipId(UUID.fromString(parts[6])),
            )
        } catch (error: Exception) {
            throw InvalidRelationshipCursor()
        }
}
