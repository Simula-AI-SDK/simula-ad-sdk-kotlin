package ad.simula.ad.sdk.model

internal data class VideoAspectFitTransform(val scaleX: Float, val scaleY: Float)

internal data class VideoDimensions(val width: Int = 0, val height: Int = 0)

/** Valid dimensions replace the current source size; transient OEM zero/invalid callbacks do not. */
internal fun resolveVideoDimensions(
    current: VideoDimensions,
    candidateWidth: Int,
    candidateHeight: Int,
): VideoDimensions = if (candidateWidth > 0 && candidateHeight > 0) {
    VideoDimensions(candidateWidth, candidateHeight)
} else {
    current
}

internal fun videoAspectFitTransform(
    videoWidth: Int,
    videoHeight: Int,
    surfaceWidth: Int,
    surfaceHeight: Int,
): VideoAspectFitTransform {
    if (videoWidth <= 0 || videoHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) {
        return VideoAspectFitTransform(1f, 1f)
    }
    val videoAspect = videoWidth.toDouble() / videoHeight
    val surfaceAspect = surfaceWidth.toDouble() / surfaceHeight
    if (!videoAspect.isFinite() || !surfaceAspect.isFinite() || videoAspect <= 0.0 || surfaceAspect <= 0.0) {
        return VideoAspectFitTransform(1f, 1f)
    }
    return if (videoAspect > surfaceAspect) {
        VideoAspectFitTransform(1f, (surfaceAspect / videoAspect).toFloat().coerceIn(0f, 1f))
    } else {
        VideoAspectFitTransform((videoAspect / surfaceAspect).toFloat().coerceIn(0f, 1f), 1f)
    }
}

internal enum class VideoFailureCode(val wire: String) {
    PREPARE_TIMEOUT("prepare_timeout"),
    PLAYBACK_TIMEOUT("playback_timeout"),
    PREPARE_FAILED("prepare_failed"),
    PLAYBACK_ERROR("playback_error"),
    FIRST_FRAME_TIMEOUT("first_frame_timeout"),
}

internal enum class VideoLifecycleReason(val wire: String) {
    COMPLETED("completed"),
    FAILED("failed"),
    USER("user"),
    NO_NEXT_STEP("no_next_step"),
    NEXT_STEP_FAILED("next_step_failed"),
    NEXT_STEP_TIMEOUT("next_step_timeout"),
    BACKGROUNDED("backgrounded"),
    STORE_PRESENTED("store_presented"),
    AUDIO_INTERRUPTION("audio_interruption"),
    PLAYBACK("playback"),
}

internal fun videoReadinessTimeoutCode(prepared: Boolean): VideoFailureCode =
    if (prepared) VideoFailureCode.FIRST_FRAME_TIMEOUT else VideoFailureCode.PREPARE_TIMEOUT

internal fun videoMediaErrorCode(prepared: Boolean): VideoFailureCode =
    if (prepared) VideoFailureCode.PLAYBACK_ERROR else VideoFailureCode.PREPARE_FAILED

internal enum class VideoPreparationPhase { PREPARING, PREPARED }

internal data class VideoPreparationClaimPolicy(
    val phase: VideoPreparationPhase,
    val readinessDeadlineMs: Long,
)

internal fun videoPreparationClaimPolicy(
    phase: VideoPreparationPhase,
    originalDeadlineMs: Long,
    claimedAtMs: Long,
    totalReadinessMs: Long,
): VideoPreparationClaimPolicy = VideoPreparationClaimPolicy(
    phase = phase,
    readinessDeadlineMs = if (phase == VideoPreparationPhase.PREPARED) {
        claimedAtMs + totalReadinessMs.coerceAtLeast(0L)
    } else {
        originalDeadlineMs
    },
)

internal class VideoReadinessDeadline(private var deadlineMs: Long) {
    private var pausedRemainingMs: Long? = null

    fun remainingMs(nowMs: Long): Long = pausedRemainingMs
        ?: (deadlineMs - nowMs).coerceAtLeast(0L)

    fun pause(nowMs: Long) {
        if (pausedRemainingMs == null) pausedRemainingMs = remainingMs(nowMs)
    }

