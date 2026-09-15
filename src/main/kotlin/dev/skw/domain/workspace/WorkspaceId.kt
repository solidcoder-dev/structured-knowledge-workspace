package dev.skw.domain.workspace

import java.util.UUID

data class WorkspaceId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}
