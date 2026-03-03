package ad.simula.ad.sdk.util

import androidx.compose.ui.text.font.FontFamily

/**
 * Utility for parsing CSS font-family strings to Compose [FontFamily].
 * Maps common CSS font families to their Compose equivalents.
 */
object FontUtil {

    /**
     * Parse a CSS font-family string to Compose [FontFamily].
     *
     * Checks each font name in the comma-separated list and returns the first
     * recognized Compose FontFamily. Falls back to [FontFamily.SansSerif].
     *
     * Examples:
     * - "Inter, system-ui, sans-serif" → FontFamily.SansSerif
     * - "Georgia, Times New Roman, serif" → FontFamily.Serif
     * - "SF Mono, Consolas, monospace" → FontFamily.Monospace
     */
    fun parseFont(fontFamily: String?): FontFamily {
        if (fontFamily.isNullOrBlank()) return FontFamily.SansSerif

        val fonts = fontFamily.split(",").map { it.trim().lowercase().removeSurrounding("\"").removeSurrounding("'") }

        for (font in fonts) {
            when {
                font == "serif" -> return FontFamily.Serif
                font == "sans-serif" -> return FontFamily.SansSerif
                font == "monospace" -> return FontFamily.Monospace
                font == "cursive" -> return FontFamily.Cursive

                // Common sans-serif families
                font in listOf(
                    "inter", "system-ui", "-apple-system", "blinkmacsystemfont",
                    "segoe ui", "roboto", "helvetica neue", "arial", "helvetica",
                    "noto sans", "ubuntu", "open sans", "lato",
                ) -> return FontFamily.SansSerif

                // Common serif families
                font in listOf(
                    "georgia", "times new roman", "times", "hoefler text",
                    "baskerville old face", "garamond", "palatino", "book antiqua",
                ) -> return FontFamily.Serif

                // Common monospace families
                font in listOf(
                    "sf mono", "monaco", "inconsolata", "roboto mono",
                    "source code pro", "consolas", "courier new", "courier",
                    "ui-monospace", "menlo", "fira code", "jetbrains mono",
                ) -> return FontFamily.Monospace
            }
        }

        return FontFamily.SansSerif
    }
}
