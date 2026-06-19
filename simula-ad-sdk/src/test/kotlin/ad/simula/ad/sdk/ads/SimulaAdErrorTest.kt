package ad.simula.ad.sdk.ads

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the user-facing [SimulaAdError] messages. These are kept verbatim-aligned with the Swift
 * SDK's `SimulaAdError` so the two platforms surface identical diagnostics (platform-specific API
 * names — `onAdLoaded` vs `didLoad`, Activity vs window scene — aside).
 */
class SimulaAdErrorTest {

    @Test
    fun `aligned error messages match the cross-platform contract`() {
        assertEquals(
            "SimulaAds is not initialized — call SimulaAds.initialize() first.",
            SimulaAdError.NotInitialized.message,
        )
        assertEquals(
            "Could not create a session. Check the API key and network connection.",
            SimulaAdError.NoSession.message,
        )
        assertEquals(
            "No ad available to show right now (no fill).",
            SimulaAdError.NoFill.message,
        )
        assertEquals(
            "Ad not ready — call load() first and wait for the loaded callback before show().",
            SimulaAdError.NotReady.message,
        )
        assertEquals(
            "An interstitial is already showing.",
            SimulaAdError.AlreadyShowing.message,
        )
        assertEquals(
            "Network error while loading the ad — check the connection and call load() again.",
            SimulaAdError.Network(null).message,
        )
        assertEquals(
            "Ad unit id is not registered for this app — check the ad unit id in your Simula dashboard.",
            SimulaAdError.AdUnitNotFound.message,
        )
    }
}
