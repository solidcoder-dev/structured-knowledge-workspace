package dev.skw.domain.entry

import java.util.UUID

data class EntryId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}
