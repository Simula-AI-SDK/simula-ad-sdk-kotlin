package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.model.SimulaAdContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the `POST /load/native` request/response models (backend `CaiNativeRequest` /
 * `CaiNativeResponse`). Uses the same lenient/ignore-unknown/encode-defaults [Json] the production
 * [SimulaApiClient] does. Pure kotlinx.serialization on the JVM — no Android framework.
 */
class NativeAdParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // ── Request ───────────────────────────────────────────────────────────────

    @Test
    fun `request encodes required keys and snake_case char fields`() {
        val body = NativeAdRequestBody(position = 3, sessionId = "sess_9", adUnitId = "au_1", charId = "c_7")
        val encoded = json.encodeToString(body)

        assertTrue(encoded.contains("\"position\""))
        assertTrue(encoded.contains("\"session_id\""))
        assertTrue(encoded.contains("\"ad_unit_id\""))
        assertTrue(encoded.contains("\"char_id\""))
        // The native surface has no char_image (unlike the interstitial).
        assertFalse(encoded.contains("\"char_image\""))

        val decoded = json.decodeFromString<NativeAdRequestBody>(encoded)
        assertEquals(3, decoded.position)
        assertEquals("sess_9", decoded.sessionId)
        assertEquals("au_1", decoded.adUnitId)
        assertEquals("c_7", decoded.charId)
    }

    @Test
    fun `context maps SimulaAdContext to camelCase wire keys`() {
        val ctx = SimulaAdContext(
            searchTerm = "fantasy rpg",
            tags = listOf("adventure", "magic"),
            category = "roleplay",
            userEmail = "a@b.com",
            customContext = mapOf("recent" to "Frieren"),
            nsfw = false,
        )
        val encoded = json.encodeToString(ctx.toBody())

        // NativeContext is the one camelCase object in the otherwise snake_case API.
        assertTrue(encoded.contains("\"searchTerm\""))
        assertTrue(encoded.contains("\"userEmail\""))
        assertTrue(encoded.contains("\"customContext\""))
        assertTrue(encoded.contains("\"nsfw\""))
        // No snake_case leakage.
        assertFalse(encoded.contains("\"search_term\""))
        assertFalse(encoded.contains("\"user_email\""))

        val decoded = json.decodeFromString<NativeContextBody>(encoded)
        assertEquals("fantasy rpg", decoded.searchTerm)
        assertEquals(listOf("adventure", "magic"), decoded.tags)
        assertEquals("roleplay", decoded.category)
        assertEquals("a@b.com", decoded.userEmail)
        assertEquals("Frieren", decoded.customContext?.get("recent"))
        assertFalse(decoded.nsfw)
    }

    // ── Response: fill ──────────────────────────────────────────────────────────

    @Test
    fun `fill response decodes the camelCase adResponse wrapper`() {
        val payload = """
            {
              "impression_id": "serve-123",
              "ad_inserted": true,
              "ad_format": "character_ad",
              "adResponse": {
                "iframe_url": "https://api/iframe/abc",
                "rendered_html": "<iframe srcdoc=...></iframe>"
              }
            }
        """.trimIndent()
        val r = json.decodeFromString<NativeAdApiResponse>(payload)

        assertEquals("serve-123", r.impressionId)
        assertTrue(r.adInserted)
        assertEquals("character_ad", r.adFormat)
        assertEquals("https://api/iframe/abc", r.adResponse.iframeUrl)
        assertEquals("<iframe srcdoc=...></iframe>", r.adResponse.renderedHtml)
    }

    // ── Response: no-fill / tolerance ───────────────────────────────────────────

    @Test
    fun `no-fill response decodes with null impression and empty creative`() {
        val payload = """{"impression_id":null,"ad_inserted":false,"ad_format":"","adResponse":{}}"""
        val r = json.decodeFromString<NativeAdApiResponse>(payload)

        assertNull(r.impressionId)
        assertFalse(r.adInserted)
        assertEquals("", r.adFormat)
        assertNull(r.adResponse.iframeUrl)
        assertNull(r.adResponse.renderedHtml)
    }

    @Test
    fun `empty object decodes to safe defaults`() {
        val r = json.decodeFromString<NativeAdApiResponse>("{}")
        assertNull(r.impressionId)
        assertFalse(r.adInserted)
        assertEquals("", r.adFormat)
        assertNull(r.adResponse.iframeUrl)
        assertNull(r.adResponse.renderedHtml)
    }

    @Test
    fun `unknown keys are ignored`() {
        val payload = """{"ad_inserted":true,"ad_format":"character_ad","adResponse":{"iframe_url":"u"},"future":1}"""
        val r = json.decodeFromString<NativeAdApiResponse>(payload)
        assertTrue(r.adInserted)
        assertEquals("u", r.adResponse.iframeUrl)
    }
}
