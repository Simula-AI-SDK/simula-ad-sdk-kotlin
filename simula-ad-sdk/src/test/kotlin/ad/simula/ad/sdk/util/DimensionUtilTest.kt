package ad.simula.ad.sdk.util

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class DimensionUtilTest {

    // ── Defaults / null ───────────────────────────────────────────────────────

    @Test
    fun `null returns Fill`() {
        assertEquals(ParsedDimension.Fill, parseDimension(null))
    }

    @Test
    fun `empty string returns Fill`() {
        assertEquals(ParsedDimension.Fill, parseDimension(""))
    }

    @Test
    fun `auto returns Fill`() {
        assertEquals(ParsedDimension.Fill, parseDimension("auto"))
    }

    // ── Percentage strings ────────────────────────────────────────────────────

    @Test
    fun `percentage string 80 percent`() {
        assertEquals(ParsedDimension.Percentage(0.8f), parseDimension("80%"))
    }

    @Test
    fun `percentage string 100 percent`() {
        assertEquals(ParsedDimension.Percentage(1.0f), parseDimension("100%"))
    }

    @Test
    fun `percentage string 50 percent with spaces`() {
        assertEquals(ParsedDimension.Percentage(0.5f), parseDimension("  50%  "))
    }

    // ── Pixel strings ─────────────────────────────────────────────────────────

    @Test
    fun `numeric string as pixels`() {
        assertEquals(ParsedDimension.Pixels(300.dp), parseDimension("300"))
    }

    @Test
    fun `px suffix string`() {
        assertEquals(ParsedDimension.Pixels(320.dp), parseDimension("320px"))
    }

    @Test
    fun `px suffix uppercase`() {
        assertEquals(ParsedDimension.Pixels(320.dp), parseDimension("320PX"))
    }

    @Test
    fun `px suffix with spaces`() {
        assertEquals(ParsedDimension.Pixels(400.dp), parseDimension("  400px  "))
    }

    // ── Number inputs ─────────────────────────────────────────────────────────

    @Test
    fun `float less than 1 as percentage`() {
        assertEquals(ParsedDimension.Percentage(0.8f), parseDimension(0.8f))
    }

    @Test
    fun `double less than 1 as percentage`() {
        assertEquals(ParsedDimension.Percentage(0.5f), parseDimension(0.5))
    }

    @Test
    fun `int as pixels`() {
        assertEquals(ParsedDimension.Pixels(300.dp), parseDimension(300))
    }

    @Test
    fun `float greater than 1 as pixels`() {
        assertEquals(ParsedDimension.Pixels(320.dp), parseDimension(320f))
    }

    // ── Dp input ───────────────────────────────────────────────────────────────

    @Test
    fun `Dp value as pixels`() {
        assertEquals(ParsedDimension.Pixels(300.dp), parseDimension(300.dp))
    }

    @Test
    fun `zero Dp returns Fill`() {
        assertEquals(ParsedDimension.Fill, parseDimension(0.dp))
    }

    // ── Invalid / fallback ────────────────────────────────────────────────────

    @Test
    fun `negative number returns Fill`() {
        assertEquals(ParsedDimension.Fill, parseDimension(-100))
    }

    @Test
    fun `zero returns Fill`() {
        assertEquals(ParsedDimension.Fill, parseDimension(0))
    }

    @Test
    fun `garbage string returns Fill`() {
        assertEquals(ParsedDimension.Fill, parseDimension("banana"))
    }

    @Test
    fun `boolean returns Fill`() {
        assertEquals(ParsedDimension.Fill, parseDimension(true))
    }

    @Test
    fun `list returns Fill`() {
        assertEquals(ParsedDimension.Fill, parseDimension(listOf(300)))
    }

    @Test
    fun `negative percentage string returns Fill`() {
        assertEquals(ParsedDimension.Fill, parseDimension("-50%"))
    }

    @Test
    fun `zero percent string returns Fill`() {
        assertEquals(ParsedDimension.Fill, parseDimension("0%"))
    }

    @Test
    fun `over 100 percent string returns Fill`() {
        assertEquals(ParsedDimension.Fill, parseDimension("150%"))
    }

    // ── clampMinWidth ─────────────────────────────────────────────────────────

    @Test
    fun `clampMinWidth raises Pixels below minimum`() {
        val parsed = ParsedDimension.Pixels(100.dp)
        assertEquals(ParsedDimension.Pixels(300.dp), parsed.clampMinWidth(300.dp))
    }

    @Test
    fun `clampMinWidth keeps Pixels at minimum`() {
        val parsed = ParsedDimension.Pixels(300.dp)
        assertEquals(ParsedDimension.Pixels(300.dp), parsed.clampMinWidth(300.dp))
    }

    @Test
    fun `clampMinWidth keeps Pixels above minimum`() {
        val parsed = ParsedDimension.Pixels(500.dp)
        assertEquals(ParsedDimension.Pixels(500.dp), parsed.clampMinWidth(300.dp))
    }

    @Test
    fun `clampMinWidth passes Fill through`() {
        assertEquals(ParsedDimension.Fill, ParsedDimension.Fill.clampMinWidth(300.dp))
    }

    @Test
    fun `clampMinWidth passes Percentage through`() {
        val parsed = ParsedDimension.Percentage(0.1f)
        assertEquals(ParsedDimension.Percentage(0.1f), parsed.clampMinWidth(300.dp))
    }

    // ── End-to-end: parse + clamp ─────────────────────────────────────────────

    @Test
    fun `100px clamped to 300dp`() {
        assertEquals(
            ParsedDimension.Pixels(300.dp),
            parseDimension("100px").clampMinWidth(300.dp),
        )
    }

    @Test
    fun `int 100 clamped to 300dp`() {
        assertEquals(
            ParsedDimension.Pixels(300.dp),
            parseDimension(100).clampMinWidth(300.dp),
        )
    }

    @Test
    fun `200dp clamped to 300dp`() {
        assertEquals(
            ParsedDimension.Pixels(300.dp),
            parseDimension(200.dp).clampMinWidth(300.dp),
        )
    }

    @Test
    fun `float 0_1 not clamped — percentage passes through`() {
        assertEquals(
            ParsedDimension.Percentage(0.1f),
            parseDimension(0.1f).clampMinWidth(300.dp),
        )
    }

    @Test
    fun `400px stays 400dp after clamp`() {
        assertEquals(
            ParsedDimension.Pixels(400.dp),
            parseDimension("400px").clampMinWidth(300.dp),
        )
    }
}
