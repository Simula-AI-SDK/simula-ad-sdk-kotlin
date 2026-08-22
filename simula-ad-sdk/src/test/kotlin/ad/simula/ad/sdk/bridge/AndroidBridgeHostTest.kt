package ad.simula.ad.sdk.bridge

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidBridgeHostTest {

    @Test
    fun orientationMappingIsPreserved() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            requestedOrientationFor("portrait"),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            requestedOrientationFor("landscape"),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            requestedOrientationFor("auto"),
        )
        assertNull(requestedOrientationFor("invalid"))
    }

    @Test
    fun orientationAssignmentFailureIsAbsorbedAndReported() {
        val failures = mutableListOf<String>()

        applyRequestedOrientation(
            orientation = "portrait",
            assign = { throw IllegalStateException("translucent API 26 activity") },
            recordFailure = { failures += it },
        )

        assertEquals(listOf("IllegalStateException"), failures)
    }

    @Test
    fun creativeAudioStateUsesMediaVolumePercentage() {
        assertEquals(CreativeAudioState(muted = true, volume = 0), creativeAudioState(0, 15))
        assertEquals(CreativeAudioState(muted = false, volume = 47), creativeAudioState(7, 15))
        assertEquals(CreativeAudioState(muted = false, volume = 100), creativeAudioState(15, 15))
        assertEquals(CreativeAudioState(muted = false, volume = 100), creativeAudioState(20, 15))
    }

    @Test
    fun creativeAudioStateDegradesSafelyForUnavailableLevels() {
        assertEquals(CreativeAudioState(muted = true, volume = 0), creativeAudioState(null, 15))
        assertEquals(CreativeAudioState(muted = true, volume = 0), creativeAudioState(7, null))
        assertEquals(CreativeAudioState(muted = true, volume = 0), creativeAudioState(7, 0))
        assertEquals(CreativeAudioState(muted = true, volume = 0), creativeAudioState(-1, 15))
    }
}
