package dev.skw.domain.property

import java.math.BigDecimal

sealed interface PropertyValue {
    data class StringValue(
        val value: String,
    ) : PropertyValue {
        init {
            require(value.length <= 100_000)
        }
    }

    data class NumberValue(
        val value: BigDecimal,
    ) : PropertyValue

    data class BooleanValue(
        val value: Boolean,
    ) : PropertyValue

    data class StringListValue(
        val value: List<String>,
    ) : PropertyValue {
        init {
            require(value.size <= 1_000)
            require(value.all { it.length <= 100_000 })
        }
    }

    data class NumberListValue(
        val value: List<BigDecimal>,
    ) : PropertyValue {
        init {
            require(value.size <= 1_000)
        }
    }

    data class BooleanListValue(
        val value: List<Boolean>,
    ) : PropertyValue {
        init {
            require(value.size <= 1_000)
        }
    }

    data object EmptyListValue : PropertyValue
}
