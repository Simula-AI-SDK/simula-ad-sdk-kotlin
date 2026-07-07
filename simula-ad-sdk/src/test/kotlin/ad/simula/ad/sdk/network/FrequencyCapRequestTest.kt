package ad.simula.ad.sdk.network

import org.junit.Assert.assertFalse
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
}