    fun resume(nowMs: Long): Long {
        val remaining = pausedRemainingMs ?: return remainingMs(nowMs)
        deadlineMs = nowMs + remaining
        pausedRemainingMs = null
        return remaining
    }
}

internal class VideoUiProgressCoalescer(private val intervalMs: Long = 250L) {
    private var lastEmissionMs: Long? = null

    fun shouldEmit(
        nowMs: Long,
        force: Boolean = false,
        gateCrossed: Boolean = false,
        midpointCrossed: Boolean = false,
    ): Boolean {
        val last = lastEmissionMs
        if (!force && !gateCrossed && !midpointCrossed && last != null && nowMs - last < intervalMs) return false
        lastEmissionMs = nowMs
        return true
    }
}

internal data class VideoPositionSample(
    val positionMs: Long,
    val advancedMs: Long,
    val totalPlayedMs: Long,
)

internal fun resolvedVideoChromeStyle(
    requested: VideoChromeStyle,
    appName: String?,
    appIconUrl: String?,
): VideoChromeStyle = when (requested) {
    VideoChromeStyle.BOTTOM_BAR,
    VideoChromeStyle.FLOATING_PILL,
    -> requested.takeIf { !appIconUrl.isNullOrBlank() } ?: VideoChromeStyle.CORNER_CTA
    VideoChromeStyle.BOTTOM_CARD,
    VideoChromeStyle.FEED_CARD,
    -> requested.takeIf { !appIconUrl.isNullOrBlank() && !appName.isNullOrBlank() }
        ?: VideoChromeStyle.CORNER_CTA
    VideoChromeStyle.CORNER_CTA,
    -> requested
}

internal data class VideoChromeObstructionClearance(
    val minimumStartFromSafeEdgeDp: Int,
    val minimumBottomFromSafeEdgeDp: Int,
)

internal fun videoChromeObstructionClearance(
    effectiveClosePosition: ClosePosition,
    resolvedStyle: VideoChromeStyle,
    bottomProgressBarObstructed: Boolean,
): VideoChromeObstructionClearance = VideoChromeObstructionClearance(
    minimumStartFromSafeEdgeDp = if (
        effectiveClosePosition == ClosePosition.BOTTOM_LEFT &&
        resolvedStyle != VideoChromeStyle.CORNER_CTA
    ) 112 else 0,
    minimumBottomFromSafeEdgeDp = if (bottomProgressBarObstructed) 26 + 4 + 8 else 0,
)

internal enum class VideoSequenceAdvance { MANUAL, WAIT_FOR_BLOCKER, ADVANCE }

internal enum class VideoPreFirstFrameFailureAction { PRESERVE_PENDING_HANDOFF, FAIL_EXPECTED_NEXT_STEP }

internal fun videoPreFirstFrameFailureAction(hasNextStep: Boolean): VideoPreFirstFrameFailureAction =
    if (hasNextStep) {
        VideoPreFirstFrameFailureAction.PRESERVE_PENDING_HANDOFF
    } else {
        VideoPreFirstFrameFailureAction.FAIL_EXPECTED_NEXT_STEP
    }

internal fun videoSequenceAdvance(
    videoPlanV2: Boolean,
    currentType: CreativeType,
    terminal: Boolean,
    clickHandoffPending: Boolean,
    storeVisitPending: Boolean,
): VideoSequenceAdvance {
    if (!videoPlanV2 || currentType != CreativeType.VIDEO || !terminal) return VideoSequenceAdvance.MANUAL
    return if (clickHandoffPending || storeVisitPending) {
        VideoSequenceAdvance.WAIT_FOR_BLOCKER
    } else {
        VideoSequenceAdvance.ADVANCE
    }
}

internal const val VIDEO_STALL_BUDGET_MS = 8_000L

