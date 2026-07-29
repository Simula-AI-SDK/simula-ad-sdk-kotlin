package ad.simula.ad.sdk.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the GAID refresh gate ([shouldReadGaidNow]): first call always reads, consent-gate
 * flips force an immediate re-read, the TTL throttles steady-state foreground refreshes, and
 * the disabled path always proceeds so a previously collected id gets cleared.
 */
class GaidRefreshGateTest {

    private val ttlMs = 4 * 60 * 60 * 1000L // 4h, mirrors GAID_REFRESH_TTL_MS

    @Test
    fun `first call always reads (no previous gate)`() {
        assertTrue(shouldReadGaidNow(enabled = true, lastEnabled = null, lastRefreshAtMs = 0L, nowMs = 1_000L, ttlMs = ttlMs))
        assertTrue(shouldReadGaidNow(enabled = false, lastEnabled = null, lastRefreshAtMs = 0L, nowMs = 1_000L, ttlMs = ttlMs))
    }

    @Test
    fun `same gate within TTL skips the read`() {
        assertFalse(shouldReadGaidNow(enabled = true, lastEnabled = true, lastRefreshAtMs = 1_000L, nowMs = 1_000L + ttlMs - 1, ttlMs = ttlMs))
    }

    @Test
    fun `same gate re-reads once the TTL expires`() {
        assertTrue(shouldReadGaidNow(enabled = true, lastEnabled = true, lastRefreshAtMs = 1_000L, nowMs = 1_000L + ttlMs, ttlMs = ttlMs))
    }

    @Test
    fun `a gate change forces an immediate read regardless of TTL`() {
        assertTrue(shouldReadGaidNow(enabled = false, lastEnabled = true, lastRefreshAtMs = 1_000L, nowMs = 2_000L, ttlMs = ttlMs))
        assertTrue(shouldReadGaidNow(enabled = true, lastEnabled = false, lastRefreshAtMs = 1_000L, nowMs = 2_000L, ttlMs = ttlMs))
    }

    @Test
    fun `disabled collection always proceeds so the id is cleared`() {
        assertTrue(shouldReadGaidNow(enabled = false, lastEnabled = false, lastRefreshAtMs = 1_000L, nowMs = 2_000L, ttlMs = ttlMs))
    }
}
