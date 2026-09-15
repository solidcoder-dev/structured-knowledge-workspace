package dev.skw.adapter.inbound.rest

import dev.skw.domain.Version
import dev.skw.domain.workspace.WorkspaceId

class MissingIfMatch : RuntimeException("If-Match header is required")

class MalformedEtag : RuntimeException("If-Match must contain one valid strong ETag")

class ResourceEtag {
    fun format(
        id: WorkspaceId,
        version: Version,
    ): String = "\"${id.value}-v${version.value}\""

    fun requireVersion(
        id: WorkspaceId,
        header: String?,
    ): Version =
        when {
            header == null -> throw MissingIfMatch()
            header.contains(",") -> throw MalformedEtag()
            header == "*" -> throw MalformedEtag()
            else -> parse(id, header)
        }

    private fun parse(
        id: WorkspaceId,
        header: String,
    ): Version {
        val match = Regex("^\\\"(${id.value})-v([1-9][0-9]*)\\\"$").matchEntire(header) ?: throw MalformedEtag()
        return try {
            Version.of(match.groupValues[2].toLong())
        } catch (_: RuntimeException) {
            throw MalformedEtag()
        }
    }
}
