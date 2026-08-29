package ad.simula.ad.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the catalog always uses the session-independent all-games endpoint.
 * Pure URL construction — no network.
 */
class CatalogRequestTest {

    @Test
    fun `catalog url is independent of session`() {
        val expected = "https://simula-api-701226639755.us-central1.run.app/minigames/catalogv2"

        assertEquals(expected, SimulaApiClient.catalogUrl("sess_9"))
        assertEquals(expected, SimulaApiClient.catalogUrl(null))
        assertEquals(expected, SimulaApiClient.catalogUrl("a b&c"))
    }
}
