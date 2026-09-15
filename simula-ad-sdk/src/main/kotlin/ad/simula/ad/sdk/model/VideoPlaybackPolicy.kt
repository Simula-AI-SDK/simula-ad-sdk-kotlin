package ad.simula.ad.sdk.model

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

internal fun retainVideoMaxPosition(previousMs: Long, currentMs: Long): Long =
    maxOf(previousMs.coerceAtLeast(0L), currentMs.coerceAtLeast(0L))

internal fun videoReachedMidpoint(maxPositionMs: Long, durationMs: Long): Boolean =
    durationMs > 0L && maxPositionMs.coerceAtLeast(0L) >= durationMs / 2L

/** Converts MediaPlayer's position into monotonic played time without trusting Started wall time. */
internal class VideoPositionAccumulator(initialPlayedMs: Long = 0L) {
    private var lastPositionMs: Long? = null
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
        totalPlayedMs += advancedMs
        return VideoPositionSample(positionMs, advancedMs, totalPlayedMs)
    }

    fun complete(rawDurationMs: Long): VideoPositionSample {
        val durationMs = rawDurationMs.coerceAtLeast(0L)
        val advancedMs = (durationMs - totalPlayedMs).coerceAtLeast(0L)
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
