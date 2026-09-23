package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.AdBehavior
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.CloseTreatment
import ad.simula.ad.sdk.model.Creative
import ad.simula.ad.sdk.model.CreativeType
import ad.simula.ad.sdk.model.ProgressBarStyle
import ad.simula.ad.sdk.model.RewardBehavior
import ad.simula.ad.sdk.model.RewardEarnAt
import ad.simula.ad.sdk.model.RewardCompletionReason
import ad.simula.ad.sdk.model.VideoAudioSessionState
import ad.simula.ad.sdk.model.VideoLifecycleReason
import ad.simula.ad.sdk.model.VideoSegment
import ad.simula.ad.sdk.model.effectiveVideoMuted
import ad.simula.ad.sdk.model.videoAudioFocusLossPolicy
import ad.simula.ad.sdk.model.videoChromeObstructionClearance
import ad.simula.ad.sdk.model.VideoQuartileTracker
import ad.simula.ad.sdk.model.AdValue
import ad.simula.ad.sdk.model.primaryCreativeCloseAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle

@OptIn(ExperimentalCoroutinesApi::class)
class VideoContract2PolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `duration telemetry emits midpoint only`() {
        assertEquals("video_duration", VIDEO_STAGE_DURATION)
        val tracker = VideoQuartileTracker()
        assertTrue(tracker.crossed(2_500, 10_000).isEmpty())
        assertEquals(listOf(50), tracker.crossed(5_000, 10_000))
        assertTrue(tracker.crossed(7_500, 10_000).isEmpty())
    }

    @Test
    fun `all playable primary close paths bypass contract 2 video arbitration`() {
        var arbitrationCalls = 0
        repeat(2) {
            assertTrue(
                primaryCreativeCloseAllowed(
                    videoContract2 = true,
                    creativeType = CreativeType.PLAYABLE,
                    videoTerminal = false,
                ) {
                    arbitrationCalls++
                    false
                },
            )
        }
        assertEquals("close button and back both bypass video ownership", 0, arbitrationCalls)
    }

    @Test
    fun `fullscreen video exposes an immediate escape only before display admission`() {
        assertTrue(videoPreFirstFrameEscapeAvailable(isVideo = true, displayAdmitted = false))
        assertFalse(videoPreFirstFrameEscapeAvailable(isVideo = true, displayAdmitted = true))
        assertFalse(videoPreFirstFrameEscapeAvailable(isVideo = false, displayAdmitted = false))
    }

    @Test
    fun `fallback video escape is immediate before first frame and normal gate owns it after frame`() {
        assertTrue(videoPreFirstFrameEscapeAvailable(isVideo = true, displayAdmitted = false))
        assertFalse(
            "pre-frame escape must not wait for the configured fallback countdown",
            fallbackReachedAuthoritativeGate(
                isVideo = true,
                renderAdmitted = true,
                countdown = 5,
                videoTerminal = false,
            ),
        )

        assertFalse(videoPreFirstFrameEscapeAvailable(isVideo = true, displayAdmitted = true))
        assertTrue(fallbackReachedAuthoritativeGate(true, true, countdown = 0, videoTerminal = false))
    }

    @Test
    fun `fallback pre frame failure and escape share exactly once advance authority`() {
        val fallback = FallbackPresentationState(clockMs = { 0L }, videoPlanV2 = true)
        fallback.showing(0)
        val owner = Any()
        var authorityReports = 0

        assertTrue(fallback.abandonRenderer(0, owner))
        assertFalse(fallback.abandonRenderer(0, owner))
        assertTrue(fallback.reportAuthoritativeEnd { authorityReports++ })
        assertFalse(fallback.reportAuthoritativeEnd { authorityReports++ })
        assertEquals(1, authorityReports)
    }

    @Test
    fun `contract 2 primary video close still requires terminal arbitration`() {
        assertFalse(primaryCreativeCloseAllowed(true, CreativeType.VIDEO, false) { false })
        assertTrue(primaryCreativeCloseAllowed(true, CreativeType.VIDEO, false) { true })
        assertTrue(primaryCreativeCloseAllowed(true, CreativeType.VIDEO, true) { false })
    }

    @Test
    fun `positions at and beyond final segment end attribute the final segment`() {
        val segments = listOf(
            VideoSegment(0, "ugc", 0.0, 2.5),
            VideoSegment(1, "brand", 2.5, 5.0),
        )
        assertEquals(0, videoSegmentAtPosition(segments, 2_499L)?.clipIndex)
        assertEquals(1, videoSegmentAtPosition(segments, 2_500L)?.clipIndex)
        assertEquals(1, videoSegmentAtPosition(segments, 5_000L)?.clipIndex)
        assertEquals(1, videoSegmentAtPosition(segments, 99_000L)?.clipIndex)
    }

    @Test
    fun `two tone gate fraction is all bright without a meaningful split`() {
        assertEquals(1f, twoToneGateFraction(0, 10_000), 0f)
        assertEquals(1f, twoToneGateFraction(10, 10_000), 0f)
        assertEquals(0.4f, twoToneGateFraction(4, 10_000), 0f)
    }

    @Test
    fun `video cache names are opaque stable and bounded`() {
        val first = opaqueVideoAssetName("https://cdn.example/video.mp4?token=secret")
        val second = opaqueVideoAssetName("https://cdn.example/video.mp4?token=secret")
        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{64}\\.video")))
        assertTrue(!first.contains("token"))
    }

    @Test
    fun `active cache leases retain asset until the final release`() = runTest {
        val directory = temporaryFolder.newFolder("video-cache")
        val url = "https://cdn.example/video.mp4"
        val file = java.io.File(directory, opaqueVideoAssetName(url))
        file.writeBytes(byteArrayOf(1, 2, 3))
        val cache = VideoAssetCacheManager(
            directory = directory,
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
        )

        val first = requireNotNull(cache.acquire(url))
        val second = requireNotNull(cache.acquire(url))
        first.release()
        advanceUntilIdle()
        assertTrue(file.exists())
        second.release()
        advanceUntilIdle()
        assertTrue(!file.exists())
    }

    @Test
    fun `failed activity launch invalidates ready lease ownership`() {
        assertTrue(invalidateReadyLeaseAfterLaunch(launched = false))
        assertFalse(invalidateReadyLeaseAfterLaunch(launched = true))
    }

    @Test
    fun `unit end authority survives two activity generations and claims once`() {
        val presentation = unitEndPresentation()

        val firstGeneration = presentation.markPrimaryProgressionAllowed()
        assertTrue(firstGeneration.newlyAllowed)
        assertNull(firstGeneration.completionClaim)
        assertFalse(presentation.rewardEarned)

        val firstClaim = presentation.markAuthoritativeEndReached()
        assertNull(firstClaim)
        assertTrue(presentation.rewardEarned)
        assertFalse(presentation.hasClaimedRewardCompletion())

        assertNull("recreated Activity cannot claim publisher callback again", presentation.markAuthoritativeEndReached())
        assertNull(presentation.markPrimaryProgressionAllowed().completionClaim)
        assertEquals(
            RewardCompletionClaim(true, ad.simula.ad.sdk.model.RewardCompletionReason.UNIT_END),
            presentation.claimRewardCompletion(),
        )
        assertNull(presentation.claimRewardCompletion())
        assertTrue(presentation.hasClaimedRewardCompletion())
    }

    @Test
    fun `primary gate permits progression but fallback authority is still required`() {
        val presentation = unitEndPresentation()

        val progression = presentation.markPrimaryProgressionAllowed()

        assertTrue(progression.newlyAllowed)
        assertNull(progression.completionClaim)
        assertFalse(presentation.rewardEarned)
        assertFalse(presentation.hasAuthoritativeEndReached())
    }

    @Test
    fun `authority arriving before primary gate is claimed when gate crosses`() {
        val presentation = unitEndPresentation()
        assertNull(presentation.markAuthoritativeEndReached())

        val claim = presentation.markPrimaryProgressionAllowed().completionClaim

        assertNull(claim)
        assertTrue(presentation.rewardEarned)
        assertEquals(
            ad.simula.ad.sdk.model.RewardCompletionReason.UNIT_END,
            presentation.claimRewardCompletion()?.reason,
        )
        assertNull(presentation.markPrimaryProgressionAllowed().completionClaim)
    }

    @Test
    fun `teardown without authoritative end never creates a reward claim`() {
        val presentation = unitEndPresentation()
        presentation.markPrimaryProgressionAllowed()

        assertFalse(presentation.rewardEarned)
        assertNull(presentation.claimRewardCompletion()?.reason)
        assertFalse(presentation.rewardEarned)
    }

    @Test
    fun `last fallback video is authoritative at gate or natural end`() {
        assertFalse(fallbackScreenIsAuthoritative(0, 2))
        assertTrue(fallbackScreenIsAuthoritative(1, 2))
        assertFalse(fallbackReachedAuthoritativeGate(true, true, countdown = 1, videoTerminal = false))
        assertTrue(fallbackReachedAuthoritativeGate(true, true, countdown = 0, videoTerminal = false))
        assertTrue(fallbackReachedAuthoritativeGate(true, true, countdown = 4, videoTerminal = true))
    }

    @Test
    fun `final playable that never commits cannot accrue or reach gate authority`() {
        val gates = FallbackCloseGateState()

        assertEquals(
            0L,
            gates.addPlayableElapsedMs(
                index = 1,
                elapsedMs = FALLBACK_CLOSE_GATE_MS,
                durationMs = FALLBACK_CLOSE_GATE_MS,
                renderAdmitted = false,
                foreground = true,
                clickHandoffPending = false,
                storeVisitPending = false,
            ),
        )
        assertFalse(
            fallbackReachedAuthoritativeGate(
                isVideo = false,
                renderAdmitted = false,
                countdown = 0,
                videoTerminal = false,
            ),
        )
    }

    @Test
    fun `playable fallback gate starts after late commit and pauses while blocked`() {
        val gates = FallbackCloseGateState()

        gates.addPlayableElapsedMs(1, 4_000L, 5_000L, false, true, false, false)
        assertEquals(0L, gates.elapsedMs(1))
        gates.addPlayableElapsedMs(1, 2_000L, 5_000L, true, true, false, false)
        assertEquals(2_000L, gates.elapsedMs(1))
        gates.addPlayableElapsedMs(1, 1_000L, 5_000L, true, false, false, false)
        gates.addPlayableElapsedMs(1, 1_000L, 5_000L, true, true, true, false)
        gates.addPlayableElapsedMs(1, 1_000L, 5_000L, true, true, false, true)
        assertEquals(2_000L, gates.elapsedMs(1))
        gates.addPlayableElapsedMs(1, 3_000L, 5_000L, true, true, false, false)
        assertEquals(5_000L, gates.elapsedMs(1))
        assertTrue(fallbackReachedAuthoritativeGate(false, true, 0, false))
    }

    @Test
    fun `post close handoff timeout is retained across activity generation`() {
        var now = 1_000L
        val state = FallbackPresentationState(clockMs = { now }, videoPlanV2 = true)
        val generation = state.startPostCloseFetchWait()
        assertEquals(FALLBACK_POST_CLOSE_WAIT_MS, state.postCloseFetchWaitRemainingMs(generation))

        now += FALLBACK_POST_CLOSE_WAIT_MS + 1L

        assertEquals(0L, state.postCloseFetchWaitRemainingMs(generation))
        assertTrue(state.timeoutPostCloseFetchWait(generation))
        assertFalse(state.timeoutPostCloseFetchWait(generation))
    }

    @Test
    fun `fallback failure and timeout each report authoritative end exactly once`() {
        var failureReports = 0
        val failure = FallbackPresentationState(clockMs = { 0L }, videoPlanV2 = true)
        failure.markPrimaryEndReached()
        failure.terminalizeInitialFetchFailure()
        assertTrue(failure.reportAuthoritativeEnd { failureReports++ })
        assertFalse(failure.reportAuthoritativeEnd { failureReports++ })
        assertEquals(1, failureReports)

        var timeoutReports = 0
        val timeout = FallbackPresentationState(clockMs = { 0L }, videoPlanV2 = true)
        timeout.startPostCloseFetchWait()
        assertTrue(timeout.reportAuthoritativeEnd { timeoutReports++ })
        assertFalse(timeout.reportAuthoritativeEnd { timeoutReports++ })
        assertEquals(1, timeoutReports)
    }

    @Test
    fun `failed final playable resolves unit end through unavailable authority exactly once`() {
        val presentation = unitEndPresentation()
        val fallback = FallbackPresentationState(clockMs = { 0L }, videoPlanV2 = true)
        val gates = FallbackCloseGateState()
        var authorityReports = 0
        presentation.markPrimaryProgressionAllowed()

        gates.addPlayableElapsedMs(0, FALLBACK_CLOSE_GATE_MS, FALLBACK_CLOSE_GATE_MS, false, true, false, false)
        assertEquals(0L, gates.elapsedMs(0))
        assertFalse(presentation.rewardEarned)

        assertTrue(fallback.reportAuthoritativeEnd {
            authorityReports++
            presentation.markAuthoritativeEndReached()
        })
        assertFalse(fallback.reportAuthoritativeEnd {
            authorityReports++
            presentation.markAuthoritativeEndReached()
        })
        assertEquals(1, authorityReports)
        assertTrue(presentation.rewardEarned)
        assertFalse(presentation.hasClaimedRewardCompletion())
        assertEquals(
            RewardCompletionReason.UNIT_END,
            presentation.claimRewardCompletion()?.reason,
        )
        assertNull(presentation.markAuthoritativeEndReached())
    }

    @Test
    fun `reward completion is never claimed by teardown state`() {
        val legacy = unitEndPresentation(
            videoContract2 = false,
            rewardBehavior = null,
        )
        legacy.recordCompletionReason(ad.simula.ad.sdk.model.RewardCompletionReason.DURATION_ELAPSED)
        legacy.retainRewardEarned(true)
        assertFalse(legacy.hasClaimedRewardCompletion())

        val unitEnd = unitEndPresentation()
        unitEnd.markPrimaryProgressionAllowed()
        assertFalse(unitEnd.rewardEarned)
        assertFalse(unitEnd.hasClaimedRewardCompletion())
    }

    @Test
    fun `fallback videos remain candidates until a lease settles`() {
        val state = FallbackPresentationState(videoPlanV2 = true)
        val playable = ad.simula.ad.sdk.network.SimulaApiClient.FallbackAd(
            adId = "playable",
            sourceIndex = 0,
            type = CreativeType.PLAYABLE,
            renderedHtml = "<html></html>",
        )
        val video = ad.simula.ad.sdk.network.SimulaApiClient.FallbackAd(
            adId = "video",
            sourceIndex = 1,
            type = CreativeType.VIDEO,
            url = "https://cdn.example/video.mp4",
        )
        state.retainServerCandidates(listOf(playable, video))

        assertEquals(listOf(playable), state.displayablePreparedAds())
        assertTrue(state.hasPendingVideoPreparation())

        val file = temporaryFolder.newFile("prepared.video").apply { writeBytes(byteArrayOf(1)) }
        state.settleVideoPreparation(1, VideoAssetLease(file) {})

        assertEquals(listOf(playable, video), state.displayablePreparedAds())
    }

    @Test
    fun `failed video after playable auto skips without replaying playable`() {
        val state = FallbackPresentationState(clockMs = { 0L }, videoPlanV2 = true)
        val playable = ad.simula.ad.sdk.network.SimulaApiClient.FallbackAd(
            adId = "playable",
            sourceIndex = 0,
            type = CreativeType.PLAYABLE,
            renderedHtml = "<html></html>",
        )
        val video = ad.simula.ad.sdk.network.SimulaApiClient.FallbackAd(
            adId = "video",
            sourceIndex = 1,
            type = CreativeType.VIDEO,
            url = "https://cdn.example/video.mp4",
        )
        state.retainServerCandidates(listOf(playable, video))
        state.showing(0)
        val generation = state.startPostCloseFetchWait(targetIndex = 1)
        state.settleVideoPreparation(1, null)
        val displayable = state.displayablePreparedAds()

        assertEquals(listOf(playable), displayable)
        assertTrue(state.resolvePostCloseFetchWait(generation, displayable))
        assertEquals(FallbackStage.DONE, state.stage)
    }

    @Test
    fun `two tone remains visible after gate and exposes exact bright dark fractions`() {
        assertTrue(progressBarVisible(true, CloseTreatment.PROGRESS_BAR, ProgressBarStyle.TWO_TONE))
        assertFalse(progressBarVisible(true, CloseTreatment.PROGRESS_BAR, ProgressBarStyle.SINGLE))
        assertEquals(TwoToneProgressFractions(0.4f, 0.35f), twoToneProgressFractions(0.75f, 0.4f))
        assertEquals(0xFF3A3A40L, TWO_TONE_PROGRESS_BACKGROUND_ARGB)
        assertEquals(0xFF1186F2L, TWO_TONE_PROGRESS_BRIGHT_ARGB)
        assertEquals(0xFF1156B6L, TWO_TONE_PROGRESS_DARK_ARGB)
    }

    @Test
    fun `screen stays awake only during eligible foreground playback`() {
        assertTrue(videoScreenAwakeEligible(true, false, true, false))
        assertFalse(videoScreenAwakeEligible(false, false, true, false))
        assertFalse(videoScreenAwakeEligible(true, true, true, false))
        assertFalse(videoScreenAwakeEligible(true, false, false, false))
        assertFalse(videoScreenAwakeEligible(true, false, true, true))
        assertFalse(videoScreenAwakeEligible(true, false, true, false, prepared = false))
        assertFalse(videoScreenAwakeEligible(true, false, true, false, surfaceAttached = false))
        assertFalse(videoScreenAwakeEligible(true, false, true, false, playing = false))
    }

    @Test
    fun `generation and terminal outcome survive controller recreation`() {
        val state = VideoPlanPresentationState(videoPlanV2 = true)
        val slot = VideoPlaybackSlotIdentity.Primary
        val first = state.registerPlaybackGeneration(slot)
        assertTrue(state.claimPlaybackTerminal(first.generation, VideoPlaybackTerminalOutcome.COMPLETED))

        val recreated = state.registerPlaybackGeneration(slot)

        assertEquals(VideoPlaybackTerminalOutcome.COMPLETED, recreated.retainedTerminalOutcome)
        assertFalse(state.isPlaybackGenerationOpen(recreated.generation))
        assertFalse(state.claimPlaybackTerminal(recreated.generation, VideoPlaybackTerminalOutcome.COMPLETED))
    }

    @Test
    fun `video lifecycle names remain canonical`() {
        assertEquals(
            listOf("completed", "failed", "user", "no_next_step", "next_step_failed", "next_step_timeout",
                "backgrounded", "store_presented", "audio_interruption", "playback"),
            VideoLifecycleReason.entries.map { it.wire },
        )
    }

    @Test
    fun `focus loss preserves contract 2 preference but remutes legacy playback`() {
        val audio = VideoAudioSessionState(videoPlanV2 = true)
        audio.updateFromTap(videoPlanV2 = true, value = false)
        val contract2 = videoAudioFocusLossPolicy(videoPlanV2 = true, desiredMuted = audio.desiredMuted)
        val legacy = videoAudioFocusLossPolicy(videoPlanV2 = false, desiredMuted = false)

        assertFalse(contract2.desiredMuted)
        assertFalse(contract2.abandonFocus)
        assertTrue(effectiveVideoMuted(contract2.desiredMuted, audioFocusHeld = false))
        assertFalse(effectiveVideoMuted(contract2.desiredMuted, audioFocusHeld = true))
        assertTrue(legacy.desiredMuted)
        assertTrue(legacy.abandonFocus)
    }

    @Test
    fun `collision geometry clears bottom left close and bottom progress bar`() {
        val bottomLeft = videoChromeObstructionClearance(
            ClosePosition.BOTTOM_LEFT,
            ad.simula.ad.sdk.model.VideoChromeStyle.FLOATING_PILL,
            bottomProgressBarObstructed = false,
        )
        val bottomBar = videoChromeObstructionClearance(
            ClosePosition.TOP_RIGHT,
            ad.simula.ad.sdk.model.VideoChromeStyle.BOTTOM_BAR,
            bottomProgressBarObstructed = true,
        )
        assertEquals(112, bottomLeft.minimumStartFromSafeEdgeDp)
        assertEquals(38, bottomBar.minimumBottomFromSafeEdgeDp)
    }

    private fun unitEndPresentation(
        videoContract2: Boolean = true,
        rewardBehavior: RewardBehavior? = RewardBehavior(RewardEarnAt.UNIT_END),
    ) = RewardedPresentation(
        creative = Creative(type = CreativeType.VIDEO),
        videoContract2 = videoContract2,
        impressionId = "serve",
        apiKey = "key",
        callbacks = object : RewardedCallbacks {
            override fun onDisplayed() = Unit
            override fun onImpression() = Unit
            override fun onPaid(adValue: AdValue) = Unit
            override fun persistClick(
                interaction: ad.simula.ad.sdk.network.ClickInteraction,
                onTelemetryPersisted: () -> Unit,
            ) = onTelemetryPersisted()
            override fun notifyClicked(interaction: ad.simula.ad.sdk.network.ClickInteraction) = Unit
            override fun onClose(earned: Boolean, elapsedPlayTimeSeconds: Double) = Unit
            override fun onRewardCompleted(
                earned: Boolean,
                elapsedPlayTimeSeconds: Double,
                completionReason: ad.simula.ad.sdk.model.RewardCompletionReason?,
            ) = Unit
        },
        adBehavior = rewardBehavior?.let { AdBehavior(reward = it) },
    )
}
