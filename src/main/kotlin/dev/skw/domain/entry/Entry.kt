package dev.skw.domain.entry

import dev.skw.domain.Version
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.workspace.WorkspaceId
import java.time.Instant

data class Entry private constructor(
    val id: EntryId,
    val workspaceId: WorkspaceId,
    val properties: Map<PropertyName, PropertyValue>,
    val version: Version,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun setProperty(
        name: PropertyName,
        value: PropertyValue,
        now: Instant = updatedAt,
    ): Entry =
        if (properties[name] == value) {
            this
        } else {
            copy(properties = properties + (name to value), version = version.next(), updatedAt = now)
        }

    fun removeProperty(
        name: PropertyName,
        now: Instant = updatedAt,
    ): Entry =
        if (!properties.containsKey(name)) {
            this
        } else {
            copy(properties = properties - name, version = version.next(), updatedAt = now)
        }

    companion object {
        fun create(
            workspaceId: WorkspaceId,
            properties: Map<PropertyName, PropertyValue> = emptyMap(),
            now: Instant,
            id: EntryId = EntryId(java.util.UUID.randomUUID()),
        ): Entry = Entry(id, workspaceId, properties.toMap(), Version.initial(), now, now)
    }
}
