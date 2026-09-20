package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.network.ClickInteractionGate
import ad.simula.ad.sdk.network.ClickSources
import ad.simula.ad.sdk.network.PrimaryCtaRoute
import ad.simula.ad.sdk.model.RewardCompletionReason
import ad.simula.ad.sdk.model.monotonicRewardCompletionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardedNavigationPolicyTest {
    @Test
    fun `early complete before readiness latches across recreation and consumes once`() {
        val retained = RewardedEarlyCompleteState()

        assertFalse(retained.signal(creativeReady = false))
        assertEquals(RewardedEarlyCompleteStatus.PENDING, retained.status)

        // The presentation-owned instance is retained while the Activity is recreated.
        val recreated = retained
        assertTrue(recreated.consumePending(creativeReady = true))
        assertEquals(RewardedEarlyCompleteStatus.CONSUMED, recreated.status)
        assertFalse(recreated.consumePending(creativeReady = true))
        assertFalse(recreated.signal(creativeReady = true))
    }

    @Test
    fun `early complete after readiness earns immediately and duplicate events no-op`() {
        val state = RewardedEarlyCompleteState()

        assertTrue(state.signal(creativeReady = true))
        assertEquals(RewardedEarlyCompleteStatus.CONSUMED, state.status)
        assertFalse(state.signal(creativeReady = true))
    }

    @Test
    fun `creative failure discards pending early complete permanently`() {
        val state = RewardedEarlyCompleteState()
        assertFalse(state.signal(creativeReady = false))

        state.discard()

        assertEquals(RewardedEarlyCompleteStatus.DISCARDED, state.status)
        assertFalse(state.consumePending(creativeReady = true))
        assertFalse(state.signal(creativeReady = true))
    }

    @Test
    fun `pending early complete wins zero-gate ordering and video bridge is inapplicable`() {
        val state = RewardedEarlyCompleteState()
        assertFalse(state.signal(creativeReady = false))
        var reason: RewardCompletionReason? = null
        if (state.consumePending(creativeReady = true)) {
            reason = monotonicRewardCompletionReason(reason, RewardCompletionReason.CREATIVE_COMPLETED)
        }
        reason = monotonicRewardCompletionReason(reason, RewardCompletionReason.DURATION_ELAPSED)

        assertEquals(RewardCompletionReason.CREATIVE_COMPLETED, reason)
        assertTrue(rewardedEarlyCompleteApplicable(isVideo = false))
        assertFalse(rewardedEarlyCompleteApplicable(isVideo = true))
    }

    @Test
    fun `rewarded video executes admitted route without reclassification and click admission stays once`() {
        val admitted = PrimaryCtaRoute(
            tappedUrl = "https://advertiser.example/original",
            externalTarget = "resolved-route://opaque",
            externalTargetIsTracker = false,
        )
        assertSame(admitted, rewardedVideoCtaExecutionRoute(admitted))
        assertEquals(
            CreativeCtaRouter.PrimaryCtaTapPlan.ConsumeWithoutClick,
            CreativeCtaRouter.primaryCtaTapPlan(
                tappedUrl = admitted.externalTarget,
                creativeBaseUrl = null,
                trackingUrl = null,
                destination = "appstore",
            ),
        )

        val gate = ClickInteractionGate(clockMs = { 1L }, idFactory = { "video-click" })
        val claim = gate.claim(ClickSources.PRIMARY_CTA)
        assertNull(gate.claim(ClickSources.PRIMARY_CTA))
        assertEquals("video-click", claim?.interaction?.id)
    }

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
    fun `playable failure fails open only after a visible commit and evidence survives recreation`() {
        val presentation = RewardedPresentation(
            creative = ad.simula.ad.sdk.model.Creative(),
            impressionId = "serve",
            apiKey = "key",
            callbacks = NoOpRewardedCallbacks,
        )
        assertEquals(
            false,
            rewardEarnedAfterCreativeFailure(
                isVideo = false,
                everCreativeReady = presentation.everCreativeReady,
                candidate = false,
                retained = false,
            ),
        )
        presentation.everCreativeReady = true
        val recreated = presentation
        assertEquals(
            true,
            rewardEarnedAfterCreativeFailure(
                isVideo = false,
                everCreativeReady = recreated.everCreativeReady,
                candidate = false,
                retained = false,
            ),
        )
        assertEquals(
            true,
            rewardEarnedAfterCreativeFailure(
                isVideo = false,
                everCreativeReady = false,
                candidate = true,
                retained = false,
            ),
        )
        assertEquals(
            true,
            rewardEarnedAfterCreativeFailure(
                isVideo = false,
                everCreativeReady = false,
                candidate = false,
                retained = true,
            ),
        )
    }

    @Test
    fun `video failure preserves only rewards already earned by playback`() {
        assertEquals(
            false,
            rewardEarnedAfterCreativeFailure(
                isVideo = true,
                everCreativeReady = true,
                candidate = false,
                retained = false,
            ),
        )
        assertEquals(
            true,
            rewardEarnedAfterCreativeFailure(
                isVideo = true,
                everCreativeReady = true,
                candidate = true,
                retained = false,
            ),
        )
        assertEquals(
            true,
            rewardEarnedAfterCreativeFailure(
                isVideo = true,
                everCreativeReady = true,
                candidate = false,
                retained = true,
            ),
        )
    }

    private object NoOpRewardedCallbacks : RewardedCallbacks {
        override fun onDisplayed() = Unit
        override fun onImpression() = Unit
        override fun onPaid(adValue: ad.simula.ad.sdk.model.AdValue) = Unit
        override fun persistClick(
            interaction: ad.simula.ad.sdk.network.ClickInteraction,
            onTelemetryPersisted: () -> Unit,
        ) = onTelemetryPersisted()
        override fun notifyClicked() = Unit
        override fun onClose(earned: Boolean, elapsedPlayTimeSeconds: Double) = Unit
        override fun onRewardCompleted(
            earned: Boolean,
            elapsedPlayTimeSeconds: Double,
            completionReason: RewardCompletionReason?,
        ) = Unit
    }

    @Test
    fun `rotation retains dismissal admission without admitting replacement display`() {
        assertEquals(false, rewardedDismissalDisplayAdmitted(false, previouslyDisplayed = false))
        assertEquals(true, rewardedDismissalDisplayAdmitted(false, previouslyDisplayed = true))
        assertEquals(true, rewardedDismissalDisplayAdmitted(true, previouslyDisplayed = false))
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
    fun `gestured subframe advertiser exit routes as user CTA`() {
        assertEquals(
            RewardedNavigationAction.RouteUserCta,
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
    fun `gestureless subframe tracker routes automatically without becoming user CTA`() {
        val tracker = "https://tracker.example/click?id=abc"

        assertEquals(
            RewardedNavigationAction.RouteAutomatic(tracker),
            rewardedNavigationAction(
                isMainFrame = false,
                hasGesture = false,
                targetUrl = tracker,
                currentPageUrl = "about:blank",
                initialPageUrl = null,
                trackingUrl = tracker,
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