/** Counts eligible foreground playback/waiting time without media or download progress. */
internal class VideoStallBudget(
    private val budgetMs: Long = VIDEO_STALL_BUDGET_MS,
) {
    private var remainingMs = budgetMs.coerceAtLeast(0L)
    private var lastEligibleMs: Long? = null

    fun observe(
        nowMs: Long,
        eligible: Boolean,
        healthyProgress: Boolean,
    ): Boolean {
        if (healthyProgress) {
            reset()
            if (eligible) lastEligibleMs = nowMs
            return false
        }
        if (!eligible) {
            lastEligibleMs = null
            return false
        }
        val previous = lastEligibleMs
        lastEligibleMs = nowMs
        if (previous != null) remainingMs = (remainingMs - (nowMs - previous).coerceAtLeast(0L)).coerceAtLeast(0L)
        return remainingMs == 0L
    }

    fun reset() {
        remainingMs = budgetMs.coerceAtLeast(0L)
        lastEligibleMs = null
    }

    fun remainingMs(): Long = remainingMs
}

internal class VideoQuartileTracker {
    private val emitted = mutableSetOf<Int>()

    fun crossed(positionMs: Long, durationMs: Long): List<Int> {
        if (positionMs < 0L || durationMs <= 0L) return emptyList()
        val percent = (positionMs.coerceAtMost(durationMs) * 100L / durationMs).toInt()
        return listOf(25, 50, 75).filter { percent >= it && emitted.add(it) }
    }
}

internal data class VideoAudioWatchTotals(
    val mutedMs: Long,
    val unmutedMs: Long,
)

internal class VideoAudioWatchAccounting {
    private var mutedMs = 0L
    private var unmutedMs = 0L

    fun add(advancedMs: Long, muted: Boolean): VideoAudioWatchTotals {
        val delta = advancedMs.coerceAtLeast(0L)
        if (muted) mutedMs += delta else unmutedMs += delta
        return totals()
    }

    fun totals(): VideoAudioWatchTotals = VideoAudioWatchTotals(mutedMs, unmutedMs)
}

internal fun effectiveVideoMuted(desiredMuted: Boolean, audioFocusHeld: Boolean): Boolean =
    desiredMuted || !audioFocusHeld

internal data class VideoAudioFocusLossPolicy(
    val desiredMuted: Boolean,
    val abandonFocus: Boolean,
)

internal fun videoAudioFocusLossPolicy(
    videoPlanV2: Boolean,
    desiredMuted: Boolean,
): VideoAudioFocusLossPolicy = VideoAudioFocusLossPolicy(
    desiredMuted = if (videoPlanV2) desiredMuted else true,
    abandonFocus = !videoPlanV2,
)

internal fun videoDesiredMutedAfterTap(effectiveMuted: Boolean): Boolean = !effectiveMuted

internal fun initialVideoDesiredMuted(
    videoPlanV2: Boolean,
    presentationDesiredMuted: Boolean,
): Boolean = if (videoPlanV2) presentationDesiredMuted else true

internal fun videoDesiredMutedAfterLifecycleDeactivation(
    videoPlanV2: Boolean,
    desiredMuted: Boolean,
): Boolean = if (videoPlanV2) desiredMuted else true

/** Presentation-owned user preference. Effective muting remains player/audio-focus owned. */
internal class VideoAudioSessionState(videoPlanV2: Boolean) {
    var desiredMuted: Boolean = !videoPlanV2
        private set
    private var preferenceChanged = false

    @Synchronized
    fun updateFromTap(videoPlanV2: Boolean, value: Boolean) {
        if (!videoPlanV2) return
        desiredMuted = value
        preferenceChanged = true
    }

    @Synchronized
    fun activateVideoPlanV2() {
        if (!preferenceChanged) desiredMuted = false
    }
}

internal class VideoInstallOverlayClock {
    private var delayMs = 0L
    private var elapsedMs = 0L
    private var lastEligibleMs: Long? = null
    var started = false
        private set
    var ready = false
        private set

    fun start(delaySeconds: Int, nowMs: Long, eligible: Boolean) {
        if (started || ready) return
        started = true
        delayMs = delaySeconds.coerceIn(0, 60) * 1_000L
        update(nowMs, eligible)
    }

