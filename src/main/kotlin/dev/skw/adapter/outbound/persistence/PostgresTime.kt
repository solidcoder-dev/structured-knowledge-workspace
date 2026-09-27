package dev.skw.adapter.outbound.persistence

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

object PostgresTime {
    fun toDatabase(value: Instant): OffsetDateTime = value.atOffset(ZoneOffset.UTC)

    fun fromDatabase(value: OffsetDateTime): Instant = value.toInstant()
}
