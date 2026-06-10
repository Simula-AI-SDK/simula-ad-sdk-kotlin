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
        val r = json.decodeFromString<AdResponseBody>(
            """{"ad_id":"a1","serve_id":"srv_1","iframe_url":"https://z"}""",
        )
        assertEquals("a1", r.adId)
        assertEquals("srv_1", r.serveId)
        assertEquals("https://z", r.iframeUrl)
    }

    // ── Fallback ads (GET /load/fallbacks/{impression_id}) ──────────────────

    @Test
    fun `fallbacks response decodes screens in order`() {
        val payload = """
            {"impression_id":"imp_1","ads":[
              {"ad_id":"a1","html":"<html>1</html>","iframe_url":"https://i/1"},
              {"ad_id":"a2","html":"<html>2</html>","iframe_url":"https://i/2"}
            ]}
        """.trimIndent()
        val r = json.decodeFromString<FallbackAdsApiResponse>(payload)
        assertEquals("imp_1", r.impressionId)
        assertEquals(2, r.ads.size)
        assertEquals("a1", r.ads[0].adId)
        assertEquals("https://i/1", r.ads[0].iframeUrl)
        assertEquals("a2", r.ads[1].adId)
    }

    @Test
    fun `fallbacks response tolerates empty and partial payloads`() {
        val empty = json.decodeFromString<FallbackAdsApiResponse>("""{"impression_id":"imp_1","ads":[]}""")
        assertTrue(empty.ads.isEmpty())

        val bare = json.decodeFromString<FallbackAdsApiResponse>("{}")
        assertEquals("", bare.impressionId)
        assertTrue(bare.ads.isEmpty())

        val partial = json.decodeFromString<FallbackAdsApiResponse>("""{"ads":[{"ad_id":"a1"}]}""")
        assertEquals("a1", partial.ads[0].adId)
        assertNull(partial.ads[0].iframeUrl)
        assertNull(partial.ads[0].html)
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
