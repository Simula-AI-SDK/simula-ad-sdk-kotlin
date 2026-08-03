package ad.simula.ad.sdk.bridge

import android.content.pm.ActivityInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidBridgeHostTest {

    @Test
    fun orientationAssignmentIsSkippedOnExactlyApi26() {
        assertNull(requestedOrientationFor("portrait", Build.VERSION_CODES.O))
        assertNull(requestedOrientationFor("landscape", Build.VERSION_CODES.O))
        assertNull(requestedOrientationFor("auto", Build.VERSION_CODES.O))
    }

    @Test
    fun orientationMappingIsPreservedOnOtherVersions() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            requestedOrientationFor("portrait", Build.VERSION_CODES.O - 1),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            requestedOrientationFor("landscape", Build.VERSION_CODES.O + 1),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            requestedOrientationFor("auto", Build.VERSION_CODES.O + 1),
        )
        assertNull(requestedOrientationFor("invalid", Build.VERSION_CODES.O + 1))
    }
}
