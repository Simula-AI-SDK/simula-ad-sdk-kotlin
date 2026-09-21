package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.CreativeType
import ad.simula.ad.sdk.model.VideoAudioSessionState
import ad.simula.ad.sdk.model.VideoChromeStyle
import ad.simula.ad.sdk.model.VideoSequenceAdvance
import ad.simula.ad.sdk.model.VideoStallBudget
import ad.simula.ad.sdk.model.VideoAudioWatchAccounting
import ad.simula.ad.sdk.model.VideoInstallOverlayClock
import ad.simula.ad.sdk.model.VideoQuartileTracker
import ad.simula.ad.sdk.model.SkOverlayConfig
import ad.simula.ad.sdk.model.resolvedVideoChromeStyle
import ad.simula.ad.sdk.model.storePromptHalfGateReached
import ad.simula.ad.sdk.model.videoSequenceAdvance
import ad.simula.ad.sdk.model.videoStorePromptReached
import ad.simula.ad.sdk.model.effectiveVideoMuted
import ad.simula.ad.sdk.model.initialVideoDesiredMuted
import ad.simula.ad.sdk.model.videoDesiredMutedAfterTap
import ad.simula.ad.sdk.model.videoDesiredMutedAfterLifecycleDeactivation
import ad.simula.ad.sdk.network.SimulaApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlanV2PolicyTest {
    private fun playable(sourceIndex: Int) = SimulaApiClient.FallbackAd(
        adId = "p$sourceIndex",
        sourceIndex = sourceIndex,
        type = CreativeType.PLAYABLE,
        renderedHtml = "<html/>",
    )

    private fun video(sourceIndex: Int, v2: Boolean = true) = SimulaApiClient.FallbackAd(
        adId = "v$sourceIndex",
        sourceIndex = sourceIndex,
        type = CreativeType.VIDEO,
        url = "https://cdn.example/$sourceIndex.mp4",
        videoPool = "ugc".takeIf { v2 },
        clipIndex = sourceIndex.takeIf { v2 },
    )

    @Test
    fun `all playable golden progression remains manual and preserves source indices`() {
        var primaryClosed = false
        val ads = listOf(playable(0), playable(1))
        val state = FallbackPresentationState(clockMs = { 0L })
        state.retainFetchedAds(ads)

        assertFalse(primaryClosed)
        primaryClosed = true
        state.showing(0)

        assertTrue(primaryClosed)
        assertEquals(0, ads[state.index].sourceIndex)
        assertEquals(VideoSequenceAdvance.MANUAL, videoSequenceAdvance(false, CreativeType.PLAYABLE, true, false, false))
        assertTrue(state.advance(ads.size))
        assertEquals(1, ads[state.index].sourceIndex)
        assertTrue(state.advance(ads.size))
        assertEquals(FallbackStage.DONE, state.stage)
    }

    @Test
    fun `video v1 completion remains manual`() {
        assertEquals(
            VideoSequenceAdvance.MANUAL,
            videoSequenceAdvance(false, CreativeType.VIDEO, terminal = true, false, false),
        )
    }

    @Test
    fun `pool metadata alone does not opt legacy video into v2`() {
        val legacy = video(0, v2 = false).copy(videoPool = "ugc")
        assertFalse(legacy.isVideoPlanV2)
    }

    @Test
    fun `v2 terminal videos advance after blockers and hand off to playable`() {
        val ads = listOf(video(0), video(1), playable(2))
        val state = FallbackPresentationState(clockMs = { 0L }, videoPlanV2 = true)
        state.retainFetchedAds(ads)
        state.showing(0)

        assertEquals(
            VideoSequenceAdvance.WAIT_FOR_BLOCKER,
            videoSequenceAdvance(true, CreativeType.VIDEO, terminal = true, clickHandoffPending = true, false),
        )
        assertEquals(
            VideoSequenceAdvance.WAIT_FOR_BLOCKER,
            videoSequenceAdvance(true, CreativeType.VIDEO, terminal = true, false, storeVisitPending = true),
        )
        assertEquals(
            VideoSequenceAdvance.ADVANCE,
            videoSequenceAdvance(true, CreativeType.VIDEO, terminal = true, false, false),
        )
        assertTrue(state.advance(ads.size))
        assertEquals(CreativeType.VIDEO, ads[state.index].type)
        assertTrue(state.advance(ads.size))
        assertEquals(CreativeType.PLAYABLE, ads[state.index].type)
        assertEquals(VideoSequenceAdvance.MANUAL, videoSequenceAdvance(true, CreativeType.PLAYABLE, true, false, false))
    }

    @Test
    fun `v2 preparation selects only immediate consecutive video`() {
        assertEquals("https://cdn.example/1.mp4", nextVideoPlanV2Url(listOf(video(0), video(1)), 0))
        assertNull(nextVideoPlanV2Url(listOf(video(0), playable(1), video(2)), 0))
        assertNull(nextVideoPlanV2Url(listOf(video(0), video(1, v2 = false)), 0))
    }

    @Test
    fun `each style enforces its exact identity requirements`() {
        for (style in listOf(VideoChromeStyle.BOTTOM_BAR, VideoChromeStyle.FLOATING_PILL)) {
            assertEquals(VideoChromeStyle.CORNER_CTA, resolvedVideoChromeStyle(style, "Game", null))
            assertEquals(style, resolvedVideoChromeStyle(style, null, "https://cdn/icon.png"))
        }
        for (style in listOf(VideoChromeStyle.BOTTOM_CARD, VideoChromeStyle.FEED_CARD)) {
            assertEquals(VideoChromeStyle.CORNER_CTA, resolvedVideoChromeStyle(style, "Game", null))
            assertEquals(VideoChromeStyle.CORNER_CTA, resolvedVideoChromeStyle(style, null, "https://cdn/icon.png"))
            assertEquals(style, resolvedVideoChromeStyle(style, "Game", "https://cdn/icon.png"))
        }
        assertEquals(VideoChromeStyle.CORNER_CTA, resolvedVideoChromeStyle(VideoChromeStyle.CORNER_CTA, null, null))
    }

    @Test
    fun `store prompt uses half effective gate`() {
        assertFalse(storePromptHalfGateReached(4_999L, 10_000L))
        assertTrue(storePromptHalfGateReached(5_000L, 10_000L))
        assertFalse(storePromptHalfGateReached(1L, 0L))
    }

    @Test
    fun `v1 store prompt stays at asset midpoint while v2 uses effective gate midpoint`() {
        assertFalse(videoStorePromptReached(false, 4_000L, 20_000L, 5_000L, 10_000L))
        assertTrue(videoStorePromptReached(false, 10_000L, 20_000L, 0L, 10_000L))
        assertTrue(videoStorePromptReached(true, 4_000L, 20_000L, 5_000L, 10_000L))
    }

    @Test
    fun `v2 desired mute is tap-owned and independent of effective watch accounting`() {
        val audio = VideoAudioSessionState(videoPlanV2 = true)
        assertFalse(audio.desiredMuted)
        audio.updateFromTap(videoPlanV2 = true, value = true)
        audio.activateVideoPlanV2()
        assertTrue(audio.desiredMuted)
    }

    @Test
    fun `audio watch accounting uses media advance instead of wall time`() {
        val watch = VideoAudioWatchAccounting()
        watch.add(1_250L, muted = false)
        watch.add(500L, muted = true)
        watch.add(-100L, muted = true)

        assertEquals(1_250L, watch.totals().unmutedMs)
        assertEquals(500L, watch.totals().mutedMs)
    }

    @Test
    fun `effective focus mute restores the user desired state on gain`() {
        assertTrue(effectiveVideoMuted(desiredMuted = false, audioFocusHeld = false))
        assertFalse(effectiveVideoMuted(desiredMuted = false, audioFocusHeld = true))
        assertTrue(effectiveVideoMuted(desiredMuted = true, audioFocusHeld = true))
    }

    @Test
    fun `focus denial mute tap retries unmute without storing mute`() {
        val audio = VideoAudioSessionState(videoPlanV2 = true)
        val effectiveMuted = effectiveVideoMuted(audio.desiredMuted, audioFocusHeld = false)

        assertTrue(effectiveMuted)
        audio.updateFromTap(
            videoPlanV2 = true,
            value = videoDesiredMutedAfterTap(effectiveMuted),
        )

        assertFalse(audio.desiredMuted)
        assertTrue(effectiveVideoMuted(audio.desiredMuted, audioFocusHeld = false))
        assertFalse(effectiveVideoMuted(audio.desiredMuted, audioFocusHeld = true))
    }

    @Test
    fun `stall budget pauses off foreground resets on progress and fails stalled buffering`() {
        val budget = VideoStallBudget(8_000L)
        assertFalse(budget.observe(0L, eligible = true, healthyProgress = false))
        assertFalse(budget.observe(4_000L, eligible = true, healthyProgress = false))
        assertFalse(budget.observe(20_000L, eligible = false, healthyProgress = false))
        assertFalse(budget.observe(21_000L, eligible = true, healthyProgress = true))
        assertEquals(8_000L, budget.remainingMs())
        assertFalse(budget.observe(22_000L, eligible = true, healthyProgress = false))
        assertTrue(budget.observe(30_000L, eligible = true, healthyProgress = false))
    }

    @Test
    fun `quartiles fire 25 50 75 once even across jumps`() {
        val tracker = VideoQuartileTracker()
        assertEquals(listOf(25), tracker.crossed(2_500L, 10_000L))
        assertEquals(listOf(50, 75), tracker.crossed(8_000L, 10_000L))
        assertTrue(tracker.crossed(10_000L, 10_000L).isEmpty())
    }

    @Test
    fun `install overlay clock starts once and counts only eligible presentation time`() {
        val clock = VideoInstallOverlayClock()
        clock.start(delaySeconds = 3, nowMs = 0L, eligible = true)
        assertFalse(clock.update(1_000L, eligible = true))
        assertFalse(clock.update(10_000L, eligible = false))
        assertFalse(clock.update(11_000L, eligible = true))
        assertTrue(clock.update(13_000L, eligible = true))
        clock.start(delaySeconds = 60, nowMs = 14_000L, eligible = true)
        assertTrue(clock.ready)
    }

    @Test
    fun `handoff retains originating first frame time and targets next step`() {
        var now = 1_000L
        val state = VideoPlanPresentationState(videoPlanV2 = true, clockMs = { now })
        val telemetry = VideoPlaybackTelemetry(
            context = VideoTelemetryContext(
                adFormat = "rewarded",
                adUnitId = "unit",
                adId = "ad",
                serveId = "serve",
                impressionId = "serve",
                style = "corner_cta",
                skoverlayEnabled = false,
                skoverlayDelaySeconds = 3,
                clipIndex = 0,
                pool = "ugc",
            ),
            videoPositionS = 4.0,
            muted = false,
            durationS = 10.0,
            watchedS = 4.0,
            secondsUnmuted = 4.0,
            secondsMuted = 0.0,
        )
        state.firstVideoFrame(SkOverlayConfig(enabled = false, delaySeconds = 3))
        now = 5_000L
        state.beginHandoff(telemetry)
        now = 5_300L

        val timing = requireNotNull(state.nextStepReady())
        assertEquals(300.0, timing.msToNextStepReady, 0.0)
        assertEquals(4.3, timing.secondsSinceVideoStart, 0.0)
        assertEquals("next_step", timing.on)
        assertTrue(state.overlayReady())
    }

    @Test
    fun `primary v2 handoff reads live fallback knowledge at terminal time`() {
        val state = FallbackPresentationState(videoPlanV2 = true)
        val hasNextStep = {
            primaryVideoWillHandoff(videoPlanV2 = true, fallbackAds = state.fetchedAds)
        }

        assertTrue(shouldBeginVideoHandoff(videoPlanV2 = true, hasNextStep))

        state.retainFetchedAds(emptyList())
        assertFalse(shouldBeginVideoHandoff(videoPlanV2 = true, hasNextStep))

        assertTrue(primaryVideoWillHandoff(videoPlanV2 = true, fallbackAds = listOf(playable(0))))
        assertFalse(primaryVideoWillHandoff(videoPlanV2 = false, fallbackAds = null))
    }

    @Test
    fun `accepted delayed playable fallback reports first step ready immediately`() {
        val state = pendingFallbackHandoffState()
        val generation = state.startPostCloseFetchWait()

        assertTrue(state.resolvePostCloseFetchWait(generation, listOf(playable(0))))
        assertNull(state.videoPlan.nextStepReady())
    }

    @Test
    fun `accepted delayed video fallback waits for its first frame readiness`() {
        val state = pendingFallbackHandoffState()
        val generation = state.startPostCloseFetchWait()

        assertTrue(state.resolvePostCloseFetchWait(generation, listOf(video(0))))
        assertTrue(state.videoPlan.nextStepReady() != null)
    }

    @Test
    fun `late delayed fallback after timeout does not report readiness`() {
        val state = pendingFallbackHandoffState()
        val generation = state.startPostCloseFetchWait()

        assertTrue(state.timeoutPostCloseFetchWait(generation))
        assertFalse(state.resolvePostCloseFetchWait(generation, listOf(playable(0))))
        assertTrue(state.videoPlan.nextStepReady() != null)
    }

    @Test
    fun `v1 backgrounding permanently remutes without changing v2 presentation preference`() {
        val presentationAudio = VideoAudioSessionState(videoPlanV2 = true)
        val v1InitialMuted = initialVideoDesiredMuted(
            videoPlanV2 = false,
            presentationDesiredMuted = presentationAudio.desiredMuted,
        )
        assertTrue(v1InitialMuted)

        presentationAudio.updateFromTap(videoPlanV2 = false, value = true)
        assertFalse(presentationAudio.desiredMuted)

        val v1Unmuted = false
        val v1AfterBackground = videoDesiredMutedAfterLifecycleDeactivation(
            videoPlanV2 = false,
            desiredMuted = v1Unmuted,
        )
        assertTrue(v1AfterBackground)
        assertTrue(effectiveVideoMuted(v1AfterBackground, audioFocusHeld = true))
    }

    @Test
    fun `v2 desired unmuted preference survives background and clip handoff`() {
        val presentationAudio = VideoAudioSessionState(videoPlanV2 = true)
        presentationAudio.updateFromTap(videoPlanV2 = true, value = false)

        val afterBackground = videoDesiredMutedAfterLifecycleDeactivation(
            videoPlanV2 = true,
            desiredMuted = presentationAudio.desiredMuted,
        )
        assertFalse(afterBackground)
        assertTrue(effectiveVideoMuted(afterBackground, audioFocusHeld = false))
        assertFalse(effectiveVideoMuted(afterBackground, audioFocusHeld = true))
        assertFalse(
            initialVideoDesiredMuted(
                videoPlanV2 = true,
                presentationDesiredMuted = presentationAudio.desiredMuted,
            ),
        )
    }

    private fun pendingFallbackHandoffState(): FallbackPresentationState {
        val state = FallbackPresentationState(clockMs = { 0L }, videoPlanV2 = true)
        state.videoPlan.beginHandoff(
            VideoPlaybackTelemetry(
                context = VideoTelemetryContext(
                    adFormat = "interstitial",
                    adUnitId = "unit",
                    adId = "ad",
                    serveId = "serve",
                    impressionId = "serve",
                    style = "corner_cta",
                    skoverlayEnabled = false,
                    skoverlayDelaySeconds = 3,
                    clipIndex = 0,
                    pool = "ugc",
                ),
                videoPositionS = 1.0,
                muted = false,
                durationS = 2.0,
                watchedS = 1.0,
                secondsUnmuted = 1.0,
                secondsMuted = 0.0,
            ),
        )
        return state
    }
}
