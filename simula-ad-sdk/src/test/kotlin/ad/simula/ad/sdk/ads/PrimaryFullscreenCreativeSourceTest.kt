package ad.simula.ad.sdk.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrimaryFullscreenCreativeSourceTest {
    @Test
    fun `rendered HTML always wins over iframe URL`() {
        assertEquals(
            PrimaryFullscreenCreativeSource.Html("<html>primary</html>"),
            primaryFullscreenCreativeSource(
                renderedHtml = "<html>primary</html>",
                iframeUrl = "https://example.com/fallback",
            ),
        )
    }

    @Test
    fun `iframe URL is used only when rendered HTML is absent or blank`() {
        val expected = PrimaryFullscreenCreativeSource.Iframe("https://example.com/fallback")

        assertEquals(expected, primaryFullscreenCreativeSource(null, expected.url))
        assertEquals(expected, primaryFullscreenCreativeSource("  \n\t", expected.url))
    }

    @Test
    fun `missing HTML and iframe has no renderable source`() {
        assertNull(primaryFullscreenCreativeSource(null, null))
        assertNull(primaryFullscreenCreativeSource(" ", " "))
    }
}
