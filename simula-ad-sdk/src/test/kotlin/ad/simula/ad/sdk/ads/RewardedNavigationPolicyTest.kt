package ad.simula.ad.sdk.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardedNavigationPolicyTest {
    @Test
    fun `automatic main-frame redirects always stay in WebView`() {
        listOf(
            "https://creative.example/game",
            "https://creative.example:8443/game",
            "https://cdn.example/game",
            "https://advertiser.example/landing",
        ).forEach { target ->
            assertFalse(
                shouldRouteRewardedNavigationAsUserCta(
                    isMainFrame = true,
                    hasGesture = false,
                    targetUrl = target,
                    currentPageUrl = "http://creative.example/game",
                    logicalCtaBaseUrl = "http://creative.example/game",
                ),
            )
            assertFalse(
                shouldRouteRewardedNavigationAsUserCta(
                    isMainFrame = true,
                    hasGesture = false,
                    targetUrl = target,
                    currentPageUrl = null,
                    logicalCtaBaseUrl = null,
                ),
            )
        }
    }

    @Test
    fun `only gestured cross-origin main-frame navigation is CTA`() {
        assertFalse(
            shouldRouteRewardedNavigationAsUserCta(
                true,
                true,
                "https://creative.example/next",
                "https://creative.example/game",
                "http://stale.example/game",
            ),
        )
        assertTrue(
            shouldRouteRewardedNavigationAsUserCta(
                true,
                true,
                "https://advertiser.example/offer",
                "https://creative.example/game",
                "https://creative.example/game",
            ),
        )
        assertTrue(
            shouldRouteRewardedNavigationAsUserCta(
                true,
                true,
                "https://creative.example:8443/offer",
                "https://creative.example/game",
                "https://creative.example/game",
            ),
        )
    }

    @Test
    fun `subframe navigation never becomes CTA`() {
        assertFalse(
            shouldRouteRewardedNavigationAsUserCta(
                false,
                true,
                "https://advertiser.example/offer",
                "https://creative.example/game",
                "https://creative.example/game",
            ),
        )
    }
}
