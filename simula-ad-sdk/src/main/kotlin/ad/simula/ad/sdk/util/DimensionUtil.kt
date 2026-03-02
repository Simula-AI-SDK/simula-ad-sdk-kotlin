package ad.simula.ad.sdk.util

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Parsed dimension result — mirrors React's parseWidth() return shape.
 */
sealed class ParsedDimension {
    /** Fill container (100%) */
    data object Fill : ParsedDimension()
    /** Percentage of container, as a 0..1 fraction */
    data class Percentage(val fraction: Float) : ParsedDimension()
    /** Fixed pixel/dp value */
    data class Pixels(val dp: Dp) : ParsedDimension()
}

/**
 * Parse a flexible width/dimension input.
 * Mirrors the React SDK's parseWidth() from utils/parseWidth.ts exactly:
 *
 * - null/undefined -> Fill (100%)
 * - "auto" or "" -> Fill (100%)
 * - String ending with "%" (e.g. "80%") -> Percentage(0.8)
 * - String parseable as number (e.g. "500") -> Pixels(500.dp)
 * - Number < 1 (e.g. 0.8) -> Percentage(0.8)
 * - Number >= 1 (e.g. 500) -> Pixels(500.dp)
 * - else -> Fill
 */
fun parseDimension(input: Any?): ParsedDimension {
    // Handle null, "auto"
    if (input == null) return ParsedDimension.Fill

    // Handle string
    if (input is String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed == "auto") return ParsedDimension.Fill

        // String with %
        if (trimmed.endsWith("%")) {
            val percentValue = trimmed.removeSuffix("%").toFloatOrNull()
            if (percentValue != null && percentValue > 0f && percentValue <= 100f) {
                return ParsedDimension.Percentage(percentValue / 100f)
            }
        }

        // String with number (pixels)
        val pixelValue = trimmed.toFloatOrNull()
        if (pixelValue != null && pixelValue > 0f) {
            return ParsedDimension.Pixels(pixelValue.dp)
        }
    }

    // Handle Number
    if (input is Number) {
        val value = input.toFloat()
        // Number < 1 as percentage (e.g. 0.8 = 80%)
        if (value > 0f && value < 1f) {
            return ParsedDimension.Percentage(value)
        }
        // Number >= 1 as pixels
        if (value >= 1f) {
            return ParsedDimension.Pixels(value.dp)
        }
    }

    return ParsedDimension.Fill
}

/**
 * Apply a parsed dimension as a width modifier.
 */
fun Modifier.applyWidth(dimension: ParsedDimension): Modifier = when (dimension) {
    is ParsedDimension.Fill -> this.fillMaxWidth()
    is ParsedDimension.Percentage -> this.fillMaxWidth(dimension.fraction)
    is ParsedDimension.Pixels -> this.width(dimension.dp)
}

/**
 * Parse an offset value (top, left, etc.) to Dp.
 * Same format as width: number < 1 = percentage of screen, number >= 1 = dp.
 * String with % = percentage, string with number = dp.
 *
 * For percentage values, caller must multiply by screen dimension.
 *
 * Returns null if input is null/undefined.
 */
sealed class ParsedOffset {
    data class Percentage(val fraction: Float) : ParsedOffset()
    data class Fixed(val dp: Dp) : ParsedOffset()
}

fun parseOffset(value: Any?): ParsedOffset? {
    if (value == null) return null

    // String with %
    if (value is String && value.endsWith("%")) {
        val v = value.removeSuffix("%").toFloatOrNull()
        if (v != null) return ParsedOffset.Percentage(v / 100f)
    }

    // String with number (pixels/dp)
    if (value is String) {
        val v = value.toFloatOrNull()
        if (v != null) return ParsedOffset.Fixed(v.dp)
    }

    // Number < 1 = percentage
    if (value is Number) {
        val v = value.toFloat()
        if (v >= 0f && v < 1f) return ParsedOffset.Percentage(v)
        if (v >= 1f) return ParsedOffset.Fixed(v.dp)
        if (v == 0f) return ParsedOffset.Fixed(0.dp)
    }

    return null
}
