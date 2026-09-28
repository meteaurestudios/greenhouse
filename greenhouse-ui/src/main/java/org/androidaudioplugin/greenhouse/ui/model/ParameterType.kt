package org.androidaudioplugin.greenhouse.ui.model

import org.androidaudioplugin.ParameterInformation
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

private const val EPSILON = 0.0001
private const val MIN_INTEGER_RANGE_SPAN = 2.0
private const val MAX_INTEGER_RANGE_SPAN = 256.0
private const val BOOLEAN_MIN_VALUE = 0.0
private const val BOOLEAN_MAX_VALUE = 1.0

sealed interface ParameterType {
    data class FloatType(
        val min: Double,
        val max: Double
    ) : ParameterType

    data class IntType(
        val min: Int,
        val max: Int
    ) : ParameterType

    data class BoolType(
        val offLabel: String = "Off",
        val onLabel: String = "On"
    ) : ParameterType

    data class EnumType(
        val options: List<ParameterInformation.EnumerationInformation>
    ) : ParameterType
}

// Explicit boolean naming keywords and types (e.g. bypass switch, mute button, sync toggle, bool/boolean flag)
private val EXPLICIT_BOOLEAN_KEYWORDS = listOf(
    "bypass",
    "mute",
    "enable",
    "active",
    "on/off",
    "power",
    "solo",
    "phase invert",
    "invert phase",
    "sync",
    "bool",
    "boolean",
    "toggle",
    "switch"
)

private fun isWholeNumber(value: Double): Boolean {
    val rounded = value.roundToInt().toDouble()
    return abs(value - rounded) < EPSILON
}

private val typeCache = ConcurrentHashMap<String, ParameterType>()

val ParameterInformation.inferredType: ParameterType
    get() {
        val cacheKey = "${id}_${name}_${minimumValue}_${maximumValue}_${defaultValue}_${enumerations.size}"
        return typeCache.computeIfAbsent(cacheKey) {
            computeInferredType()
        }
    }

private fun ParameterInformation.computeInferredType(): ParameterType {
    val isZeroMin = abs(minimumValue - BOOLEAN_MIN_VALUE) < EPSILON
    val isOneMax = abs(maximumValue - BOOLEAN_MAX_VALUE) < EPSILON
    val isZeroToOne = isZeroMin && isOneMax

    // 1. Explicit Enumerations with size == 2 AND min = 0 AND max = 1 -> Button
    if (enumerations.size == 2 && isZeroToOne) {
        val first = enumerations[0]
        val second = enumerations[1]

        val minEnum = if (first.value <= second.value) {
            first
        } else {
            second
        }

        val maxEnum = if (first.value <= second.value) {
            second
        } else {
            first
        }

        return ParameterType.BoolType(
            offLabel = minEnum.name,
            onLabel = maxEnum.name
        )
    }

    // 2. Explicit Boolean type or keyword in name with 0.0..1.0 bounds -> Button
    if (isZeroToOne) {
        val lowerName = name.lowercase()
        val hasExplicitBoolKeyword = EXPLICIT_BOOLEAN_KEYWORDS.any { keyword ->
            lowerName.contains(keyword)
        }

        if (hasExplicitBoolKeyword) {
            return ParameterType.BoolType(
                offLabel = "Off",
                onLabel = "On"
            )
        }
    }

    // 3. Explicit Enumerations with size >= 2 (non 0..1 bounds or > 2 items) -> Enum Selector / Stepper
    if (enumerations.size >= 2) {
        return ParameterType.EnumType(
            options = enumerations
        )
    }

    // 4. Stepped Integer Range (span >= 2 and whole numbers e.g. 0..2, 0..7, 1..16, 0..127) -> Discrete Knob
    val isMinInt = isWholeNumber(minimumValue)
    val isMaxInt = isWholeNumber(maximumValue)
    val isDefaultInt = isWholeNumber(defaultValue)
    val span = maximumValue - minimumValue

    if (isMinInt && isMaxInt && isDefaultInt && span >= MIN_INTEGER_RANGE_SPAN && span <= MAX_INTEGER_RANGE_SPAN) {
        val minInt = minimumValue.roundToInt()
        val maxInt = maximumValue.roundToInt()

        return ParameterType.IntType(
            min = minInt,
            max = maxInt
        )
    }

    // 5. Default: Continuous Floating-Point Rotary Knob (handles normalized 0.0..1.0 and arbitrary float spans)
    return ParameterType.FloatType(
        min = minimumValue,
        max = maximumValue
    )
}
