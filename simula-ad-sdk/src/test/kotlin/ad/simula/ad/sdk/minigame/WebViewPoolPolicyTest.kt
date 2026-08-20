package ad.simula.ad.sdk.minigame

import ad.simula.ad.sdk.nativead.retainedIdleEvictionKeys
import android.content.ComponentCallbacks2
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
        assertTrue(isWebViewRetentionEligible(nowMs = 10_000L, blockedUntilMs = 10_000L))
        assertFalse(isWebViewRetentionEligible(nowMs = 9_999L, blockedUntilMs = 10_000L))
        assertFalse(
            canRetainPooledWebView(
                maxIdle = 1,
                idleCount = 0,
                nowMs = 10_000L,
                blockedUntilMs = 0L,
                applicationActive = false,
            ),
        )
        assertFalse(isWebViewRetentionEligible(10_000L, 0L, applicationActive = false))
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
        assertEquals(
            WebViewPrewarmDecision.INACTIVE,
            webViewPrewarmDecision(1, 0, 10L, 0L, applicationActive = false),
        )
        assertEquals(
            WebViewPrewarmDecision.PRESENTATION_ACTIVE,
            webViewPrewarmDecision(1, 0, 10L, 0L, readyPresentationActive = true),
        )
        assertTrue(isReadyFullscreenPrewarmTrigger("interstitial_ready"))
        assertTrue(isReadyFullscreenPrewarmTrigger("rewarded_ready"))
        assertFalse(isReadyFullscreenPrewarmTrigger("minigame_game"))
        assertEquals("minigame_menu", canonicalWebViewPrewarmTrigger("minigame_menu"))
        assertEquals("interstitial_ready", canonicalWebViewPrewarmTrigger("interstitial_ready"))
        assertEquals("rewarded_ready", canonicalWebViewPrewarmTrigger("rewarded_ready"))
        assertEquals("unspecified", canonicalWebViewPrewarmTrigger("startup"))
        assertEquals("unspecified", canonicalWebViewPrewarmTrigger("acquire_refill"))
        assertEquals("unspecified", canonicalWebViewPrewarmTrigger("host-value-with-id-123"))
        assertEquals("interstitial", canonicalWebViewAcquireSurface("interstitial"))
        assertEquals("rewarded", canonicalWebViewAcquireSurface("rewarded"))
        assertEquals("unspecified", canonicalWebViewAcquireSurface("native-ad-id-123"))
    }

    @Test
    fun `skip diagnostics emit at most once per canonical reason`() {
        val gate = WebViewPrewarmSkipGate()

        listOf(
            WebViewPrewarmDecision.CONSTRAINED,
            WebViewPrewarmDecision.FULL,
            WebViewPrewarmDecision.COOLDOWN,
            WebViewPrewarmDecision.INACTIVE,
            WebViewPrewarmDecision.PRESENTATION_ACTIVE,
        ).forEach { decision ->
            assertTrue(gate.shouldRecord(decision))
            assertFalse(gate.shouldRecord(decision))
        }
        assertFalse(gate.shouldRecord(WebViewPrewarmDecision.WARM))
    }

    @Test
    fun `reactivation restores eligibility without clearing pressure cooldown`() {
        val state = WebViewRetentionState()
        state.suspendUntil(10_000L)
        state.markInactive()

        assertFalse(isWebViewRetentionEligible(20_000L, state.blockedUntilMs, state.applicationActive))

        state.markActive()
        assertFalse(isWebViewRetentionEligible(9_999L, state.blockedUntilMs, state.applicationActive))
        assertTrue(isWebViewRetentionEligible(10_000L, state.blockedUntilMs, state.applicationActive))
        assertEquals(10_000L, state.blockedUntilMs)
    }

    @Test
    fun `retention starts inactive until a foreground signal arrives`() {
        val state = WebViewRetentionState(initiallyActive = false)

        assertFalse(isWebViewRetentionEligible(10_000L, state.blockedUntilMs, state.applicationActive))
        state.markActive()
        assertTrue(isWebViewRetentionEligible(10_000L, state.blockedUntilMs, state.applicationActive))
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

    @Test
    fun `ui hidden evicts idle views without starting pressure cooldown`() {
        assertEquals(WebViewTrimAction.EVICT_IDLE, webViewTrimAction(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(
            WebViewTrimAction.EVICT_IDLE_AND_COOLDOWN,
            webViewTrimAction(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW),
        )
        assertEquals(
            WebViewTrimAction.EVICT_IDLE_AND_COOLDOWN,
            webViewTrimAction(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL),
        )
        assertEquals(
            WebViewTrimAction.EVICT_IDLE_AND_COOLDOWN,
            webViewTrimAction(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND),
        )
        assertEquals(WebViewTrimAction.NONE, webViewTrimAction(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
    }
}
