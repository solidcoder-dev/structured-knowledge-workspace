package dev.skw.domain.relationship

private val RELATIONSHIP_TYPE_PATTERN = Regex("^[a-z][a-z0-9]*([._-][a-z0-9]+)*$")

class RelationshipType(
    val value: String,
) {
    init {
        require(value.length <= 128) { "Relationship type must not exceed 128 characters" }
        require(RELATIONSHIP_TYPE_PATTERN.matches(value)) { "Invalid relationship type" }
    }

    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is RelationshipType && value == other.value

    override fun hashCode(): Int = value.hashCode()
}
