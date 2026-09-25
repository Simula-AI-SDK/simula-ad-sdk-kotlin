package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.VideoSegment

/** At most three stitched clips; state belongs to the presentation, surviving Activity recreation. */
internal class VideoSegmentTelemetryState {
    private val started = BooleanArray(3)
    private val midpoint = BooleanArray(3)
    private val completed = BooleanArray(3)
    private val mutedSeconds = DoubleArray(3)
    private val unmutedSeconds = DoubleArray(3)
    private var previousPosition = 0.0

    fun update(
        segments: List<VideoSegment>, position: Double, advanced: Double, muted: Boolean,
    ): List<VideoSegmentTelemetryEvent> {
        if (!position.isFinite() || position < previousPosition) return emptyList()
        val delta = position - previousPosition
        val watched = advanced.takeIf { it.isFinite() }?.coerceIn(0.0, delta) ?: 0.0
        val events = ArrayList<VideoSegmentTelemetryEvent>(9)
        segments.take(3).forEachIndexed { index, segment ->
            val duration = segment.endSeconds - segment.startSeconds
            if (!duration.isFinite() || duration <= 0 || position < segment.startSeconds) return@forEachIndexed
            fun event(stage: String, localPosition: Double) = VideoSegmentTelemetryEvent(
                segment, stage, localPosition.coerceIn(0.0, duration), duration,
                mutedSeconds[index], unmutedSeconds[index],
            )
            if (!started[index]) {
                started[index] = true
                events += event("video_start", 0.0)
            }
            val overlap = (minOf(position, segment.endSeconds) -
                maxOf(previousPosition, segment.startSeconds)).coerceAtLeast(0.0)
            val clipWatch = if (delta > 0) watched * overlap / delta else 0.0
            if (muted) mutedSeconds[index] += clipWatch else unmutedSeconds[index] += clipWatch
            val localPosition = position - segment.startSeconds
            if (!midpoint[index] && localPosition >= duration / 2) {
                midpoint[index] = true
                events += event("video_duration", localPosition)
            }
            if (!completed[index] && position >= segment.endSeconds) {
                completed[index] = true
                events += event("video_complete", duration)
            }
        }
        previousPosition = position
        return events
    }
}

internal data class VideoSegmentTelemetryEvent(
    val segment: VideoSegment,
    val stage: String,
    val position: Double,
    val duration: Double,
    val mutedSeconds: Double,
    val unmutedSeconds: Double,
)
