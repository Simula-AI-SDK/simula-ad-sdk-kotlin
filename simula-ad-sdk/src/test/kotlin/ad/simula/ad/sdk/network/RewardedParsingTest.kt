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
 * (`POST /minigames/init/rewarded` and `POST /minigames/verify-reward`). Mirrors the
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
        val body = RewardedInitRequestBody(adUnitId = "unit_1", sessionId = "sess_9", minPlayThreshold = 15)
        val encoded = json.encodeToString(body)

        assertTrue(encoded.contains("\"ad_unit_id\""))
        assertTrue(encoded.contains("\"session_id\""))
        assertTrue(encoded.contains("\"min_play_threshold\""))

        val decoded = json.decodeFromString<RewardedInitRequestBody>(encoded)
        assertEquals("unit_1", decoded.adUnitId)
        assertEquals("sess_9", decoded.sessionId)
        assertEquals(15, decoded.minPlayThreshold)
    }

    @Test
    fun `init request round-trips with null threshold`() {
        val body = RewardedInitRequestBody(adUnitId = "unit_1")
        val decoded = json.decodeFromString<RewardedInitRequestBody>(json.encodeToString(body))
        assertEquals("", decoded.sessionId)
        assertNull(decoded.minPlayThreshold)
    }

    // ── Init response ───────────────────────────────────────────────────────────

    @Test
    fun `init response decodes all fields`() {
        val payload = """
            {
              "serve_id": "srv_1",
              "iframe_url": "https://cdn/play",
              "ad_id": "ad_9",
              "duration_seconds": 30
            }
        """.trimIndent()

        val r = json.decodeFromString<RewardedInitApiResponse>(payload)
        assertEquals("srv_1", r.serveId)
        assertEquals("https://cdn/play", r.iframeUrl)
        assertEquals("ad_9", r.adId)
        assertEquals(30, r.durationSeconds)
    }

    @Test
    fun `init response empty object decodes to safe defaults`() {
        val r = json.decodeFromString<RewardedInitApiResponse>("{}")
        assertEquals("", r.serveId)
        assertEquals("", r.iframeUrl)
        assertEquals("", r.adId)
        assertEquals(0, r.durationSeconds)
    }

    @Test
    fun `init response ignores unknown keys`() {
        val payload = """{"serve_id":"s","iframe_url":"u","future_field":42,"nested":{"a":1}}"""
        val r = json.decodeFromString<RewardedInitApiResponse>(payload)
        assertEquals("s", r.serveId)
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
