package ad.simula.ad.sdk.minigame

import ad.simula.ad.sdk.nativead.retainedIdleEvictionKeys
import org.junit.Assert.assertEquals
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
    fun `prewarm decisions and triggers stay low cardinality`() {
        assertEquals(WebViewPrewarmDecision.CONSTRAINED, webViewPrewarmDecision(0, 0, 10L, 0L))
        assertEquals(WebViewPrewarmDecision.FULL, webViewPrewarmDecision(1, 1, 10L, 0L))
        assertEquals(WebViewPrewarmDecision.COOLDOWN, webViewPrewarmDecision(1, 0, 9L, 10L))
        assertEquals(WebViewPrewarmDecision.WARM, webViewPrewarmDecision(1, 0, 10L, 10L))
        assertEquals("startup", canonicalWebViewPrewarmTrigger("startup"))
        assertEquals("unspecified", canonicalWebViewPrewarmTrigger("host-value-with-id-123"))
    }

    @Test
    fun `retained cap eviction is LRU and always preserves attached views`() {
        val sessions = listOf(
            "attached-oldest" to true,
            "idle-oldest" to false,
            "attached-newer" to true,
            "idle-newest" to false,
        )

        assertEquals(listOf("idle-oldest"), retainedIdleEvictionKeys(sessions, maxRetained = 3))
        assertEquals(
            listOf("idle-oldest", "idle-newest"),
            retainedIdleEvictionKeys(sessions, maxRetained = 0),
        )
        assertFalse(retainedIdleEvictionKeys(sessions, maxRetained = 0).contains("attached-oldest"))
        assertFalse(retainedIdleEvictionKeys(sessions, maxRetained = 0).contains("attached-newer"))
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
