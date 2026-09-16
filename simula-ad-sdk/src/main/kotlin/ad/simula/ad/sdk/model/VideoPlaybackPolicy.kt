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

internal fun videoCtaInteractionAllowed(firstFrameRendered: Boolean): Boolean = firstFrameRendered

internal fun videoMuteInteractionAllowed(
    firstFrameRendered: Boolean,
    playerActive: Boolean,
): Boolean = firstFrameRendered && playerActive

internal fun videoMuteActionLabel(muted: Boolean): String = if (muted) "Unmute video" else "Mute video"

internal fun videoMuteControlVisible(completed: Boolean): Boolean = !completed

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
