package ad.simula.ad.sdk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreativePolicyTest {
    @Test
    fun `video interactions stay consumed until first frame`() {
        assertFalse(videoCtaInteractionAllowed(firstFrameRendered = false))
        assertFalse(videoMuteInteractionAllowed(firstFrameRendered = false, playerActive = true))
        assertTrue(videoCtaInteractionAllowed(firstFrameRendered = true))
        assertTrue(videoMuteInteractionAllowed(firstFrameRendered = true, playerActive = true))
        assertFalse(videoMuteInteractionAllowed(firstFrameRendered = true, playerActive = false))
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
