package dev.skw.domain.accesscontrol

import dev.skw.domain.property.PropertyName
import dev.skw.domain.relationship.RelationshipType

private val NAMESPACE_PATTERN = Regex("^[a-z][a-z0-9]*([._-][a-z0-9]+)*$")

class Namespace(val value: String) {
    init {
        require(value.length <= 128) { "Namespace must not exceed 128 characters" }
        require(NAMESPACE_PATTERN.matches(value)) { "Invalid namespace" }
    }

    fun matches(propertyName: PropertyName): Boolean = from(propertyName.toString()) == this

    fun matches(relationshipType: RelationshipType): Boolean = from(relationshipType.toString()) == this

    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is Namespace && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        fun from(identifier: String): Namespace = Namespace(identifier.substringBefore('.'))
    }
}
