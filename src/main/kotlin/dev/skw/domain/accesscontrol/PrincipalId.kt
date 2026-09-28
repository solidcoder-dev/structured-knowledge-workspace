package dev.skw.domain.accesscontrol

class PrincipalId(
    val value: String,
) {
    init {
        require(value.length <= 128) { "Principal ID must not exceed 128 characters" }
        require(value.isNotBlank() && value.none(Char::isWhitespace)) { "Principal ID must be non-blank and contain no whitespace" }
    }

    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is PrincipalId && value == other.value

    override fun hashCode(): Int = value.hashCode()
}
