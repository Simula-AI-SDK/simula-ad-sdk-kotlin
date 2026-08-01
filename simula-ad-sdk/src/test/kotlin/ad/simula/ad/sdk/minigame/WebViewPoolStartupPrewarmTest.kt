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
