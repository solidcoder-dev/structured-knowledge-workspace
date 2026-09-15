package dev.skw.domain.relationship

import java.util.UUID

data class RelationshipId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}
