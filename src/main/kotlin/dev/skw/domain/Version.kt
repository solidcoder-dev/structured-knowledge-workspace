package dev.skw.domain

class Version private constructor(
    val value: Long,
) {
    fun next(): Version = Version(value + 1)

    companion object {
        fun of(value: Long): Version {
            require(value >= 1) { "Version must be at least 1" }
            return Version(value)
        }

        fun initial(): Version = Version(1)
    }

    override fun equals(other: Any?): Boolean = other is Version && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value.toString()
}
