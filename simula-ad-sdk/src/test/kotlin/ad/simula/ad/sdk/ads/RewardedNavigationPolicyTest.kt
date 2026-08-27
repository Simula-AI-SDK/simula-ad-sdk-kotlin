package ad.simula.ad.sdk.ads

import org.junit.Assert.assertEquals
import org.junit.Test

class RewardedNavigationPolicyTest {
    @Test
    fun `ordinary automatic redirects stay in WebView`() {
        listOf(
            "https://creative.example/game",
            "https://creative.example:8443/game",
            "https://cdn.example/game",
            "https://advertiser.example/landing",
        ).forEach { target ->
            assertEquals(
                RewardedNavigationAction.ALLOW_IN_WEBVIEW,
                rewardedNavigationAction(
                    isMainFrame = true,
                    hasGesture = false,
                    targetUrl = target,
                    currentPageUrl = "http://creative.example/game",
                    initialPageUrl = "http://creative.example/game",
                ),
            )
        }
    }

    @Test
    fun `valid automatic Android store exits route without becoming user CTAs`() {
        listOf(
            "market://details?id=com.example.app",
            "intent://details#Intent;scheme=market;" +
                "S.browser_fallback_url=https%3A%2F%2Fplay.google.com%2Fstore%2Fapps%2Fdetails%3Fid%3Dcom.example.app;end",
        ).forEach { target ->
            assertEquals(
                RewardedNavigationAction.ROUTE_AUTO_STORE,
                rewardedNavigationAction(true, false, target, "https://creative.example/game", null),
            )
        }
        assertEquals(
            RewardedNavigationAction.CONSUME,
            rewardedNavigationAction(true, false, "intent://details#Intent;scheme=market;end", null, null),
        )
    }

    @Test
    fun `only gestured cross-origin main-frame navigation is user CTA`() {
        assertEquals(
            RewardedNavigationAction.ALLOW_IN_WEBVIEW,
            rewardedNavigationAction(
                true,
                true,
                "https://creative.example/next",
                "https://creative.example/game",
                "http://stale.example/game",
            ),
        )
        assertEquals(
            RewardedNavigationAction.ROUTE_USER_CTA,
            rewardedNavigationAction(
                true,
                true,
                "https://advertiser.example/offer",
                "https://creative.example/game",
                "https://creative.example/game",
            ),
        )
        assertEquals(
            RewardedNavigationAction.ROUTE_USER_CTA,
            rewardedNavigationAction(
                true,
                true,
                "https://creative.example:8443/offer",
                "https://creative.example/game",
                "https://creative.example/game",
            ),
        )
    }

    @Test
    fun `opaque rendered HTML does not inherit unused iframe origin`() {
        assertEquals(
            RewardedNavigationAction.ROUTE_USER_CTA,
            rewardedNavigationAction(
                isMainFrame = true,
                hasGesture = true,
                targetUrl = "https://creative.example/offer",
                currentPageUrl = "data:text/html,creative",
                initialPageUrl = null,
            ),
        )
    }

    @Test
    fun `subframe navigation always stays in WebView`() {
        assertEquals(
            RewardedNavigationAction.ALLOW_IN_WEBVIEW,
            rewardedNavigationAction(
                false,
                true,
                "https://advertiser.example/offer",
                "https://creative.example/game",
                "https://creative.example/game",
            ),
        )
    }
}
