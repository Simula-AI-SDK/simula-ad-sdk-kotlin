package ad.simula.ad.sdk.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the rewarded minigame request/response models
 * (`POST /load/rewarded` and `POST /minigames/verify-reward`). Mirrors the
 * production [SimulaApiClient] JSON config so these exercise the same decode/encode
 * behavior the client relies on. Pure kotlinx.serialization on the JVM.
 */
class RewardedParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // ── Init request ────────────────────────────────────────────────────────────

    @Test
    fun `init request encodes snake_case keys`() {
        val body = RewardedInitRequestBody(adUnitId = "unit_1", sessionId = "sess_9")
        val encoded = json.encodeToString(body)

        assertTrue(encoded.contains("\"ad_unit_id\""))
        assertTrue(encoded.contains("\"session_id\""))

        val decoded = json.decodeFromString<RewardedInitRequestBody>(encoded)
        assertEquals("unit_1", decoded.adUnitId)
        assertEquals("sess_9", decoded.sessionId)
    }

    @Test
    fun `init request round-trips with defaults`() {
        val body = RewardedInitRequestBody(adUnitId = "unit_1")
        val decoded = json.decodeFromString<RewardedInitRequestBody>(json.encodeToString(body))
        assertEquals("", decoded.sessionId)
    }

    // ── Init response ───────────────────────────────────────────────────────────

    @Test
    fun `init response decodes all fields`() {
        val payload = """
            {
              "impression_id": "imp_1",
              "iframe_url": "https://cdn/play",
              "ad_behavior": { "close": { "delay_seconds": 30 } }
            }
        """.trimIndent()

        val r = json.decodeFromString<RewardedInitApiResponse>(payload)
        assertEquals("imp_1", r.impressionId)
        assertEquals("https://cdn/play", r.iframeUrl)
        // The play-to-earn gate now rides on `ad_behavior.close.delay_seconds` (no top-level field).
        assertEquals(30, r.adBehavior?.close?.delaySeconds)
    }

    @Test
    fun `init response empty object decodes to safe defaults`() {
        val r = json.decodeFromString<RewardedInitApiResponse>("{}")
        assertEquals("", r.impressionId)
        assertEquals("", r.iframeUrl)
        // Absent `ad_behavior` → null → no gate (instantly earned) and no store prompt.
        assertNull(r.adBehavior)
    }

    @Test
    fun `init response ignores unknown keys`() {
        // Legacy `serve_id`/`ad_id` keys are unknown now and must be ignored, not remapped.
        val payload = """{"impression_id":"i","iframe_url":"u","serve_id":"s","ad_id":"a","future_field":42}"""
        val r = json.decodeFromString<RewardedInitApiResponse>(payload)
        assertEquals("i", r.impressionId)
        assertEquals("u", r.iframeUrl)
    }

    // ── Verify request / response ────────────────────────────────────────────────

    @Test
    fun `verify request encodes snake_case keys`() {
        val body = VerifyRewardRequestBody(serveId = "srv_1", sessionId = "sess_9", elapsedPlayTime = 31.5)
        val encoded = json.encodeToString(body)

        assertTrue(encoded.contains("\"serve_id\""))
        assertTrue(encoded.contains("\"session_id\""))
        assertTrue(encoded.contains("\"elapsed_play_time\""))

        val decoded = json.decodeFromString<VerifyRewardRequestBody>(encoded)
        assertEquals("srv_1", decoded.serveId)
        assertEquals("sess_9", decoded.sessionId)
        assertEquals(31.5, decoded.elapsedPlayTime, 0.0001)
    }

    @Test
    fun `verify response decodes verified and token`() {
        val r = json.decodeFromString<VerifyRewardApiResponse>("""{"verified":true,"token":"tok_1"}""")
        assertTrue(r.verified)
        assertEquals("tok_1", r.token)
    }

    @Test
    fun `verify response missing token is null`() {
        val r = json.decodeFromString<VerifyRewardApiResponse>("""{"verified":true}""")
        assertTrue(r.verified)
        assertNull(r.token)
    }

    @Test
    fun `verify response empty object is unverified`() {
        val r = json.decodeFromString<VerifyRewardApiResponse>("{}")
        assertEquals(false, r.verified)
        assertNull(r.token)
    }
}
