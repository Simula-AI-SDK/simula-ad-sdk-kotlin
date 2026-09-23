package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.CreativeType
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.CloseTreatment
import ad.simula.ad.sdk.model.VideoAudioSessionState
import ad.simula.ad.sdk.model.VideoChromeStyle
import ad.simula.ad.sdk.model.VideoSequenceAdvance
import ad.simula.ad.sdk.model.VideoStallBudget
import ad.simula.ad.sdk.model.VideoMuteControlPlacement
import ad.simula.ad.sdk.model.VideoPreFirstFrameFailureAction
import ad.simula.ad.sdk.model.VideoAudioWatchAccounting
import ad.simula.ad.sdk.model.VideoInstallOverlayClock
import ad.simula.ad.sdk.model.VideoLifecycleReason
import ad.simula.ad.sdk.model.VideoPositionAccumulator
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
import ad.simula.ad.sdk.model.videoMuteControlPlacement
import ad.simula.ad.sdk.model.videoPreFirstFrameFailureAction
import ad.simula.ad.sdk.model.videoAudioFocusLossPolicy
import ad.simula.ad.sdk.model.VideoChromeObstructionClearance
import ad.simula.ad.sdk.model.videoChromeObstructionClearance
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
        videoContract2 = v2,
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
        assertFalse(legacy.videoContract2)
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
    fun `video chrome clearance covers every style position and bottom obstruction combination`() {
        for (position in ClosePosition.entries) {
            for (style in VideoChromeStyle.entries) {
                for (bottomProgressBarObstructed in listOf(false, true)) {
                    val expected = VideoChromeObstructionClearance(
                        minimumStartFromSafeEdgeDp = if (
                            position == ClosePosition.BOTTOM_LEFT && style != VideoChromeStyle.CORNER_CTA
                        ) 112 else 0,
                        minimumBottomFromSafeEdgeDp = if (bottomProgressBarObstructed) 38 else 0,
                    )
                    assertEquals(
                        "position=$position style=$style obstructed=$bottomProgressBarObstructed",
                        expected,
                        videoChromeObstructionClearance(position, style, bottomProgressBarObstructed),
                    )
                }
            }
        }
    }

    @Test
    fun `wide bottom left close exclusion covers fallback inset and touch target`() {
        val clearance = videoChromeObstructionClearance(
            effectiveClosePosition = ClosePosition.BOTTOM_LEFT,
            resolvedStyle = VideoChromeStyle.FLOATING_PILL,
            bottomProgressBarObstructed = false,
        )

        assertEquals(112, clearance.minimumStartFromSafeEdgeDp)
        assertTrue(clearance.minimumStartFromSafeEdgeDp >= 18 + 8 + 48)
        assertEquals(0, clearance.minimumBottomFromSafeEdgeDp)
    }

    @Test
    fun `relocated progress close applies only bottom bar obstruction clearance`() {
        val clearance = videoChromeObstructionClearance(
            effectiveClosePosition = ClosePosition.TOP_RIGHT,
            resolvedStyle = VideoChromeStyle.BOTTOM_BAR,
            bottomProgressBarObstructed = true,
        )

        assertEquals(0, clearance.minimumStartFromSafeEdgeDp)
        assertEquals(38, clearance.minimumBottomFromSafeEdgeDp)
    }

    @Test
    fun `v2 mute avoids effective top left close while v1 and no cta keep legacy placement`() {
        assertEquals(
            VideoMuteControlPlacement.TOP_RIGHT,
            videoMuteControlPlacement(true, ctaEnabled = true, ClosePosition.TOP_LEFT),
        )
        assertEquals(
            VideoMuteControlPlacement.TOP_LEFT,
            videoMuteControlPlacement(true, ctaEnabled = true, ClosePosition.TOP_RIGHT),
        )
        assertEquals(
            VideoMuteControlPlacement.BOTTOM_RIGHT,
            videoMuteControlPlacement(false, ctaEnabled = true, ClosePosition.TOP_RIGHT),
        )
        assertEquals(
            VideoMuteControlPlacement.BOTTOM_RIGHT,
            videoMuteControlPlacement(true, ctaEnabled = false, ClosePosition.TOP_RIGHT),
        )
        assertEquals(
            ClosePosition.TOP_RIGHT,
            effectiveClosePosition(CloseTreatment.PROGRESS_BAR, ClosePosition.BOTTOM_LEFT),
        )
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
    fun `v1 focus loss permanently remutes and gain cannot restore audio`() {
        val loss = videoAudioFocusLossPolicy(videoPlanV2 = false, desiredMuted = false)

        assertTrue(loss.desiredMuted)
        assertTrue(loss.abandonFocus)
        assertTrue(effectiveVideoMuted(loss.desiredMuted, audioFocusHeld = false))
        assertTrue(effectiveVideoMuted(loss.desiredMuted, audioFocusHeld = true))
    }

    @Test
    fun `v2 focus loss preserves preference and gain restores audio`() {
        val loss = videoAudioFocusLossPolicy(videoPlanV2 = true, desiredMuted = false)

        assertFalse(loss.desiredMuted)
        assertFalse(loss.abandonFocus)
        assertTrue(effectiveVideoMuted(loss.desiredMuted, audioFocusHeld = false))
        assertFalse(effectiveVideoMuted(loss.desiredMuted, audioFocusHeld = true))
    }

    @Test
    fun `v1 media deltas do not mutate presentation watch accounting`() {
        val state = VideoPlanPresentationState(videoPlanV2 = true)
        state.addEligibleMediaDelta(videoPlanV2 = false, advancedMs = 1_000L, muted = false)

        assertEquals(0L, state.audioWatchTotals().mutedMs)
        assertEquals(0L, state.audioWatchTotals().unmutedMs)
    }

    @Test
    fun `recreated v2 controller contributes only newly advanced media`() {
        val state = VideoPlanPresentationState(videoPlanV2 = true)
        val firstController = VideoPositionAccumulator()
        val recreatedController = VideoPositionAccumulator(initialPlayedMs = 1_000L)

        state.addEligibleMediaDelta(true, firstController.sample(1_000L).advancedMs, muted = false)
        state.addEligibleMediaDelta(true, recreatedController.sample(0L).advancedMs, muted = true)
        state.addEligibleMediaDelta(true, recreatedController.sample(500L).advancedMs, muted = true)

        assertEquals(1_000L, state.audioWatchTotals().unmutedMs)
        assertEquals(500L, state.audioWatchTotals().mutedMs)
    }

    @Test
    fun `generation is unavailable until committed registration`() {
        val state = VideoPlanPresentationState(videoPlanV2 = true)

        assertFalse(state.closeCurrent(VideoLifecycleReason.USER))

        val generation = state.registerPlaybackGeneration(VideoPlaybackSlotIdentity.Primary).generation
        assertTrue(state.isPlaybackGenerationOpen(generation))
    }

    @Test
    fun `retained terminal outcome selects only its host replay`() {
        assertEquals(
            VideoPlaybackReplayAction.COMPLETE,
            videoPlaybackReplayAction(VideoPlaybackTerminalOutcome.COMPLETED),
        )
        assertEquals(
            VideoPlaybackReplayAction.FAIL,
            videoPlaybackReplayAction(VideoPlaybackTerminalOutcome.FAILED),
        )
        assertEquals(
            VideoPlaybackReplayAction.STAY_STOPPED,
            videoPlaybackReplayAction(VideoPlaybackTerminalOutcome.USER),
        )
        assertEquals(VideoPlaybackReplayAction.PREPARE, videoPlaybackReplayAction(null))
    }

    @Test
    fun `same slot replacement preserves retained telemetry and rejects stale token`() {
        val recordedPositions = mutableListOf<Double>()
        val state = VideoPlanPresentationState(
            videoPlanV2 = true,
            terminalRecorder = { telemetry, _, _, _ -> recordedPositions += telemetry.videoPositionS },
        )
        val slot = VideoPlaybackSlotIdentity.Fallback(sourceIndex = 4)
        val staleGeneration = state.registerPlaybackGeneration(slot).generation
        state.retainCurrentTelemetry(staleGeneration, playbackTelemetry(4, 3.5, 3.5, 0.0))

        val replacementGeneration = state.registerPlaybackGeneration(slot).generation

        assertFalse(state.claimPlaybackTerminal(staleGeneration, VideoPlaybackTerminalOutcome.COMPLETED))
        assertTrue(state.isPlaybackGenerationOpen(replacementGeneration))
        assertTrue(state.closeCurrent(VideoLifecycleReason.USER))
        assertEquals(listOf(3.5), recordedPositions)
    }

    @Test
    fun `same slot replacement preserves original video start time`() {
        var now = 1_000L
        val state = VideoPlanPresentationState(videoPlanV2 = true, clockMs = { now })
        val slot = VideoPlaybackSlotIdentity.Fallback(sourceIndex = 2)
        state.registerPlaybackGeneration(slot)
        state.firstVideoFrame(config = null)

        now = 2_000L
        val replacementGeneration = state.registerPlaybackGeneration(slot).generation
        assertTrue(
            state.claimPlaybackTerminal(replacementGeneration, VideoPlaybackTerminalOutcome.COMPLETED),
        )
        state.beginHandoff(playbackTelemetry(2, 1.0, 1.0, 0.0), VideoLifecycleReason.COMPLETED)
        now = 3_000L

        assertEquals(2.0, requireNotNull(state.nextStepReady()).secondsSinceVideoStart, 0.0)
    }

    @Test
    fun `completed same slot recreation returns outcome without duplicate terminal telemetry`() {
        val terminalStages = mutableListOf<String>()
        val state = VideoPlanPresentationState(
            videoPlanV2 = true,
            terminalRecorder = { _, stage, _, _ -> terminalStages += stage },
        )
        val telemetry = playbackTelemetry(0, 1.0, 1.0, 0.0)
        val slot = VideoPlaybackSlotIdentity.Primary
        val settledGeneration = state.registerPlaybackGeneration(slot).generation
        assertTrue(state.claimPlaybackTerminal(settledGeneration, VideoPlaybackTerminalOutcome.COMPLETED))
        state.close(telemetry, VideoLifecycleReason.COMPLETED)

        val replacement = state.registerPlaybackGeneration(slot)

        assertEquals(VideoPlaybackTerminalOutcome.COMPLETED, replacement.retainedTerminalOutcome)
        assertFalse(state.isPlaybackGenerationOpen(replacement.generation))
        assertFalse(
            state.claimPlaybackTerminal(replacement.generation, VideoPlaybackTerminalOutcome.COMPLETED),
        )
        assertEquals(listOf(VIDEO_STAGE_CLOSE), terminalStages)
    }

    @Test
    fun `failed same slot recreation returns outcome without duplicate terminal telemetry`() {
        val terminalStages = mutableListOf<String>()
        val state = VideoPlanPresentationState(
            videoPlanV2 = true,
            terminalRecorder = { _, stage, _, _ -> terminalStages += stage },
        )
        val telemetry = playbackTelemetry(0, 1.0, 1.0, 0.0)
        val slot = VideoPlaybackSlotIdentity.Fallback(sourceIndex = 0)
        val settledGeneration = state.registerPlaybackGeneration(slot).generation
        assertTrue(state.claimPlaybackTerminal(settledGeneration, VideoPlaybackTerminalOutcome.FAILED))
        state.close(telemetry, VideoLifecycleReason.FAILED)

        val replacement = state.registerPlaybackGeneration(slot)

        assertEquals(VideoPlaybackTerminalOutcome.FAILED, replacement.retainedTerminalOutcome)
        assertFalse(state.isPlaybackGenerationOpen(replacement.generation))
        assertEquals(listOf(VIDEO_STAGE_CLOSE), terminalStages)
    }

    @Test
    fun `user terminal same slot recreation remains stopped without duplicate telemetry`() {
        val terminalStages = mutableListOf<String>()
        val state = VideoPlanPresentationState(
            videoPlanV2 = true,
            terminalRecorder = { _, stage, _, _ -> terminalStages += stage },
        )
        val slot = VideoPlaybackSlotIdentity.Fallback(sourceIndex = 0)
        val first = state.registerPlaybackGeneration(slot)
        state.retainCurrentTelemetry(first.generation, playbackTelemetry(0, 1.0, 1.0, 0.0))
        assertTrue(state.closeCurrent(VideoLifecycleReason.USER))

        val replacement = state.registerPlaybackGeneration(slot)

        assertEquals(VideoPlaybackTerminalOutcome.USER, replacement.retainedTerminalOutcome)
        assertFalse(state.isPlaybackGenerationOpen(replacement.generation))
        assertEquals(listOf(VIDEO_STAGE_CLOSE), terminalStages)
    }

    @Test
    fun `different slots with duplicate clip index prepare independently`() {
        val state = VideoPlanPresentationState(videoPlanV2 = true)
        val duplicateClipIndex = 1
        val first = state.registerPlaybackGeneration(VideoPlaybackSlotIdentity.Primary)
        state.retainCurrentTelemetry(first.generation, playbackTelemetry(duplicateClipIndex, 1.0, 1.0, 0.0))
        assertTrue(state.claimPlaybackTerminal(first.generation, VideoPlaybackTerminalOutcome.COMPLETED))

        val second = state.registerPlaybackGeneration(VideoPlaybackSlotIdentity.Fallback(sourceIndex = 0))

        assertNull(second.retainedTerminalOutcome)
        assertEquals(VideoPlaybackReplayAction.PREPARE, videoPlaybackReplayAction(second.retainedTerminalOutcome))
        assertTrue(state.isPlaybackGenerationOpen(second.generation))
        assertFalse(state.claimPlaybackTerminal(first.generation, VideoPlaybackTerminalOutcome.FAILED))
    }

    @Test
    fun `null clip telemetry still replays a valid same slot terminal outcome`() {
        val state = VideoPlanPresentationState(videoPlanV2 = true)
        val slot = VideoPlaybackSlotIdentity.Fallback(sourceIndex = 3)
        val first = state.registerPlaybackGeneration(slot)
        state.retainCurrentTelemetry(first.generation, playbackTelemetry(null, 1.0, 1.0, 0.0))
        assertTrue(state.claimPlaybackTerminal(first.generation, VideoPlaybackTerminalOutcome.FAILED))

        val replacement = state.registerPlaybackGeneration(slot)

        assertEquals(VideoPlaybackTerminalOutcome.FAILED, replacement.retainedTerminalOutcome)
        assertEquals(VideoPlaybackReplayAction.FAIL, videoPlaybackReplayAction(replacement.retainedTerminalOutcome))
    }

    @Test
    fun `user close wins a queued completion`() {
        val terminalEvents = mutableListOf<Pair<String, VideoLifecycleReason?>>()
        val state = VideoPlanPresentationState(
            videoPlanV2 = true,
            terminalRecorder = { _, stage, reason, _ -> terminalEvents += stage to reason },
        )
        val generation = state.registerPlaybackGeneration(VideoPlaybackSlotIdentity.Primary).generation
        state.retainCurrentTelemetry(generation, playbackTelemetry(0, 1.0, 1.0, 0.0))

        assertTrue(state.closeCurrent(VideoLifecycleReason.USER))
        assertFalse(state.claimPlaybackTerminal(generation, VideoPlaybackTerminalOutcome.COMPLETED))
        assertEquals(listOf(VIDEO_STAGE_CLOSE to VideoLifecycleReason.USER), terminalEvents)
    }

    @Test
    fun `user close wins a queued failure`() {
        val terminalEvents = mutableListOf<Pair<String, VideoLifecycleReason?>>()
        val state = VideoPlanPresentationState(
            videoPlanV2 = true,
            terminalRecorder = { _, stage, reason, _ -> terminalEvents += stage to reason },
        )
        val generation = state.registerPlaybackGeneration(VideoPlaybackSlotIdentity.Primary).generation
        state.retainCurrentTelemetry(generation, playbackTelemetry(0, 1.0, 1.0, 0.0))

        assertTrue(state.closeCurrent(VideoLifecycleReason.USER))
        assertFalse(state.claimPlaybackTerminal(generation, VideoPlaybackTerminalOutcome.FAILED))
        assertEquals(listOf(VIDEO_STAGE_CLOSE to VideoLifecycleReason.USER), terminalEvents)
    }

    @Test
    fun `natural terminal claim wins a queued user close`() {
        val terminalEvents = mutableListOf<Pair<String, VideoLifecycleReason?>>()
        val state = VideoPlanPresentationState(
            videoPlanV2 = true,
            terminalRecorder = { _, stage, reason, _ -> terminalEvents += stage to reason },
        )
        val telemetry = playbackTelemetry(0, 1.0, 1.0, 0.0)
        val generation = state.registerPlaybackGeneration(VideoPlaybackSlotIdentity.Primary).generation
        state.retainCurrentTelemetry(generation, telemetry)

        assertTrue(state.claimPlaybackTerminal(generation, VideoPlaybackTerminalOutcome.COMPLETED))
        state.close(telemetry, VideoLifecycleReason.COMPLETED)
        assertTrue(state.closeCurrent(VideoLifecycleReason.USER))
        assertEquals(listOf(VIDEO_STAGE_CLOSE to VideoLifecycleReason.COMPLETED), terminalEvents)
    }

    @Test
    fun `stale generation cannot claim the current slot`() {
        val state = VideoPlanPresentationState(videoPlanV2 = true)
        val slot = VideoPlaybackSlotIdentity.Primary
        val staleGeneration = state.registerPlaybackGeneration(slot).generation
        val currentGeneration = state.registerPlaybackGeneration(slot).generation

        assertFalse(state.claimPlaybackTerminal(staleGeneration, VideoPlaybackTerminalOutcome.FAILED))
        assertTrue(state.claimPlaybackTerminal(currentGeneration, VideoPlaybackTerminalOutcome.FAILED))
    }

    @Test
    fun `next slot terminal claim is independent`() {
        val state = VideoPlanPresentationState(videoPlanV2 = true)
        val firstGeneration = state.registerPlaybackGeneration(VideoPlaybackSlotIdentity.Primary).generation
        assertTrue(state.claimPlaybackTerminal(firstGeneration, VideoPlaybackTerminalOutcome.COMPLETED))

        val secondGeneration = state.registerPlaybackGeneration(
            VideoPlaybackSlotIdentity.Fallback(sourceIndex = 0),
        ).generation
        assertTrue(state.isPlaybackGenerationOpen(secondGeneration))
        assertTrue(state.claimPlaybackTerminal(secondGeneration, VideoPlaybackTerminalOutcome.FAILED))
    }

    @Test
    fun `retained telemetry cannot repopulate after terminal claim`() {
        val recordedClipIndices = mutableListOf<Int?>()
        val state = VideoPlanPresentationState(
            videoPlanV2 = true,
            terminalRecorder = { telemetry, _, _, _ -> recordedClipIndices += telemetry.context.clipIndex },
        )
        val generation = state.registerPlaybackGeneration(VideoPlaybackSlotIdentity.Primary).generation
        state.retainCurrentTelemetry(generation, playbackTelemetry(0, 1.0, 1.0, 0.0))

        assertTrue(state.closeCurrent(VideoLifecycleReason.USER))
        state.retainCurrentTelemetry(generation, playbackTelemetry(1, 1.0, 1.0, 0.0))

        assertTrue(state.closeCurrent(VideoLifecycleReason.USER))
        assertEquals(listOf(0), recordedClipIndices)
    }

    @Test
    fun `pending predecessor handoff survives the next generation`() {
        val terminalStages = mutableListOf<String>()
        val state = VideoPlanPresentationState(
            videoPlanV2 = true,
            clockMs = { 0L },
            terminalRecorder = { _, stage, _, _ -> terminalStages += stage },
        )
        val firstGeneration = state.registerPlaybackGeneration(VideoPlaybackSlotIdentity.Primary).generation
        assertTrue(state.claimPlaybackTerminal(firstGeneration, VideoPlaybackTerminalOutcome.COMPLETED))
        state.beginHandoff(playbackTelemetry(0, 1.0, 1.0, 0.0), VideoLifecycleReason.COMPLETED)

        val secondGeneration = state.registerPlaybackGeneration(
            VideoPlaybackSlotIdentity.Fallback(sourceIndex = 1),
        ).generation

        assertTrue(state.isPlaybackGenerationOpen(secondGeneration))
        assertTrue(state.nextStepReady() != null)
        assertEquals(listOf(VIDEO_STAGE_HANDOFF), terminalStages)
    }

    @Test
    fun `two clips aggregate mixed audio into final handoff and close`() {
        val terminalEvents = mutableListOf<Triple<String, VideoLifecycleReason?, VideoPlaybackTelemetry>>()
        val state = VideoPlanPresentationState(
            videoPlanV2 = true,
            clockMs = { 0L },
            terminalRecorder = { telemetry, stage, reason, _ ->
                terminalEvents += Triple(stage, reason, telemetry)
            },
        )
        val firstClip = playbackTelemetry(clipIndex = 0, watchedS = 1.25, unmutedS = 1.0, mutedS = 0.25)
        val secondClip = playbackTelemetry(clipIndex = 1, watchedS = 1.25, unmutedS = 0.75, mutedS = 0.5)
        val firstGeneration = state.registerPlaybackGeneration(
            VideoPlaybackSlotIdentity.Fallback(sourceIndex = 0),
        ).generation

        state.addEligibleMediaDelta(videoPlanV2 = true, advancedMs = 1_000L, muted = false)
        state.addEligibleMediaDelta(videoPlanV2 = true, advancedMs = 250L, muted = true)
        state.retainCurrentTelemetry(firstGeneration, firstClip)
        assertTrue(state.claimPlaybackTerminal(firstGeneration, VideoPlaybackTerminalOutcome.COMPLETED))
        state.beginHandoff(firstClip, VideoLifecycleReason.COMPLETED)
        state.retainCurrentTelemetry(firstGeneration, firstClip)

        val secondGeneration = state.registerPlaybackGeneration(
            VideoPlaybackSlotIdentity.Fallback(sourceIndex = 1),
        ).generation
        state.addEligibleMediaDelta(videoPlanV2 = true, advancedMs = 500L, muted = true)
        state.addEligibleMediaDelta(videoPlanV2 = true, advancedMs = 750L, muted = false)
        state.nextStepReady()
        assertNull(state.nextStepReady())
        assertTrue(state.claimPlaybackTerminal(secondGeneration, VideoPlaybackTerminalOutcome.COMPLETED))
        state.close(secondClip, reason = VideoLifecycleReason.COMPLETED)

        assertEquals(listOf(VIDEO_STAGE_HANDOFF, VIDEO_STAGE_CLOSE), terminalEvents.map { it.first })
        assertEquals(listOf(VideoLifecycleReason.COMPLETED, VideoLifecycleReason.COMPLETED), terminalEvents.map { it.second })
        terminalEvents.forEach { (_, _, telemetry) ->
            assertEquals(2.5, telemetry.watchedS, 0.0)
            assertEquals(1.75, telemetry.secondsUnmuted, 0.0)
            assertEquals(0.75, telemetry.secondsMuted, 0.0)
        }
    }

    @Test
    fun `final completed video close preserves completed origin`() {
        val reasons = mutableListOf<VideoLifecycleReason?>()
        val fallback = FallbackPresentationState(clockMs = { 0L }, videoPlanV2 = true)
        val state = VideoPlanPresentationState(
            videoPlanV2 = true,
            clockMs = { 0L },
            terminalRecorder = { _, stage, reason, _ ->
                if (stage == VIDEO_STAGE_CLOSE) reasons += reason
            },
        )

        state.beginHandoff(playbackTelemetry(0, 1.0, 1.0, 0.0), VideoLifecycleReason.COMPLETED)
        fallback.retainFetchedAds(emptyList())
        assertNull(fallback.fallbackResolutionReasonOverride())
        state.closePendingHandoff(fallback.fallbackResolutionReasonOverride())

        assertEquals(listOf(VideoLifecycleReason.COMPLETED), reasons)
    }

    @Test
    fun `final failed video close preserves failed origin`() {
        val reasons = mutableListOf<VideoLifecycleReason?>()
        val state = VideoPlanPresentationState(
            videoPlanV2 = true,
            clockMs = { 0L },
            terminalRecorder = { _, stage, reason, _ ->
                if (stage == VIDEO_STAGE_CLOSE) reasons += reason
            },
        )

        state.beginHandoff(playbackTelemetry(0, 1.0, 1.0, 0.0), VideoLifecycleReason.FAILED)
        state.closePendingHandoff()

        assertEquals(listOf(VideoLifecycleReason.FAILED), reasons)
    }

    @Test
    fun `expected next step infrastructure failure explicitly overrides origin`() {
        val reasons = mutableListOf<VideoLifecycleReason?>()
        val state = FallbackPresentationState(clockMs = { 0L }, videoPlanV2 = true)
        val videoPlan = VideoPlanPresentationState(
            videoPlanV2 = true,
            clockMs = { 0L },
            terminalRecorder = { _, stage, reason, _ ->
                if (stage == VIDEO_STAGE_CLOSE) reasons += reason
            },
        )

        videoPlan.beginHandoff(playbackTelemetry(0, 1.0, 1.0, 0.0), VideoLifecycleReason.COMPLETED)
        state.terminalizeInitialFetchFailure()
        videoPlan.closePendingHandoff(state.fallbackResolutionReasonOverride())

        assertEquals(listOf(VideoLifecycleReason.NEXT_STEP_FAILED), reasons)
    }

    @Test
    fun `pre first frame failure preserves predecessor handoff for the next viable step`() {
        assertEquals(
            VideoPreFirstFrameFailureAction.PRESERVE_PENDING_HANDOFF,
            videoPreFirstFrameFailureAction(hasNextStep = true),
        )
        val terminalEvents = mutableListOf<Triple<Int?, String, VideoLifecycleReason?>>()
        val state = VideoPlanPresentationState(
            videoPlanV2 = true,
            clockMs = { 0L },
            terminalRecorder = { telemetry, stage, reason, _ ->
                terminalEvents += Triple(telemetry.context.clipIndex, stage, reason)
            },
        )

        state.beginHandoff(playbackTelemetry(0, 1.0, 1.0, 0.0), VideoLifecycleReason.COMPLETED)
        state.nextStepReady()

        assertEquals(
            listOf(Triple(0, VIDEO_STAGE_HANDOFF, VideoLifecycleReason.COMPLETED)),
            terminalEvents,
        )
    }

    @Test
    fun `final pre first frame failure closes predecessor as next step failed`() {
        assertEquals(
            VideoPreFirstFrameFailureAction.FAIL_EXPECTED_NEXT_STEP,
            videoPreFirstFrameFailureAction(hasNextStep = false),
        )
        val terminalEvents = mutableListOf<Triple<Int?, String, VideoLifecycleReason?>>()
        val state = VideoPlanPresentationState(
            videoPlanV2 = true,
            clockMs = { 0L },
            terminalRecorder = { telemetry, stage, reason, _ ->
                terminalEvents += Triple(telemetry.context.clipIndex, stage, reason)
            },
        )

        state.beginHandoff(playbackTelemetry(0, 1.0, 1.0, 0.0), VideoLifecycleReason.COMPLETED)
        state.resolvePendingHandoffForFailedNextStep()

        assertEquals(
            listOf(Triple(0, VIDEO_STAGE_CLOSE, VideoLifecycleReason.NEXT_STEP_FAILED)),
            terminalEvents,
        )
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
    fun `repeated non progress buffering observations do not reset v2 stall budget`() {
        val budget = VideoStallBudget(8_000L)

        assertFalse(budget.observe(0L, eligible = true, healthyProgress = false))
        assertFalse(budget.observe(3_000L, eligible = true, healthyProgress = false))
        assertFalse(budget.observe(6_000L, eligible = true, healthyProgress = false))
        assertTrue(budget.observe(8_000L, eligible = true, healthyProgress = false))

        assertFalse(budget.observe(9_000L, eligible = true, healthyProgress = true))
        assertEquals(8_000L, budget.remainingMs())
    }

    @Test
    fun `duration telemetry emits midpoint only`() {
        val tracker = VideoQuartileTracker()
        assertTrue(tracker.crossed(2_500L, 10_000L).isEmpty())
        assertEquals(listOf(50), tracker.crossed(8_000L, 10_000L))
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
        var recordedReason: VideoLifecycleReason? = null
        val state = VideoPlanPresentationState(
            videoPlanV2 = true,
            clockMs = { now },
            terminalRecorder = { _, _, reason, _ -> recordedReason = reason },
        )
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
        state.beginHandoff(telemetry, VideoLifecycleReason.FAILED)
        now = 5_300L

        val timing = requireNotNull(state.nextStepReady())
        assertEquals(300.0, timing.msToNextStepReady, 0.0)
        assertEquals(4.3, timing.secondsSinceVideoStart, 0.0)
        assertEquals("next_step", timing.on)
        assertEquals(VideoLifecycleReason.FAILED, recordedReason)
        assertTrue(state.overlayReady())
    }

    @Test
    fun `accepted delayed playable fallback reports first step ready immediately`() {
        val state = pendingFallbackHandoffState()
        val generation = state.startPostCloseFetchWait()

        assertTrue(state.resolvePostCloseFetchWait(generation, listOf(playable(0))))
        assertNull(state.videoPlan.nextStepReady())
    }

    @Test
    fun `fallback preparation plans only current and immediate next`() {
        val ads = listOf(playable(0), video(1), video(2))

        assertEquals(listOf(0, 1), fallbackPreparationWindow(ads, 0).map { it.sourceIndex })
        assertEquals(listOf(1, 2), fallbackPreparationWindow(ads, 1).map { it.sourceIndex })
    }

    @Test
    fun `post close fallback settle has one aggregate bounded deadline`() {
        var now = 100L
        val state = FallbackPresentationState(clockMs = { now })
        val generation = state.startPostCloseFetchWait()

        now += FALLBACK_POST_CLOSE_WAIT_MS

        assertEquals(0L, state.postCloseFetchWaitRemainingMs(generation))
        assertTrue(state.timeoutPostCloseFetchWait(generation))
        assertEquals(FallbackStage.DONE, state.stage)
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
            VideoLifecycleReason.COMPLETED,
        )
        return state
    }

    private fun playbackTelemetry(
        clipIndex: Int?,
        watchedS: Double,
        unmutedS: Double,
        mutedS: Double,
    ) = VideoPlaybackTelemetry(
        context = VideoTelemetryContext(
            adFormat = "interstitial",
            adUnitId = "unit",
            adId = "ad",
            serveId = "serve",
            impressionId = "serve",
            style = "corner_cta",
            skoverlayEnabled = false,
            skoverlayDelaySeconds = 3,
            clipIndex = clipIndex,
            pool = "ugc",
        ),
        videoPositionS = watchedS,
        muted = mutedS > 0.0,
        durationS = 10.0,
        watchedS = watchedS,
        secondsUnmuted = unmutedS,
        secondsMuted = mutedS,
    )

    @Test
    fun `video lifecycle reasons exactly match the canonical contract`() {
        assertEquals(
            listOf(
                "completed",
                "failed",
                "user",
                "no_next_step",
                "next_step_failed",
                "next_step_timeout",
                "backgrounded",
                "store_presented",
                "audio_interruption",
                "playback",
            ),
            VideoLifecycleReason.entries.map { it.wire },
        )
    }

    @Test
    fun `fallback ad formats normalize to base fullscreen labels`() {
        assertEquals("interstitial", canonicalFullscreenAdFormat("interstitial_fallback"))
        assertEquals("rewarded", canonicalFullscreenAdFormat("rewarded_fallback"))
        assertFalse(canonicalFullscreenAdFormat("interstitial_fallback").endsWith("_fallback"))
        assertFalse(canonicalFullscreenAdFormat("rewarded_fallback").endsWith("_fallback"))
    }
}
