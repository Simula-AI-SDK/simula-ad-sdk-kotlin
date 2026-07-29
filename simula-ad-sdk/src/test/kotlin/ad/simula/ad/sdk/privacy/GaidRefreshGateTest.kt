package ad.simula.ad.sdk.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the GAID refresh gate ([shouldReadGaidNow]): first call always reads, consent-gate
 * flips force an immediate re-read, the TTL throttles steady-state foreground refreshes,
 * the disabled path always proceeds so a previously collected id gets cleared, and an
 * enabled-but-unattached context skips WITHOUT stamping so the next trigger retries.
 */
class GaidRefreshGateTest {

    private val ttlMs = 4 * 60 * 60 * 1000L // 4h, mirrors GAID_REFRESH_TTL_MS

    @Test
    fun `first call always reads (no previous gate)`() {
        assertTrue(shouldReadGaidNow(enabled = true, contextAttached = true, lastEnabled = null, lastRefreshAtMs = 0L, nowMs = 1_000L, ttlMs = ttlMs))
        assertTrue(shouldReadGaidNow(enabled = false, contextAttached = true, lastEnabled = null, lastRefreshAtMs = 0L, nowMs = 1_000L, ttlMs = ttlMs))
    }

    @Test
    fun `same gate within TTL skips the read`() {
        assertFalse(shouldReadGaidNow(enabled = true, contextAttached = true, lastEnabled = true, lastRefreshAtMs = 1_000L, nowMs = 1_000L + ttlMs - 1, ttlMs = ttlMs))
    }

    @Test
    fun `same gate re-reads once the TTL expires`() {
        assertTrue(shouldReadGaidNow(enabled = true, contextAttached = true, lastEnabled = true, lastRefreshAtMs = 1_000L, nowMs = 1_000L + ttlMs, ttlMs = ttlMs))
    }

    @Test
    fun `a gate change forces an immediate read regardless of TTL`() {
        assertTrue(shouldReadGaidNow(enabled = false, contextAttached = true, lastEnabled = true, lastRefreshAtMs = 1_000L, nowMs = 2_000L, ttlMs = ttlMs))
        assertTrue(shouldReadGaidNow(enabled = true, contextAttached = true, lastEnabled = false, lastRefreshAtMs = 1_000L, nowMs = 2_000L, ttlMs = ttlMs))
    }

    @Test
    fun `disabled collection always proceeds so the id is cleared`() {
        assertTrue(shouldReadGaidNow(enabled = false, contextAttached = true, lastEnabled = false, lastRefreshAtMs = 1_000L, nowMs = 2_000L, ttlMs = ttlMs))
    }

    @Test
    fun `enabled without an attached context skips so the next trigger retries`() {
        // First-ever trigger: must not stamp a null read that would throttle the real one.
        assertFalse(shouldReadGaidNow(enabled = true, contextAttached = false, lastEnabled = null, lastRefreshAtMs = 0L, nowMs = 1_000L, ttlMs = ttlMs))
        // Even after a prior successful read, a no-context trigger must not refresh the stamp.
        assertFalse(shouldReadGaidNow(enabled = true, contextAttached = false, lastEnabled = true, lastRefreshAtMs = 1_000L, nowMs = 1_000L + ttlMs, ttlMs = ttlMs))
    }

    @Test
    fun `disabled without an attached context still proceeds so the id is cleared`() {
        assertTrue(shouldReadGaidNow(enabled = false, contextAttached = false, lastEnabled = true, lastRefreshAtMs = 1_000L, nowMs = 2_000L, ttlMs = ttlMs))
    }
}
