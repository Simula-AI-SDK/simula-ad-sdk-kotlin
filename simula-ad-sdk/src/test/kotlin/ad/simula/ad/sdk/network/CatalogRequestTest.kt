package ad.simula.ad.sdk.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the catalog request URL carries `session_id` when available and omits it otherwise.
 * Pure URL construction — no network.
 */
class CatalogRequestTest {

    @Test
    fun `catalog url appends session_id when present`() {
        val url = SimulaApiClient.catalogUrl("sess_9")
        assertTrue(url.endsWith("/minigames/catalog?session_id=sess_9"))
    }

    @Test
    fun `catalog url omits session_id when null`() {
        val url = SimulaApiClient.catalogUrl(null)
        assertTrue(url.endsWith("/minigames/catalog"))
        assertFalse(url.contains("session_id"))
    }

    @Test
    fun `catalog url omits session_id when blank`() {
        assertFalse(SimulaApiClient.catalogUrl("   ").contains("session_id"))
    }

    @Test
    fun `catalog url encodes special characters in session_id`() {
        val url = SimulaApiClient.catalogUrl("a b&c")
        // URLEncoder (form-encoding): space -> '+', '&' -> '%26'.
        assertTrue(url.endsWith("?session_id=a+b%26c"))
        assertFalse(url.contains("a b&c"))
    }
}