    fun update(nowMs: Long, eligible: Boolean): Boolean {
        if (!started || ready) return ready
        val previous = lastEligibleMs
        if (eligible && previous != null) elapsedMs += (nowMs - previous).coerceAtLeast(0L)
        lastEligibleMs = nowMs.takeIf { eligible }
        if (elapsedMs >= delayMs) ready = true
        return ready
    }

    fun remainingMs(): Long = (delayMs - elapsedMs).coerceAtLeast(0L)
}

internal fun videoCtaInteractionAllowed(
    videoPlanV2: Boolean,
    firstFrameRendered: Boolean,
    completed: Boolean,
): Boolean = firstFrameRendered && (!videoPlanV2 || !completed)

internal fun videoMuteInteractionAllowed(
    firstFrameRendered: Boolean,
    playerActive: Boolean,
): Boolean = firstFrameRendered && playerActive

internal fun videoMuteActionLabel(muted: Boolean): String = if (muted) "Unmute video" else "Mute video"

internal fun videoMuteControlVisible(completed: Boolean): Boolean = !completed

internal enum class VideoMuteControlPlacement { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT }

internal fun videoMuteControlPlacement(
    videoPlanV2: Boolean,
    ctaEnabled: Boolean,
    effectiveClosePosition: ClosePosition,
): VideoMuteControlPlacement = when {
    !videoPlanV2 || !ctaEnabled -> VideoMuteControlPlacement.BOTTOM_RIGHT
    effectiveClosePosition == ClosePosition.TOP_LEFT -> VideoMuteControlPlacement.TOP_RIGHT
    else -> VideoMuteControlPlacement.TOP_LEFT
}

internal const val VIDEO_NEAR_END_MAX_TOLERANCE_MS = 150L

/**
 * Conservative completion inference for devices that stop at the final frame without dispatching
 * MediaPlayer.OnCompletionListener. Playing samples only arm stable near-end evidence; inference
 * requires repeated non-playing confirmation or the playback timeout observing the same final state.
 * Unknown duration, lifecycle pause, backward movement, and one-off duration changes never infer.
 */
internal class VideoNearEndCompletionDetector(
    private val maxToleranceMs: Long = VIDEO_NEAR_END_MAX_TOLERANCE_MS,
    private val requiredNotPlayingConfirmations: Int = 2,
) {
    private var lastDurationMs: Long? = null
    private var lastPositionMs: Long? = null
    private var nearEndObserved = false
    private var notPlayingConfirmations = 0
    private var notPlayingPositionMs: Long? = null
    private var inferred = false

    fun observe(
        durationMs: Long,
        positionMs: Long,
        firstFrameRendered: Boolean,
        playerActive: Boolean,
        isPlaying: Boolean,
    ): Boolean {
        if (inferred || !firstFrameRendered || durationMs <= 0L || positionMs < 0L) return false
        if (!playerActive) {
            notPlayingConfirmations = 0
            notPlayingPositionMs = null
            return false
        }
        val previousDuration = lastDurationMs
        val previousPosition = lastPositionMs
        if (previousDuration != durationMs || previousPosition == null) {
            lastDurationMs = durationMs
            lastPositionMs = positionMs
            nearEndObserved = false
            notPlayingConfirmations = 0
            notPlayingPositionMs = null
            return false
        }
        if (positionMs < previousPosition) {
            lastPositionMs = positionMs
            nearEndObserved = false
            notPlayingConfirmations = 0
            notPlayingPositionMs = null
            return false
        }
        lastPositionMs = positionMs
        val toleranceMs = minOf(maxToleranceMs.coerceAtLeast(1L), maxOf(1L, durationMs / 10L))
        val thresholdMs = (durationMs - toleranceMs).coerceAtLeast(0L)
        val nearEnd = positionMs in thresholdMs..durationMs
        if (!nearEnd) {
            nearEndObserved = false
            notPlayingConfirmations = 0
            notPlayingPositionMs = null
            return false
        }
        if (isPlaying) {
            nearEndObserved = true
            notPlayingConfirmations = 0
            notPlayingPositionMs = null
            return false
        }
        if (!nearEndObserved) {
            nearEndObserved = true
            notPlayingConfirmations = 1
            notPlayingPositionMs = positionMs
            return false
        }
        if (notPlayingPositionMs != positionMs) {
            notPlayingPositionMs = positionMs
            notPlayingConfirmations = 1
        } else {
            notPlayingConfirmations++
        }
        if (notPlayingConfirmations < requiredNotPlayingConfirmations.coerceAtLeast(1)) return false
        inferred = true
        return true
    }

    fun onPlaybackTimeout(
        durationMs: Long,
        positionMs: Long,
        firstFrameRendered: Boolean,
        playerActive: Boolean,
    ): Boolean {
        if (inferred || !firstFrameRendered || !playerActive || durationMs <= 0L) return false
        if (lastDurationMs != durationMs || !nearEndObserved || lastPositionMs != positionMs) return false
        val toleranceMs = minOf(maxToleranceMs.coerceAtLeast(1L), maxOf(1L, durationMs / 10L))
        if (positionMs !in (durationMs - toleranceMs).coerceAtLeast(0L)..durationMs) return false
        inferred = true
        return true
    }
}

