package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.VideoSegment
import org.junit.Assert.*
import org.junit.Test

class VideoSegmentTelemetryTest {
    private val segments = listOf(VideoSegment(1, "A", 0.0, 4.0), VideoSegment(2, "B", 4.0, 10.0))

    @Test
    fun `crossing stitch emits clip boundaries with local position duration and watch`() {
        val state = VideoSegmentTelemetryState()
        val events = state.update(segments, 0.0, 0.0, false) +
            state.update(segments, 2.0, 2.0, false) +
            state.update(segments, 6.0, 4.0, true) +
            state.update(segments, 10.0, 4.0, false)
        assertEquals(listOf("video_start", "video_duration", "video_complete", "video_start", "video_duration", "video_complete"), events.map { it.stage })
        val completes = events.filter { it.stage == "video_complete" }
        assertEquals(listOf(1, 2), completes.map { it.segment.clipIndex })
        assertEquals(listOf(4.0, 6.0), completes.map { it.duration })
        assertEquals(listOf(4.0, 6.0), completes.map { it.position })
        assertEquals(listOf(2.0, 2.0), completes.map { it.mutedSeconds })
        assertEquals(listOf(2.0, 4.0), completes.map { it.unmutedSeconds })
        assertTrue(state.update(segments, 10.0, 0.0, false).isEmpty())
        assertTrue(state.update(segments, 3.0, 0.0, false).isEmpty())
    }

    @Test
    fun `watch never counts an unsampled seek gap and state is bounded`() {
        val state = VideoSegmentTelemetryState()
        val events = state.update(segments + segments + segments, 10.0, 1.0, false)
        assertTrue(events.size <= 9)
        assertEquals(0.4, events.first { it.stage == "video_complete" }.unmutedSeconds, 0.00001)
        assertTrue(state.update(segments, Double.NaN, 1.0, false).isEmpty())
    }
}
