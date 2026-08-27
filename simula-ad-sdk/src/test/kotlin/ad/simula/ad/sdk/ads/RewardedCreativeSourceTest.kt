package ad.simula.ad.sdk.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RewardedCreativeSourceTest {
    @Test
    fun `rendered HTML always wins over iframe URL`() {
        assertEquals(
            RewardedCreativeSource.Html("<html>primary</html>"),
            rewardedCreativeSource(
                renderedHtml = "<html>primary</html>",
                iframeUrl = "https://example.com/fallback",
            ),
        )
    }

    @Test
    fun `iframe URL is used only when rendered HTML is absent or blank`() {
        val expected = RewardedCreativeSource.Iframe("https://example.com/fallback")

        assertEquals(expected, rewardedCreativeSource(null, expected.url))
        assertEquals(expected, rewardedCreativeSource("  \n\t", expected.url))
    }

    @Test
    fun `missing HTML and iframe has no renderable source`() {
        assertNull(rewardedCreativeSource(null, null))
        assertNull(rewardedCreativeSource(" ", " "))
    }
}
