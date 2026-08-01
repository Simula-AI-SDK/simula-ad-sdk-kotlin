package ad.simula.ad.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimulaHttpRouteTest {
    @Test
    fun `contract dynamic routes replace identifiers`() {
        val cases = mapOf(
            "/impressions/serve-123/shown" to "/impressions/:id/shown",
            "/impressions/serve-123/seen" to "/impressions/:id/seen",
            "/impressions/serve-123/click" to "/impressions/:id/click",
            "/impressions/serve-123/interest" to "/impressions/:id/interest",
            "/impressions/serve-123/report" to "/impressions/:id/report",
            "/load/fallbacks/serve-123" to "/load/fallbacks/:id",
        )

        cases.forEach { (path, expected) ->
            assertEquals(expected, SimulaHttp.normalizeFirstPartyRoute("https://api.example$path?secret=value#fragment"))
        }
    }

    @Test
    fun `registered static routes discard query and fragment`() {
        assertEquals(
            "/session/create",
            SimulaHttp.normalizeFirstPartyRoute("https://api.example/session/create?ppid=user-123#fragment"),
        )
        assertEquals(
            "/frequency-cap/status",
            SimulaHttp.normalizeFirstPartyRoute("https://api.example/frequency-cap/status?session_id=serve-123"),
        )
    }

    @Test
    fun `telemetry and ppid routes are dropped`() {
        assertNull(SimulaHttp.normalizeFirstPartyRoute("https://api.example/telemetry/events"))
        assertNull(SimulaHttp.normalizeFirstPartyRoute("https://api.example/v1/telemetry/events"))
        assertNull(SimulaHttp.normalizeFirstPartyRoute("https://api.example/session/session-123/ppid/user-123"))
        assertNull(SimulaHttp.normalizeFirstPartyRoute("https://api.example/future/ppid/user-123"))
    }

    @Test
    fun `unknown first party paths never expose their raw segments`() {
        assertEquals(
            "/unknown",
            SimulaHttp.normalizeFirstPartyRoute("https://api.example/new-route/secret-id"),
        )
        assertEquals("/unknown", SimulaHttp.normalizeFirstPartyRoute("not a valid url with secret-id"))
    }

    @Test
    fun `asset telemetry uses one approved low cardinality label`() {
        assertEquals("cdn", SimulaHttp.assetTelemetryLabel("https://storage.googleapis.com/bucket/asset-a.png"))
        assertEquals("cdn", SimulaHttp.assetTelemetryLabel("https://publisher.example/private/asset-b.png"))
    }
}
