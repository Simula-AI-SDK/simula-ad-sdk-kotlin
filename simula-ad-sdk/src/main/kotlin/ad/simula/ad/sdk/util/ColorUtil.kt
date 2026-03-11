package ad.simula.ad.sdk.util

import androidx.compose.ui.graphics.Color

/**
 * Utility for parsing CSS color strings to Compose [Color].
 * Supports hex (#RRGGBB, #RRGGBBAA, #RGB), rgba(), rgb(), and named colors.
 */
object ColorUtil {

    /**
     * Parse a CSS color string to Compose [Color].
     * Returns [Color.Transparent] for "transparent" or unparseable values.
     */
    fun parseColor(colorString: String): Color {
        val trimmed = colorString.trim()

        // Handle transparent
        if (trimmed.equals("transparent", ignoreCase = true)) return Color.Transparent

        // Handle hex colors
        if (trimmed.startsWith("#")) {
            return parseHexColor(trimmed)
        }

        // Handle rgba()
        if (trimmed.startsWith("rgba(")) {
            return parseRgba(trimmed)
        }

        // Handle rgb()
        if (trimmed.startsWith("rgb(")) {
            return parseRgb(trimmed)
        }

        // Fallback
        return Color.Transparent
    }

    private fun parseHexColor(hex: String): Color {
        return try {
            val cleaned = hex.removePrefix("#")
            when (cleaned.length) {
                3 -> {
                    // #RGB -> #RRGGBB
                    val r = cleaned[0].toString().repeat(2).toInt(16)
                    val g = cleaned[1].toString().repeat(2).toInt(16)
                    val b = cleaned[2].toString().repeat(2).toInt(16)
                    Color(r, g, b)
                }
                6 -> {
                    val colorLong = cleaned.toLong(16)
                    Color(
                        red = ((colorLong shr 16) and 0xFF).toInt(),
                        green = ((colorLong shr 8) and 0xFF).toInt(),
                        blue = (colorLong and 0xFF).toInt(),
                    )
                }
                8 -> {
                    val colorLong = cleaned.toLong(16)
                    Color(
                        red = ((colorLong shr 24) and 0xFF).toInt(),
                        green = ((colorLong shr 16) and 0xFF).toInt(),
                        blue = ((colorLong shr 8) and 0xFF).toInt(),
                        alpha = (colorLong and 0xFF).toInt(),
                    )
                }
                else -> Color.Transparent
            }
        } catch (e: Exception) {
            Color.Transparent
        }
    }

    private fun parseRgba(rgba: String): Color {
        return try {
            val content = rgba
                .removePrefix("rgba(")
                .removeSuffix(")")
                .split(",")
                .map { it.trim() }
            if (content.size >= 4) {
                Color(
                    red = content[0].toInt(),
                    green = content[1].toInt(),
                    blue = content[2].toInt(),
                    alpha = (content[3].toFloat() * 255).toInt(),
                )
            } else {
                Color.Transparent
            }
        } catch (e: Exception) {
            Color.Transparent
        }
    }

    private fun parseRgb(rgb: String): Color {
        return try {
            val content = rgb
                .removePrefix("rgb(")
                .removeSuffix(")")
                .split(",")
                .map { it.trim() }
            if (content.size >= 3) {
                Color(
                    red = content[0].toInt(),
                    green = content[1].toInt(),
                    blue = content[2].toInt(),
                )
            } else {
                Color.Transparent
            }
        } catch (e: Exception) {
            Color.Transparent
        }
    }
}
