package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.model.CreativeType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
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
        assertEquals(2, decoded.contracts["video"])
    }

    @Test
    fun `init request encodes metadata under the exact top-level wire key`() {
        val root = json.parseToJsonElement(
            json.encodeToString(
                RewardedInitRequestBody(
                    adUnitId = "unit_1",
                    metadata = mapOf("placement" to "reward", "locale" to "pt-BR"),
                ),
            ),
        ).jsonObject

        assertEquals(
            JsonObject(mapOf("placement" to JsonPrimitive("reward"), "locale" to JsonPrimitive("pt-BR"))),
            root["metadata"],
        )
        assertFalse(root.containsKey("extraParameters"))
        assertFalse(root.containsKey("extra_parameters"))
    }

    @Test
    fun `init request represents empty metadata as omitted or null`() {
        val root = json.parseToJsonElement(
            json.encodeToString(RewardedInitRequestBody(adUnitId = "unit_1", metadata = null)),
        ).jsonObject

        assertTrue(root["metadata"] == null || root["metadata"] == JsonNull)
    }

    // ── Init response ───────────────────────────────────────────────────────────

    @Test
    fun `init response decodes all fields`() {
        val payload = """
            {
              "impression_id": "imp_1",
              "rendered_html": "<html>primary</html>",
              "creative": {"type":"video","url":"https://cdn/video.mp4","poster_url":"https://cdn/poster.jpg"},
              "experiment": {
                "experiment_id": "rewarded_video_q3",
                "variant_id": "video_b",
                "layer": "creative_media"
              },
              "prewarm_sk_product": true,
              "ad_behavior": { "close": { "delay_seconds": 30 } }
            }
        """.trimIndent()

        val r = json.decodeFromString<RewardedInitApiResponse>(payload)
        val result = SimulaApiClient.rewardedResultFromResponse(r, adUnitId = "rewarded-unit")
        assertEquals("imp_1", r.impressionId)
        assertEquals("<html>primary</html>", r.renderedHtml)
        assertEquals(CreativeType.VIDEO, r.creative.toDomain()?.type)
        assertEquals("https://cdn/video.mp4", r.creative.toDomain()?.url)
        assertEquals("https://cdn/poster.jpg", r.creative.toDomain()?.posterUrl)
        assertEquals("rewarded_video_q3", r.experiment?.experimentId)
        assertEquals("video_b", r.experiment?.variantId)
        assertEquals("creative_media", r.experiment?.layer)
        assertEquals("rewarded_video_q3", result.experiment?.experimentId)
        assertEquals("video_b", result.experiment?.variantId)
        assertEquals("creative_media", result.experiment?.layer)
        assertEquals("rewarded-unit", result.adUnitId)
        assertTrue(r.prewarmSkProduct)
        // The play-to-earn gate now rides on `ad_behavior.close.delay_seconds` (no top-level field).
        assertEquals(30, r.adBehavior?.close?.delaySeconds)
    }

    @Test
    fun `init response empty object decodes to safe defaults`() {
        val r = json.decodeFromString<RewardedInitApiResponse>("{}")
        assertNull(r.impressionId)
        assertNull(r.renderedHtml)
        // Absent `ad_behavior` → null → no gate (instantly earned) and no store prompt.
        assertNull(r.adBehavior)
        assertFalse(r.prewarmSkProduct)
    }

    @Test
    fun `init response ignores unknown keys`() {
        // Legacy `serve_id`/`ad_id` keys are unknown now and must be ignored, not remapped.
        val payload = """{"impression_id":"i","iframe_url":"ignored","serve_id":"s","ad_id":"a","future_field":42}"""
        val r = json.decodeFromString<RewardedInitApiResponse>(payload)
        assertEquals("i", r.impressionId)
        assertNull(r.renderedHtml)
    }

    @Test
    fun `explicit null no-fill fields decode and normalize safely`() {
        val response = json.decodeFromString<RewardedInitApiResponse>(
            """{"impression_id":null,"rendered_html":null,"creative":null,"experiment":null}""",
        )
        val result = SimulaApiClient.rewardedResultFromResponse(response)

        assertNull(response.impressionId)
        assertNull(response.renderedHtml)
        assertEquals("", result.impressionId)
        assertEquals("", result.renderedHtml)
        assertNull(result.experiment)
    }

    @Test
    fun `malformed experiment is ignored without rejecting rewarded creative`() {
        val response = json.decodeFromString<RewardedInitApiResponse>(
            """{"impression_id":"i","rendered_html":"<html/>","experiment":"invalid"}""",
        )
        val result = SimulaApiClient.rewardedResultFromResponse(response)

        assertNull(response.experiment)
        assertNull(result.experiment)
        assertEquals("<html/>", result.renderedHtml)
    }

    // ── Verify request / response ────────────────────────────────────────────────

    @Test
    fun `verify request encodes snake_case keys`() {
        val body = VerifyRewardRequestBody(
            serveId = "srv_1",
            sessionId = "sess_9",
            elapsedPlayTime = 31.5,
            completionReason = "duration_elapsed",
        )
        val encoded = json.encodeToString(body)

        assertTrue(encoded.contains("\"serve_id\""))
        assertTrue(encoded.contains("\"session_id\""))
        assertTrue(encoded.contains("\"elapsed_play_time\""))
        assertTrue(encoded.contains("\"completion_reason\":\"duration_elapsed\""))
        assertFalse(encoded.contains("ad_unit_id"))

        val decoded = json.decodeFromString<VerifyRewardRequestBody>(encoded)
        assertEquals("srv_1", decoded.serveId)
        assertEquals("sess_9", decoded.sessionId)
        assertEquals(31.5, decoded.elapsedPlayTime, 0.0001)
        assertEquals("duration_elapsed", decoded.completionReason)
    }

    @Test
    fun `legacy verify request without completion reason decodes with null`() {
        val decoded = json.decodeFromString<VerifyRewardRequestBody>(
            """{"serve_id":"srv","session_id":"sess","elapsed_play_time":4.0}""",
        )

        assertNull(decoded.completionReason)
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

    @Test
    fun `successful HTTP response must explicitly verify reward`() {
        val failure = runCatching {
            requireVerifiedReward(json.decodeFromString<VerifyRewardApiResponse>("""{"verified":false}"""))
        }.exceptionOrNull()

        assertTrue(failure is RewardNotVerifiedException)
        assertTrue(requireVerifiedReward(VerifyRewardApiResponse(verified = true, token = "token")).verified)
    }
}
