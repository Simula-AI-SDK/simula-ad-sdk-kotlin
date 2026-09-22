package ad.simula.ad.sdk.model

import ad.simula.ad.sdk.ads.continueAfterVideoPositionPoll
import ad.simula.ad.sdk.ads.dispatchNaturalVideoCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreativePolicyTest {
    @Test
    fun `normal crossing into final tolerance while still playing never completes`() {
        val detector = VideoNearEndCompletionDetector(maxToleranceMs = 150L)

        assertFalse(detector.observe(10_000L, 9_849L, true, true, isPlaying = true))
        assertFalse(detector.observe(10_000L, 9_850L, true, true, isPlaying = true))
        assertFalse(detector.observe(10_000L, 10_000L, true, true, isPlaying = true))
    }

    @Test
    fun `small seek or timeline correction while playing never completes`() {
        val detector = VideoNearEndCompletionDetector(maxToleranceMs = 150L)
        assertFalse(detector.observe(10_000L, 9_700L, true, true, isPlaying = true))
        assertFalse(detector.observe(10_000L, 9_900L, true, true, isPlaying = true))
        assertFalse(detector.observe(10_000L, 9_875L, true, true, isPlaying = true))
        assertFalse(detector.observe(10_000L, 9_950L, true, true, isPlaying = true))
    }

    @Test
    fun `stable near-end non-playing grace confirms completion`() {
        val detector = VideoNearEndCompletionDetector(maxToleranceMs = 150L, requiredNotPlayingConfirmations = 2)
        assertFalse(detector.observe(10_000L, 9_700L, true, true, isPlaying = true))
        assertFalse(detector.observe(10_000L, 9_900L, true, true, isPlaying = true))
        assertFalse(detector.observe(10_000L, 9_900L, true, true, isPlaying = false))
        assertTrue(detector.observe(10_000L, 9_900L, true, true, isPlaying = false))
    }

    @Test
    fun `near-end playback timeout confirms completion and unknown duration never does`() {
        val detector = VideoNearEndCompletionDetector(maxToleranceMs = 150L)
        assertFalse(detector.observe(10_000L, 9_700L, true, true, isPlaying = true))
        assertFalse(detector.observe(10_000L, 9_900L, true, true, isPlaying = true))
        assertTrue(detector.onPlaybackTimeout(10_000L, 9_900L, true, true))

        val unknown = VideoNearEndCompletionDetector()
        assertFalse(unknown.observe(0L, 0L, true, true, isPlaying = false))
        assertFalse(unknown.observe(-1L, 0L, true, true, isPlaying = false))
        assertFalse(unknown.onPlaybackTimeout(0L, 0L, true, true))
    }

    @Test
    fun `v2 stall expiry completes stable near end and fails away from end`() {
        val nearEnd = VideoNearEndCompletionDetector(maxToleranceMs = 150L)
        val nearEndBudget = VideoStallBudget(1_000L)
        assertFalse(nearEnd.observe(10_000L, 9_700L, true, true, isPlaying = true))
        assertFalse(nearEnd.observe(10_000L, 9_900L, true, true, isPlaying = true))
        assertFalse(nearEndBudget.observe(0L, eligible = true, healthyProgress = false))
        assertTrue(nearEndBudget.observe(1_000L, eligible = true, healthyProgress = false))
        assertTrue(nearEnd.onPlaybackTimeout(10_000L, 9_900L, true, true))

        val notNearEnd = VideoNearEndCompletionDetector(maxToleranceMs = 150L)
        val stalledBudget = VideoStallBudget(1_000L)
        assertFalse(notNearEnd.observe(10_000L, 5_000L, true, true, isPlaying = true))
        assertFalse(notNearEnd.observe(10_000L, 5_000L, true, true, isPlaying = false))
        assertFalse(stalledBudget.observe(0L, eligible = true, healthyProgress = false))
        assertTrue(stalledBudget.observe(1_000L, eligible = true, healthyProgress = false))
        assertFalse(notNearEnd.onPlaybackTimeout(10_000L, 5_000L, true, true))
    }

    @Test
    fun `duration change backward position and lifecycle pause reject terminal inference`() {
        val durationMismatch = VideoNearEndCompletionDetector(maxToleranceMs = 150L)
        assertFalse(durationMismatch.observe(10_000L, 9_700L, true, true, isPlaying = true))
        assertFalse(durationMismatch.observe(10_000L, 9_900L, true, true, isPlaying = true))
        assertFalse(durationMismatch.observe(9_900L, 9_850L, true, true, isPlaying = false))
        assertFalse(durationMismatch.onPlaybackTimeout(9_900L, 9_850L, true, true))

        val backward = VideoNearEndCompletionDetector(maxToleranceMs = 150L)
        assertFalse(backward.observe(10_000L, 9_700L, true, true, isPlaying = true))
        assertFalse(backward.observe(10_000L, 9_900L, true, true, isPlaying = true))
        assertFalse(backward.observe(10_000L, 9_800L, true, true, isPlaying = false))
        assertFalse(backward.onPlaybackTimeout(10_000L, 9_800L, true, true))

        val paused = VideoNearEndCompletionDetector(maxToleranceMs = 150L)
        assertFalse(paused.observe(10_000L, 9_700L, true, true, isPlaying = true))
        assertFalse(paused.observe(10_000L, 9_900L, true, true, isPlaying = true))
        assertFalse(paused.observe(10_000L, 9_900L, true, false, isPlaying = false))
    }

    @Test
    fun `inferred and platform completion race is one-shot and cancels stall timeout`() {
        val gate = VideoCompletionGate()
        val inferred = gate.complete()
        val platformCallback = gate.complete()

        assertTrue(inferred.accepted)
        assertTrue(inferred.cancelPlaybackTimeout)
        assertFalse(platformCallback.accepted)
        assertFalse(platformCallback.cancelPlaybackTimeout)
        assertEquals(
            RewardCompletionReason.VIDEO_COMPLETED,
            monotonicRewardCompletionReason(null, RewardCompletionReason.VIDEO_COMPLETED),
        )
    }

    @Test
    fun `prepared dimensions seed aspect policy and later valid listener dimensions replace them`() {
        val seeded = resolveVideoDimensions(VideoDimensions(), 1920, 1080)
        assertEquals(VideoDimensions(1920, 1080), seeded)
        assertEquals(seeded, resolveVideoDimensions(seeded, 0, 0))
        assertEquals(seeded, resolveVideoDimensions(seeded, -1, 720))

        val replaced = resolveVideoDimensions(seeded, 1080, 1920)
        assertEquals(VideoDimensions(1080, 1920), replaced)
        val transform = videoAspectFitTransform(
            replaced.width,
            replaced.height,
            surfaceWidth = 1920,
            surfaceHeight = 1080,
        )
        assertEquals(0.3164f, transform.scaleX, 0.0001f)
        assertEquals(1f, transform.scaleY, 0.0001f)
    }

    @Test
    fun `aspect fit letterboxes landscape and pillarboxes portrait without stretching`() {
        val landscape = videoAspectFitTransform(1920, 1080, 1000, 1000)
        assertEquals(1f, landscape.scaleX, 0.0001f)
        assertEquals(0.5625f, landscape.scaleY, 0.0001f)

        val portrait = videoAspectFitTransform(1080, 1920, 1920, 1080)
        assertEquals(0.3164f, portrait.scaleX, 0.0001f)
        assertEquals(1f, portrait.scaleY, 0.0001f)

        assertEquals(VideoAspectFitTransform(1f, 1f), videoAspectFitTransform(0, 1080, 1000, 1000))
        assertEquals(VideoAspectFitTransform(1f, 1f), videoAspectFitTransform(1920, 1080, 0, 1000))
    }

    @Test
    fun `preparing claim transfers shared deadline while prepared claim receives first-frame budget`() {
        assertEquals(
            VideoPreparationClaimPolicy(VideoPreparationPhase.PREPARING, 10_000L),
            videoPreparationClaimPolicy(VideoPreparationPhase.PREPARING, 10_000L, 7_000L, 10_000L),
        )
        assertEquals(
            VideoPreparationClaimPolicy(VideoPreparationPhase.PREPARED, 17_000L),
            videoPreparationClaimPolicy(VideoPreparationPhase.PREPARED, 10_000L, 7_000L, 10_000L),
        )
    }

    @Test
    fun `readiness deadline pauses foreground budget and resumes remaining time`() {
        val deadline = VideoReadinessDeadline(deadlineMs = 10_000L)
        assertEquals(7_000L, deadline.remainingMs(3_000L))
        deadline.pause(3_000L)
        assertEquals(7_000L, deadline.remainingMs(50_000L))
        assertEquals(7_000L, deadline.resume(100_000L))
        assertEquals(5_000L, deadline.remainingMs(102_000L))
    }

    @Test
    fun `video UI progress coalesces samples and flushes boundaries`() {
        val coalescer = VideoUiProgressCoalescer(intervalMs = 250L)
        assertTrue(coalescer.shouldEmit(0L))
        assertFalse(coalescer.shouldEmit(100L))
        assertTrue(coalescer.shouldEmit(120L, midpointCrossed = true))
        assertFalse(coalescer.shouldEmit(200L))
        assertTrue(coalescer.shouldEmit(210L, gateCrossed = true))
        assertTrue(coalescer.shouldEmit(211L, force = true))
    }

    @Test
    fun `position polling continues after one transient read failure`() {
        val reads = ArrayDeque<Long?>(listOf(null, 250L))
        val observed = mutableListOf<Long>()

        assertTrue(continueAfterVideoPositionPoll(read = { reads.removeFirst() }) { observed += it; true })
        assertTrue(observed.isEmpty())
        assertTrue(continueAfterVideoPositionPoll(read = { reads.removeFirst() }) { observed += it; true })
        assertEquals(listOf(250L), observed)
    }

    @Test
    fun `repeated v2 null position reads consume eligible stall budget and retain near end evidence`() {
        val budget = VideoStallBudget(8_000L)
        val detector = VideoNearEndCompletionDetector(maxToleranceMs = 150L)
        var nowMs = 0L

        assertFalse(detector.observe(10_000L, 9_700L, true, true, isPlaying = true))
        assertFalse(detector.observe(10_000L, 9_900L, true, true, isPlaying = true))
        repeat(2) {
            assertTrue(
                continueAfterVideoPositionPoll(
                    read = { null as Long? },
                    onReadFailure = { !budget.observe(nowMs, eligible = true, healthyProgress = false) },
                ) { true },
            )
            nowMs += 4_000L
        }
        assertFalse(
            continueAfterVideoPositionPoll(
                read = { null as Long? },
                onReadFailure = { !budget.observe(nowMs, eligible = true, healthyProgress = false) },
            ) { true },
        )
        assertEquals(0L, budget.remainingMs())
        assertTrue(detector.onPlaybackTimeout(10_000L, 9_900L, true, true))
    }

    @Test
    fun `v1 null position reads keep polling without consuming v2 budget`() {
        val budget = VideoStallBudget(8_000L)

        assertTrue(continueAfterVideoPositionPoll(read = { null as Long? }) { true })
        assertTrue(continueAfterVideoPositionPoll(read = { throw IllegalStateException("transient") }) { true })
        assertEquals(8_000L, budget.remainingMs())
    }

    @Test
    fun `natural completion callback precedes forced final progress`() {
        val callbacks = mutableListOf<String>()

        dispatchNaturalVideoCompletion(
            onCompleted = { callbacks += "video_completed" },
            emitFinalProgress = { callbacks += "duration_elapsed" },
        )

        assertEquals(listOf("video_completed", "duration_elapsed"), callbacks)
    }

    @Test
    fun `video failure codes exactly match Swift wire contract`() {
        assertEquals(
            listOf("prepare_timeout", "playback_timeout", "prepare_failed", "playback_error", "first_frame_timeout"),
            VideoFailureCode.entries.map { it.wire },
        )
        assertEquals(VideoFailureCode.PREPARE_TIMEOUT, videoReadinessTimeoutCode(prepared = false))
        assertEquals(VideoFailureCode.FIRST_FRAME_TIMEOUT, videoReadinessTimeoutCode(prepared = true))
        assertEquals(VideoFailureCode.PREPARE_FAILED, videoMediaErrorCode(prepared = false))
        assertEquals(VideoFailureCode.PLAYBACK_ERROR, videoMediaErrorCode(prepared = true))
    }

    @Test
    fun `video interactions stay consumed until first frame`() {
        assertFalse(videoCtaInteractionAllowed(firstFrameRendered = false))
        assertFalse(videoMuteInteractionAllowed(firstFrameRendered = false, playerActive = true))
        assertTrue(videoCtaInteractionAllowed(firstFrameRendered = true))
        assertTrue(videoMuteInteractionAllowed(firstFrameRendered = true, playerActive = true))
        assertFalse(videoMuteInteractionAllowed(firstFrameRendered = true, playerActive = false))
        assertEquals("Unmute video", videoMuteActionLabel(muted = true))
        assertEquals("Mute video", videoMuteActionLabel(muted = false))
        assertTrue(videoMuteControlVisible(completed = false))
        assertFalse(videoMuteControlVisible(completed = true))
    }

    @Test
    fun `interstitial retained maximum position preserves midpoint across recreation`() {
        val retained = retainVideoMaxPosition(previousMs = 6_000L, currentMs = 500L)
        assertEquals(6_000L, retained)
        assertTrue(videoReachedMidpoint(retained, durationMs = 10_000L))
        assertFalse(videoReachedMidpoint(maxPositionMs = 4_999L, durationMs = 10_000L))
        assertFalse(videoReachedMidpoint(maxPositionMs = 10_000L, durationMs = 0L))
    }

    @Test
    fun `store prompt midpoint remains asset midpoint independent of shorter close gate`() {
        val assetDurationMs = 20_000L
        val closeGateMs = 5_000L
        assertTrue(closeGateMs < assetDurationMs / 2L)
        assertFalse(videoReachedMidpoint(maxPositionMs = closeGateMs, durationMs = assetDurationMs))
        assertTrue(videoReachedMidpoint(maxPositionMs = 10_000L, durationMs = assetDurationMs))
    }

    @Test
    fun `creative type is typed and unknown values remain playable`() {
        assertEquals(CreativeType.PLAYABLE, CreativeType.from(null))
        assertEquals(CreativeType.PLAYABLE, CreativeType.from("playable"))
        assertEquals(CreativeType.PLAYABLE, CreativeType.from("future_format"))
        assertEquals(CreativeType.VIDEO, CreativeType.from("VIDEO"))
    }

    @Test
    fun `playable requires rendered HTML and video requires admitted network URL`() {
        assertTrue(Creative().isRenderable("<html/>"))
        assertFalse(Creative().isRenderable("  "))
        assertTrue(Creative(type = CreativeType.VIDEO, url = "https://cdn.example/video.mp4").isRenderable(null))
        assertFalse(Creative(type = CreativeType.VIDEO, url = "data:video/mp4,x").isRenderable("<html/>"))
        assertNull(admittedVideoUrl("file:///tmp/video.mp4"))
        assertNull(admittedVideoUrl("javascript:alert(1)"))
    }

    @Test
    fun `video close gate is bounded by configured delay and asset duration`() {
        assertEquals(5_000L, videoCloseGateMs(delaySeconds = 5, durationMs = 20_000L))
        assertEquals(3_000L, videoCloseGateMs(delaySeconds = 5, durationMs = 3_000L))
        assertEquals(60_000L, videoCloseGateMs(delaySeconds = 999, durationMs = 120_000L))
        assertEquals(0L, videoCloseGateMs(delaySeconds = -1, durationMs = 10_000L))
        assertEquals(3, closeGateSecondsLeft(elapsedMs = 2_001L, requiredMs = 5_000L))
        assertEquals(0, closeGateSecondsLeft(elapsedMs = 5_001L, requiredMs = 5_000L))
    }

    @Test
    fun `rewarded video duration gate handles unknown and known durations`() {
        assertFalse(rewardedVideoDurationGateReached(4_999L, 5, durationMs = 0L))
        assertTrue(rewardedVideoDurationGateReached(5_000L, 5, durationMs = 0L))
        assertTrue(rewardedVideoDurationGateReached(5_000L, 5, durationMs = -1L))

        assertFalse(rewardedVideoDurationGateReached(2_999L, 5, durationMs = 3_000L))
        assertTrue(rewardedVideoDurationGateReached(3_000L, 5, durationMs = 3_000L))

        assertFalse(rewardedVideoDurationGateReached(4_999L, 5, durationMs = 20_000L))
        assertTrue(rewardedVideoDurationGateReached(5_000L, 5, durationMs = 20_000L))
    }

    @Test
    fun `video played time follows monotonic positions and ignores stalls`() {
        val accumulator = VideoPositionAccumulator()
        assertEquals(100L, accumulator.sample(100L).advancedMs)
        assertEquals(0L, accumulator.sample(100L).advancedMs)
        assertEquals(250L, accumulator.sample(350L).advancedMs)
        assertEquals(0L, accumulator.sample(300L).advancedMs)
        assertEquals(100L, accumulator.sample(400L).advancedMs)
        assertEquals(450L, accumulator.totalPlayedMs)
        assertEquals(550L, accumulator.complete(1_000L).advancedMs)
        assertEquals(1_000L, accumulator.totalPlayedMs)

        val resumed = VideoPositionAccumulator(initialPlayedMs = 5_000L)
        assertEquals(1_000L, resumed.sample(1_000L).advancedMs)
        assertEquals(2_000L, resumed.complete(3_000L).advancedMs)
        assertEquals(8_000L, resumed.totalPlayedMs)
    }

    @Test
    fun `render attempt admits one current first frame and rejects stale callbacks`() {
        val gate = RenderAttemptGate()
        val stale = gate.begin()
        val current = gate.begin()

        assertFalse(gate.ready(stale))
        assertTrue(gate.isPending(current))
        assertTrue(gate.ready(current))
        assertFalse(gate.ready(current))
        assertFalse(gate.fail(current))

        val timedOut = gate.begin()
        assertTrue(gate.fail(timedOut))
        assertFalse(gate.ready(timedOut))
    }
}
