package ad.simula.ad.sdk.telemetry

import ad.simula.ad.sdk.ads.VIDEO_STAGE_COMPLETE
import ad.simula.ad.sdk.ads.VIDEO_STAGE_CLOSE
import ad.simula.ad.sdk.ads.VIDEO_STAGE_DURATION
import ad.simula.ad.sdk.ads.VIDEO_STAGE_FAIL
import ad.simula.ad.sdk.ads.VIDEO_STAGE_HANDOFF
import ad.simula.ad.sdk.ads.VIDEO_STAGE_MUTE_TOGGLE
import ad.simula.ad.sdk.ads.VIDEO_STAGE_PAUSE
import ad.simula.ad.sdk.ads.VIDEO_STAGE_RESUME
import ad.simula.ad.sdk.ads.VIDEO_STAGE_START
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VideoTelemetrySerializationTest {
    @Test
    fun `video lifecycle event names remain cross platform stable`() {
        assertEquals(
            listOf(
                "video_start", "video_duration", "video_complete", "video_mute_toggle",
                "video_pause", "video_resume", "video_close", "video_handoff", "video_fail",
            ),
            listOf(
                VIDEO_STAGE_START, VIDEO_STAGE_DURATION, VIDEO_STAGE_COMPLETE, VIDEO_STAGE_MUTE_TOGGLE,
                VIDEO_STAGE_PAUSE, VIDEO_STAGE_RESUME, VIDEO_STAGE_CLOSE, VIDEO_STAGE_HANDOFF, VIDEO_STAGE_FAIL,
            ),
        )
    }

    @Test
    fun `video common fields and aggregate watch seconds use exact wire keys`() {
        val event = TelemetryEvent(
            type = TYPE_LIFECYCLE,
            name = "video_complete",
            eventId = "event-1",
            timestamp = 10L,
            clipIndex = 2,
            muted = false,
            impressionId = "imp-1",
            style = "bottom_card",
            skoverlayEnabled = true,
            skoverlayDelaySeconds = 3,
            videoPositionS = 5.75,
            pool = "ugc",
            durationS = 12.0,
            watchedS = 5.75,
            secondsUnmuted = 4.5,
            secondsMuted = 1.25,
            quartile = 75,
            reason = "completed",
            pausedMs = 320.0,
            msToNextStepReady = 45.0,
            secondsSinceVideoStart = 5.9,
            on = "next_step",
            visibleS = 5.5,
            error = "none",
        )
        val root = Json.parseToJsonElement(Json.encodeToString(event)).jsonObject

        assertEquals(2, root.getValue("clip_index").jsonPrimitive.int)
        assertFalse(root.getValue("muted").jsonPrimitive.boolean)
        assertEquals("imp-1", root.getValue("impression_id").jsonPrimitive.content)
        assertEquals("bottom_card", root.getValue("style").jsonPrimitive.content)
        assertEquals(true, root.getValue("skoverlay_enabled").jsonPrimitive.boolean)
        assertEquals(3, root.getValue("skoverlay_delay_seconds").jsonPrimitive.int)
        assertEquals(5.75, root.getValue("video_position_s").jsonPrimitive.double, 0.0)
        assertEquals("ugc", root.getValue("pool").jsonPrimitive.content)
        assertEquals(12.0, root.getValue("duration_s").jsonPrimitive.double, 0.0)
        assertEquals(5.75, root.getValue("watched_s").jsonPrimitive.double, 0.0)
        assertEquals(4.5, root.getValue("seconds_unmuted").jsonPrimitive.double, 0.0)
        assertEquals(1.25, root.getValue("seconds_muted").jsonPrimitive.double, 0.0)
        assertEquals(75, root.getValue("quartile").jsonPrimitive.int)
        assertEquals("completed", root.getValue("reason").jsonPrimitive.content)
        assertEquals(320.0, root.getValue("paused_ms").jsonPrimitive.double, 0.0)
        assertEquals(45.0, root.getValue("ms_to_next_step_ready").jsonPrimitive.double, 0.0)
        assertEquals(5.9, root.getValue("seconds_since_video_start").jsonPrimitive.double, 0.0)
        assertEquals("next_step", root.getValue("on").jsonPrimitive.content)
        assertEquals(5.5, root.getValue("visible_s").jsonPrimitive.double, 0.0)
        assertEquals("none", root.getValue("error").jsonPrimitive.content)
        assertFalse(root.containsKey("video_pool"))
        assertFalse(root.containsKey("video_style"))
        assertFalse(root.containsKey("muted_watch_ms"))
        assertFalse(root.containsKey("unmuted_watch_ms"))
    }

    @Test
    fun `legacy lifecycle serialization remains free of v2 fields`() {
        val event = TelemetryEvent(TYPE_LIFECYCLE, "video_start", "event-1", 10L)
        val root = Json.parseToJsonElement(Json.encodeToString(event)).jsonObject

        assertFalse(root.containsKey("clip_index"))
        assertFalse(root.containsKey("muted"))
        assertFalse(root.containsKey("pool"))
        assertFalse(root.containsKey("seconds_muted"))
    }
}
