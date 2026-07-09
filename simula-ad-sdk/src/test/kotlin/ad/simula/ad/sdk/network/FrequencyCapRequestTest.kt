package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.ads.SimulaAds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the frequency-cap status request URL carries `ad_unit_id` always, and `ppid`/
 * `session_id` only when present. Pure URL construction — no network.
 */
class FrequencyCapRequestTest {

    @Test
    fun `url always carries ad_unit_id`() {
        val url = SimulaApiClient.frequencyCapUrl("unit_1", null, null)
        assertTrue(url.endsWith("/frequency-cap/status?ad_unit_id=unit_1"))
    }

    @Test
    fun `url appends ppid and session_id when present`() {
        val url = SimulaApiClient.frequencyCapUrl("unit_1", "user_9", "sess_9")
        assertTrue(url.contains("ad_unit_id=unit_1"))
        assertTrue(url.contains("&ppid=user_9"))
        assertTrue(url.contains("&session_id=sess_9"))
    }

    @Test
    fun `url omits blank ppid and session_id`() {
        val url = SimulaApiClient.frequencyCapUrl("unit_1", "   ", "")
        assertFalse(url.contains("ppid"))
        assertFalse(url.contains("session_id"))
    }

    @Test
    fun `url encodes special characters`() {
        val url = SimulaApiClient.frequencyCapUrl("unit 1", "a b&c", null)
        assertTrue(url.contains("ad_unit_id=unit+1"))
        assertTrue(url.contains("ppid=a+b%26c"))
    }

    // ── session-consistency gate (stale-session identity fix) ──────────────────

    @Test
    fun `session id is sent when it matches the checked ppid`() {
        assertEquals("sess_1", SimulaAds.consistentSessionId("sess_1", "user_1", "user_1"))
    }

    @Test
    fun `session id is dropped when identity diverges (login or switch)`() {
        // Session still represents user_1 but we're checking user_2 (mid-session switch/login).
        assertNull(SimulaAds.consistentSessionId("sess_1", "user_1", "user_2"))
    }

    @Test
    fun `session id is dropped after logout when session still holds prior user`() {
        // ppid cleared (logout) but the server session can't be cleared, so it still holds user_1.
        assertNull(SimulaAds.consistentSessionId("sess_1", "user_1", null))
    }

    @Test
    fun `anonymous session matches anonymous check`() {
        assertEquals("sess_1", SimulaAds.consistentSessionId("sess_1", null, null))
    }

    @Test
    fun `no session id yields null regardless of identity`() {
        assertNull(SimulaAds.consistentSessionId(null, null, null))
        assertNull(SimulaAds.consistentSessionId(null, "user_1", "user_1"))
    }
}
