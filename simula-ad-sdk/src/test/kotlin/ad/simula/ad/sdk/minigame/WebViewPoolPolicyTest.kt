package ad.simula.ad.sdk.minigame

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewPoolPolicyTest {

    @Test
    fun `retains only when capacity is available and cooldown has elapsed`() {
        assertTrue(canRetainPooledWebView(maxIdle = 1, idleCount = 0, nowMs = 10_000L, blockedUntilMs = 10_000L))
        assertFalse(canRetainPooledWebView(maxIdle = 1, idleCount = 1, nowMs = 10_000L, blockedUntilMs = 0L))
        assertFalse(canRetainPooledWebView(maxIdle = 1, idleCount = 0, nowMs = 9_999L, blockedUntilMs = 10_000L))
    }

    @Test
    fun `zero-capacity constrained device never retains an idle WebView`() {
        assertFalse(canRetainPooledWebView(maxIdle = 0, idleCount = 0, nowMs = 10_000L, blockedUntilMs = 0L))
    }

    @Test
    fun `memory policy identifies low ram devices seen in renderer OOM telemetry`() {
        val gib = 1024L * 1024 * 1024

        assertTrue(isWebViewMemoryConstrained(isLowRamDevice = false, totalRamBytes = 3L * gib, maxHeapBytes = gib))
        assertTrue(isWebViewMemoryConstrained(isLowRamDevice = false, totalRamBytes = 8L * gib, maxHeapBytes = 256L * 1024 * 1024))
        assertTrue(isWebViewMemoryConstrained(isLowRamDevice = true, totalRamBytes = 8L * gib, maxHeapBytes = gib))
        assertFalse(isWebViewMemoryConstrained(isLowRamDevice = false, totalRamBytes = 8L * gib, maxHeapBytes = gib))
    }
}
