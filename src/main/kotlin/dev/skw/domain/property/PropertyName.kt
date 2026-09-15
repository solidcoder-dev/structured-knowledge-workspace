package dev.skw.domain.property

private val PROPERTY_NAME_PATTERN = Regex("^[a-z][a-z0-9]*([._-][a-z0-9]+)*$")

class PropertyName(
    val value: String,
) {
    init {
        require(value.length <= 128) { "Property name must not exceed 128 characters" }
        require(PROPERTY_NAME_PATTERN.matches(value)) { "Invalid property name" }
    }

    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is PropertyName && value == other.value

    override fun hashCode(): Int = value.hashCode()
}