internal data class VideoCompletionTransition(
    val accepted: Boolean,
    val cancelPlaybackTimeout: Boolean,
)

internal class VideoCompletionGate {
    private var completed = false

    fun complete(): VideoCompletionTransition {
        if (completed) return VideoCompletionTransition(accepted = false, cancelPlaybackTimeout = false)
        completed = true
        return VideoCompletionTransition(accepted = true, cancelPlaybackTimeout = true)
    }
}

internal fun retainVideoMaxPosition(previousMs: Long, currentMs: Long): Long =
    maxOf(previousMs.coerceAtLeast(0L), currentMs.coerceAtLeast(0L))

internal fun videoReachedMidpoint(maxPositionMs: Long, durationMs: Long): Boolean =
    durationMs > 0L && maxPositionMs.coerceAtLeast(0L) >= durationMs / 2L

/** Converts MediaPlayer's position into monotonic played time without trusting Started wall time. */
internal class VideoPositionAccumulator(initialPlayedMs: Long = 0L) {
    private var lastPositionMs: Long? = null
    private var sessionPlayedMs = 0L
    var totalPlayedMs: Long = initialPlayedMs.coerceAtLeast(0L)
        private set

    fun sample(rawPositionMs: Long): VideoPositionSample {
        val positionMs = rawPositionMs.coerceAtLeast(0L)
        val previous = lastPositionMs
        val advancedMs = when {
            previous == null -> positionMs
            positionMs > previous -> positionMs - previous
            else -> 0L
        }
        lastPositionMs = positionMs
        sessionPlayedMs += advancedMs
        totalPlayedMs += advancedMs
        return VideoPositionSample(positionMs, advancedMs, totalPlayedMs)
    }

    fun complete(rawDurationMs: Long): VideoPositionSample {
        val durationMs = rawDurationMs.coerceAtLeast(0L)
        val advancedMs = (durationMs - sessionPlayedMs).coerceAtLeast(0L)
        sessionPlayedMs += advancedMs
        totalPlayedMs += advancedMs
        lastPositionMs = maxOf(lastPositionMs ?: 0L, durationMs)
        return VideoPositionSample(durationMs, advancedMs, totalPlayedMs)
    }
}

internal enum class RenderAttemptResult { PENDING, READY, FAILED }

/** Tokenized one-shot render admission. Late callbacks from replaced/timed-out attempts are ignored. */
internal class RenderAttemptGate {
    private var generation = 0L
    private var result = RenderAttemptResult.FAILED

    fun begin(): Long {
        generation++
        result = RenderAttemptResult.PENDING
        return generation
    }

    fun ready(token: Long): Boolean = settle(token, RenderAttemptResult.READY)

    fun fail(token: Long): Boolean = settle(token, RenderAttemptResult.FAILED)

    fun isPending(token: Long): Boolean = token == generation && result == RenderAttemptResult.PENDING

    private fun settle(token: Long, terminal: RenderAttemptResult): Boolean {
        if (!isPending(token)) return false
        result = terminal
        return true
    }
}
