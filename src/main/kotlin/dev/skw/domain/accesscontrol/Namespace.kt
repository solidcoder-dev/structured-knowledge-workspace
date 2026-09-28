package dev.skw.domain.accesscontrol

import dev.skw.domain.property.PropertyName
import dev.skw.domain.relationship.RelationshipType

private val NAMESPACE_PATTERN = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
private val LOCAL_IDENTIFIER_PATTERN = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")

class Namespace(
    val value: String,
) {
    init {
        require(value.length in 1..64) { "Namespace must be between 1 and 64 characters" }
        require(NAMESPACE_PATTERN.matches(value)) { "Invalid namespace" }
    }

    fun matches(propertyName: PropertyName): Boolean = from(propertyName.toString()) == this

    fun matches(relationshipType: RelationshipType): Boolean = from(relationshipType.toString()) == this

    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is Namespace && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        fun from(identifier: String): Namespace {
            require(identifier.isNotEmpty()) { "Identifier must not be empty" }
            val separator = identifier.indexOf('.')
            val namespace = if (separator < 0) identifier else identifier.substring(0, separator)
            Namespace(namespace)
            if (separator >=
                0
            ) {
                require(LOCAL_IDENTIFIER_PATTERN.matches(identifier.substring(separator + 1))) { "Invalid qualified identifier" }
            }
            return Namespace(namespace)
        }
    }
}
