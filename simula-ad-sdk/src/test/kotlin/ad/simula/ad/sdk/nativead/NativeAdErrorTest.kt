package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.ads.SimulaAdError
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tier-0 pure-logic tests for the native error taxonomy: telemetry codes + the internal mapping from
 * the shared [SimulaAdError] (what the load path throws) to the scoped [NativeAdError] surface. */
class NativeAdErrorTest {

    @Test
    fun `telemetry codes match the shared taxonomy`() {
        assertEquals("not_initialized", NativeAdError.NotInitialized.telemetryCode())
        assertEquals("no_session", NativeAdError.NoSession.telemetryCode())
        assertEquals("no_fill", NativeAdError.NoFill.telemetryCode())
        assertEquals("network", NativeAdError.Network.telemetryCode())
    }

    @Test
    fun `maps the four native load-path SimulaAdError cases`() {
        assertEquals(NativeAdError.NotInitialized, SimulaAdError.NotInitialized.toNativeAdError())
        assertEquals(NativeAdError.NoSession, SimulaAdError.NoSession.toNativeAdError())
        assertEquals(NativeAdError.NoFill, SimulaAdError.NoFill.toNativeAdError())
        assertEquals(NativeAdError.Network, SimulaAdError.Network(null).toNativeAdError())
    }

    @Test
    fun `maps non-native SimulaAdError cases defensively to Network`() {
        assertEquals(NativeAdError.Network, SimulaAdError.NotReady.toNativeAdError())
        assertEquals(NativeAdError.Network, SimulaAdError.Stale.toNativeAdError())
        assertEquals(NativeAdError.Network, SimulaAdError.AlreadyShowing.toNativeAdError())
        assertEquals(NativeAdError.Network, SimulaAdError.NoPresentationContext.toNativeAdError())
    }
}
