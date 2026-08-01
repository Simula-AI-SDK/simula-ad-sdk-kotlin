package ad.simula.ad.sdk.minigame

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Startup-prewarm memory policy: skip only on platform-reported low-RAM devices; normal
 * pooling after an ad request is never gated.
 */
class WebViewPoolStartupPrewarmTest {

    @Test
    fun `low-RAM device skips startup prewarm`() {
        assertTrue(shouldSkipStartupPrewarm(isLowRamDevice = true))
    }

    @Test
    fun `normal device prewarms at startup`() {
        assertFalse(shouldSkipStartupPrewarm(isLowRamDevice = false))
    }

    @Test
    fun `unknown memory class does not skip`() {
        // ActivityManager unavailable → fail open toward prewarming (the historical behavior).
        assertFalse(shouldSkipStartupPrewarm(isLowRamDevice = null))
    }
}

/** Idle-pool trim policy: trim on real pressure, never on mere backgrounding (UI_HIDDEN). */
class WebViewPoolTrimTest {

    @Test
    fun `trims on real pressure levels`() {
        assertTrue(shouldTrimIdlePool(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(shouldTrimIdlePool(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(shouldTrimIdlePool(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertTrue(shouldTrimIdlePool(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun `does not trim on UI_HIDDEN (backgrounding keeps the warm pool)`() {
        assertFalse(shouldTrimIdlePool(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
    }

    @Test
    fun `does not trim below the pressure threshold`() {
        assertFalse(shouldTrimIdlePool(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
    }
}
