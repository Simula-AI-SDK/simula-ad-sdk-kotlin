package ad.simula.ad.sdk.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `creative commit requires armed expected source without main frame failure`() {
        val html = RewardedCreativeSource.Html("<html/>")
        val iframe = RewardedCreativeSource.Iframe("https://creative.example/game")

        assertFalse(isQualifiedRewardedCreativeCommit(html, false, false, "about:blank"))
        assertFalse(isQualifiedRewardedCreativeCommit(html, true, true, "about:blank"))
        assertTrue(isQualifiedRewardedCreativeCommit(html, true, false, "about:blank"))
        assertFalse(isQualifiedRewardedCreativeCommit(iframe, true, false, "about:blank"))
        assertTrue(isQualifiedRewardedCreativeCommit(iframe, true, false, "https://other.example/game"))
        assertFalse(
            isQualifiedRewardedCreativeCommit(
                iframe,
                true,
                false,
                "https://sdk.simula.invalid/webview-reset/1",
            ),
        )
        assertTrue(isQualifiedRewardedCreativeCommit(iframe, true, false, "https://creative.example/ready"))
        val preview = RewardedCreativeSource.Iframe("data:text/html,<html>preview</html>")
        assertTrue(isQualifiedRewardedCreativeCommit(preview, true, false, preview.url))

        assertFalse(isQualifiedRewardedHtmlVisualState(html, loadArmed = false, mainFrameFailed = false))
        assertFalse(isQualifiedRewardedHtmlVisualState(html, loadArmed = true, mainFrameFailed = true))
        assertTrue(isQualifiedRewardedHtmlVisualState(html, loadArmed = true, mainFrameFailed = false))
        assertFalse(isQualifiedRewardedHtmlVisualState(iframe, loadArmed = true, mainFrameFailed = false))
    }
}
