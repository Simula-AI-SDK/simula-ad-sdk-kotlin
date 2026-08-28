package ad.simula.ad.sdk.ads

import org.junit.Assert.assertEquals
import org.junit.Test

class RewardedNavigationPolicyTest {
    @Test
    fun `zero gate reward waits for usable creative bridge`() {
        assertEquals(false, initialRewardEarned(false, accumulatedPlayTimeMs = 0L, gateSeconds = 0))
        assertEquals(true, initialRewardEarned(true, accumulatedPlayTimeMs = 0L, gateSeconds = 0))
        assertEquals(true, initialRewardEarned(false, accumulatedPlayTimeMs = 5_000L, gateSeconds = 5))
    }

    @Test
    fun `bridge failure cannot revoke an earned reward`() {
        assertEquals(false, monotonicRewardEarned(candidate = false, retained = false))
        assertEquals(true, monotonicRewardEarned(candidate = true, retained = false))
        assertEquals(true, monotonicRewardEarned(candidate = false, retained = true))
        assertEquals(true, monotonicRewardEarned(candidate = true, retained = true))
    }

    @Test
    fun `terminal creative failure fails open only after rewarded creative becomes visible`() {
        assertEquals(
            false,
            rewardEarnedAfterCreativeFailure(everCreativeReady = false, candidate = false, retained = false),
        )
        assertEquals(
            true,
            rewardEarnedAfterCreativeFailure(everCreativeReady = true, candidate = false, retained = false),
        )
        assertEquals(
            true,
            rewardEarnedAfterCreativeFailure(everCreativeReady = false, candidate = true, retained = false),
        )
        assertEquals(
            true,
            rewardEarnedAfterCreativeFailure(everCreativeReady = false, candidate = false, retained = true),
        )
    }

    @Test
    fun `ordinary automatic redirects stay in WebView`() {
        listOf(
            "https://creative.example/game",
            "https://creative.example:8443/game",
            "https://cdn.example/game",
            "https://advertiser.example/landing",
        ).forEach { target ->
            assertEquals(
                RewardedNavigationAction.AllowInWebView,
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
                RewardedNavigationAction.RouteAutomatic(
                    CreativeCtaRouter.normalizeTappedDestination(target) ?: target,
                ),
                rewardedNavigationAction(true, false, target, "https://creative.example/game", null),
            )
        }
        assertEquals(
            RewardedNavigationAction.Consume,
            rewardedNavigationAction(true, false, "intent://details#Intent;scheme=market;end", null, null),
        )
        assertEquals(
            RewardedNavigationAction.RouteAutomatic("partner-app://offer"),
            rewardedNavigationAction(
                true,
                false,
                "partner-app://offer",
                null,
                null,
                destination = "web",
            ),
        )
        assertEquals(
            RewardedNavigationAction.Consume,
            rewardedNavigationAction(
                true,
                false,
                "partner-app://offer",
                null,
                null,
                destination = "appstore",
            ),
        )
    }

    @Test
    fun `only gestured cross-origin main-frame navigation is user CTA`() {
        assertEquals(
            RewardedNavigationAction.AllowInWebView,
            rewardedNavigationAction(
                true,
                true,
                "https://creative.example/next",
                "https://creative.example/game",
                "http://stale.example/game",
            ),
        )
        assertEquals(
            RewardedNavigationAction.RouteUserCta,
            rewardedNavigationAction(
                true,
                true,
                "https://advertiser.example/offer",
                "https://creative.example/game",
                "https://creative.example/game",
            ),
        )
        assertEquals(
            RewardedNavigationAction.RouteUserCta,
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
            RewardedNavigationAction.RouteUserCta,
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
            RewardedNavigationAction.AllowInWebView,
            rewardedNavigationAction(
                false,
                true,
                "https://advertiser.example/offer",
                "https://creative.example/game",
                "https://creative.example/game",
            ),
        )
    }

    @Test
    fun `gestureless MMP tracker and Play redirect route externally without becoming user CTA`() {
        val tracker = "https://tracker.example/click?id=abc%2B123"
        val play = "https://play.google.com/store/apps/details?id=com.example.app&referrer=click%3Dabc%252B123"

        assertEquals(
            RewardedNavigationAction.RouteAutomatic(tracker),
            rewardedNavigationAction(
                true,
                false,
                tracker,
                "https://creative.example/game",
                null,
                trackingUrl = tracker,
            ),
        )
        assertEquals(
            RewardedNavigationAction.RouteAutomatic(play),
            rewardedNavigationAction(
                true,
                false,
                play,
                tracker,
                null,
                trackingUrl = tracker,
            ),
        )
    }
}
