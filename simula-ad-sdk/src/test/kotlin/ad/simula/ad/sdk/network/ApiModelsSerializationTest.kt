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
 * Serialization-contract tests for the other request/response DTOs the
 * [SimulaApiClient] relies on (session, minigame init, fallback ad, menu click).
 * Mirrors the production JSON config; pure kotlinx.serialization on the JVM, no
 * Android framework. See [AdLoadParsingTest] for the `/ads/load/interstitial` contract.
 */
class ApiModelsSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // ── Session ─────────────────────────────────────────────────────────────

    @Test
    fun `session response decodes sessionId`() {
        val r = json.decodeFromString<SessionResponse>("""{"sessionId":"abc-123"}""")
        assertEquals("abc-123", r.sessionId)
    }

    @Test
    fun `session response tolerates missing and unknown fields`() {
        val r = json.decodeFromString<SessionResponse>("""{"other":1}""")
        assertNull(r.sessionId)
    }

    // ── Minigame init request ────────────────────────────────────────────────

    @Test
    fun `init minigame request encodes snake_case keys with defaults`() {
        val body = InitMinigameRequestBody(gameType = "trivia", sessionId = "s_1", w = 320, h = 480)
        val encoded = json.encodeToString(body)

        val decoded = json.decodeFromString<InitMinigameRequestBody>(encoded)
        assertEquals("trivia", decoded.gameType)
        assertEquals("s_1", decoded.sessionId)
        assertEquals(320, decoded.w)
        assertEquals(480, decoded.h)
        assertTrue(decoded.delegateChar)   // default true
        assertFalse(decoded.currencyMode)  // default false

        assertTrue(encoded.contains("\"game_type\""))
        assertTrue(encoded.contains("\"session_id\""))
        assertTrue(encoded.contains("\"delegate_char\""))
    }

    // ── Minigame init response ───────────────────────────────────────────────

    @Test
    fun `minigame response decodes nested adResponse`() {
        val payload =
            """{"adType":"minigame","adInserted":true,"adResponse":{"ad_id":"x","iframe_url":"https://y"}}"""
        val r = json.decodeFromString<MinigameApiResponse>(payload)
        assertEquals("minigame", r.adType)
        assertTrue(r.adInserted)
        assertEquals("x", r.adResponse?.adId)
        assertEquals("https://y", r.adResponse?.iframeUrl)
    }

    @Test
    fun `minigame response defaults when fields absent`() {
        val r = json.decodeFromString<MinigameApiResponse>("{}")
        assertNull(r.adType)
        assertFalse(r.adInserted)
        assertNull(r.adResponse)
    }

    @Test
    fun `ad response body maps snake_case ids`() {
        val r = json.decodeFromString<AdResponseBody>("""{"ad_id":"a1","iframe_url":"https://z"}""")
        assertEquals("a1", r.adId)
        assertEquals("https://z", r.iframeUrl)
    }

    // ── Menu game click ──────────────────────────────────────────────────────

    @Test
    fun `menu game click encodes snake_case keys`() {
        val encoded = json.encodeToString(MenuGameClickBody(menuId = "m1", gameName = "Trivia"))
        val decoded = json.decodeFromString<MenuGameClickBody>(encoded)
        assertEquals("m1", decoded.menuId)
        assertEquals("Trivia", decoded.gameName)
        assertTrue(encoded.contains("\"menu_id\""))
        assertTrue(encoded.contains("\"game_name\""))
    }
}
