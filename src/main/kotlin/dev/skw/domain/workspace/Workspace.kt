package dev.skw.domain.workspace

import dev.skw.domain.Version
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import java.time.Instant

data class Workspace private constructor(
    val id: WorkspaceId,
    val properties: Map<PropertyName, PropertyValue>,
    val version: Version,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun setProperty(
        name: PropertyName,
        value: PropertyValue,
        now: Instant = updatedAt,
    ): Workspace =
        if (properties[name] == value) {
            this
        } else {
            copy(properties = properties + (name to value), version = version.next(), updatedAt = now)
        }

    fun removeProperty(
        name: PropertyName,
        now: Instant = updatedAt,
    ): Workspace =
        if (!properties.containsKey(name)) {
            this
        } else {
            copy(properties = properties - name, version = version.next(), updatedAt = now)
        }

    companion object {
        fun create(
            properties: Map<PropertyName, PropertyValue> = emptyMap(),
            now: Instant,
            id: WorkspaceId = WorkspaceId(java.util.UUID.randomUUID()),
        ): Workspace = Workspace(id, properties.toMap(), Version.initial(), now, now)

        fun restore(
            id: WorkspaceId,
            properties: Map<PropertyName, PropertyValue>,
            version: Version,
            createdAt: Instant,
            updatedAt: Instant,
        ): Workspace = Workspace(id, properties.toMap(), version, createdAt, updatedAt)
    }
}
