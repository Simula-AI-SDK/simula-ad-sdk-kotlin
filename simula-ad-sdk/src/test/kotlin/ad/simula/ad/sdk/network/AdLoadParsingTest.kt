package ad.simula.ad.sdk.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the `POST /ads/load` request/response models. Mirrors the
 * production [SimulaApiClient] JSON config (lenient, ignore-unknown, encode-defaults)
 * so these exercise the same decode/encode behavior the client relies on.
 *
 * No Android framework here — pure kotlinx.serialization on the JVM.
 */
class AdLoadParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // ── Request ───────────────────────────────────────────────────────────────

    @Test
    fun `request encodes snake_case keys with defaults`() {
        val body = AdLoadRequestBody(adUnitId = "unit_1", rewarded = true, sessionId = "sess_9")
        val encoded = json.encodeToString(body)

        // Round-trip is the most robust assertion (key ordering is not guaranteed).
        val decoded = json.decodeFromString<AdLoadRequestBody>(encoded)
        assertEquals("unit_1", decoded.adUnitId)
        assertTrue(decoded.rewarded)
        assertEquals("sess_9", decoded.sessionId)

        // The wire keys are snake_case, and defaults are emitted (encodeDefaults).
        assertTrue(encoded.contains("\"ad_unit_id\""))
        assertTrue(encoded.contains("\"session_id\""))
        assertTrue(encoded.contains("\"rewarded\""))
    }

    @Test
    fun `request defaults rewarded false and empty session`() {
        val body = AdLoadRequestBody(adUnitId = "unit_1")
        assertFalse(body.rewarded)
        assertEquals("", body.sessionId)
        assertNull(body.charId)
        assertNull(body.charName)
        assertNull(body.charImage)
        assertNull(body.charDesc)
    }

    @Test
    fun `request encodes char fields when set`() {
        val body = AdLoadRequestBody(
            adUnitId = "u",
            charId = "char_7",
            charName = "Mentor",
            charImage = "https://cdn.example.com/avatar.png",
            charDesc = "a wise mentor",
        )
        val encoded = json.encodeToString(body)
        assertTrue(encoded.contains("\"char_id\""))
        assertTrue(encoded.contains("\"char_name\""))
        assertTrue(encoded.contains("\"char_image\""))
        assertTrue(encoded.contains("\"char_desc\""))

        val decoded = json.decodeFromString<AdLoadRequestBody>(encoded)
        assertEquals("char_7", decoded.charId)
        assertEquals("Mentor", decoded.charName)
        assertEquals("https://cdn.example.com/avatar.png", decoded.charImage)
        assertEquals("a wise mentor", decoded.charDesc)
    }

    // ── Response: happy path ────────────────────────────────────────────────────

    @Test
    fun `full response decodes all fields`() {
        val payload = """
            {
              "ad_id": "abc-123",
              "ad_inserted": true,
              "ad_unit_id": "unit_1",
              "rewarded": true,
              "destination": "web",
              "rendered_format": "rewarded_video",
              "rendered_assets": ["https://cdn/a.jpg", "https://cdn/b.jpg"],
              "tracking_url": "https://track/click"
            }
        """.trimIndent()

        val r = json.decodeFromString<AdLoadApiResponse>(payload)

        assertEquals("abc-123", r.adId)
        assertTrue(r.adInserted)
        assertEquals("unit_1", r.adUnitId)
        assertTrue(r.rewarded)
        assertEquals("web", r.destination)
        assertEquals("rewarded_video", r.renderedFormat)
        assertEquals(listOf("https://cdn/a.jpg", "https://cdn/b.jpg"), r.renderedAssets)
        assertEquals("https://track/click", r.trackingUrl)
    }

    @Test
    fun `rendered_assets preserves server order`() {
        val payload = """{"rendered_assets":["c","a","b"]}"""
        val r = json.decodeFromString<AdLoadApiResponse>(payload)
        assertEquals(listOf("c", "a", "b"), r.renderedAssets)
    }

    // ── Response: tolerance / defaults ──────────────────────────────────────────

    @Test
    fun `empty object decodes to safe defaults`() {
        val r = json.decodeFromString<AdLoadApiResponse>("{}")
        assertEquals("", r.adId)
        assertFalse(r.adInserted)
        assertEquals("", r.adUnitId)
        assertFalse(r.rewarded)
        assertEquals("appstore", r.destination)
        assertNull(r.renderedFormat)
        assertTrue(r.renderedAssets.isEmpty())
        assertNull(r.trackingUrl)
        assertNull(r.renderedHtml)
    }

    @Test
    fun `destination defaults to appstore when absent`() {
        val r = json.decodeFromString<AdLoadApiResponse>("""{"ad_id":"x","ad_inserted":true}""")
        assertEquals("appstore", r.destination)
    }

    @Test
    fun `unknown destination passes through unchanged`() {
        // Decode keeps the raw string; the SDK maps unknown values to the appstore
        // route at CTA time — decode itself must not throw or coerce.
        val r = json.decodeFromString<AdLoadApiResponse>("""{"destination":"carousel_xyz"}""")
        assertEquals("carousel_xyz", r.destination)
    }

    @Test
    fun `unknown keys are ignored`() {
        val payload = """{"ad_id":"x","ad_inserted":true,"future_field":42,"nested":{"a":1}}"""
        val r = json.decodeFromString<AdLoadApiResponse>(payload)
        assertEquals("x", r.adId)
        assertTrue(r.adInserted)
    }

    @Test
    fun `ad_inserted false decodes as no-fill signal`() {
        // The no-fill case: server returns ad_inserted=false (and typically no assets).
        val r = json.decodeFromString<AdLoadApiResponse>("""{"ad_inserted":false,"rendered_assets":[]}""")
        assertFalse(r.adInserted)
        assertTrue(r.renderedAssets.isEmpty())
    }

    @Test
    fun `missing optional fields use defaults`() {
        val payload = """{"ad_id":"x","ad_inserted":true,"ad_unit_id":"u","rewarded":false}"""
        val r = json.decodeFromString<AdLoadApiResponse>(payload)
        assertEquals("appstore", r.destination)
        assertNull(r.renderedFormat)
        assertTrue(r.renderedAssets.isEmpty())
        assertNull(r.trackingUrl)
        assertNull(r.renderedHtml)
    }

    // ── Response: rendered_html (HTML creative precedence) ──────────────────────

    @Test
    fun `rendered_html decodes when present`() {
        val payload = """
            {
              "ad_id": "abc-123",
              "ad_inserted": true,
              "rendered_assets": ["https://cdn/a.jpg"],
              "rendered_html": "<html><body>hi</body></html>"
            }
        """.trimIndent()
        val r = json.decodeFromString<AdLoadApiResponse>(payload)
        assertEquals("<html><body>hi</body></html>", r.renderedHtml)
        // Assets still decode; precedence is decided by the SDK at render time.
        assertEquals(listOf("https://cdn/a.jpg"), r.renderedAssets)
    }

    @Test
    fun `rendered_html absent is null`() {
        val r = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_id":"x","ad_inserted":true,"rendered_assets":["https://cdn/a.jpg"]}""",
        )
        assertNull(r.renderedHtml)
    }
}
