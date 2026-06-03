package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.model.CloseCountdownUi
import ad.simula.ad.sdk.model.CloseMotion
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.CloseSize
import ad.simula.ad.sdk.model.MAX_CLOSE_DELAY_SECONDS
import ad.simula.ad.sdk.model.StoreOpen
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    }

    // ── Response: ad_behavior (A/B render config) ───────────────────────────────

    @Test
    fun `ad_behavior absent maps to null domain`() {
        val r = json.decodeFromString<AdLoadApiResponse>("""{"ad_id":"x","ad_inserted":true}""")
        // Absent ad_behavior → null so the renderer falls back to today's literal behavior.
        assertNull(r.adBehavior)
        assertNull(r.adBehavior.toDomain())
    }

    @Test
    fun `ad_behavior full config maps to domain enums`() {
        val payload = """
            {"ad_id":"x","ad_inserted":true,
             "ad_behavior":{"close":{"delay_seconds":5,"countdown_ui":"circular_progress",
               "position":"bottom_left","size":"large","motion":"reposition_on_tap"},
               "store_open":"skstoreproduct"}}
        """.trimIndent()
        val b = json.decodeFromString<AdLoadApiResponse>(payload).adBehavior.toDomain()!!
        assertEquals(5, b.close.delaySeconds)
        assertEquals(CloseCountdownUi.CIRCULAR_PROGRESS, b.close.countdownUi)
        assertEquals(ClosePosition.BOTTOM_LEFT, b.close.position)
        assertEquals(CloseSize.LARGE, b.close.size)
        assertEquals(CloseMotion.REPOSITION_ON_TAP, b.close.motion)
        assertEquals(StoreOpen.SKSTOREPRODUCT, b.storeOpen)
    }

    @Test
    fun `ad_behavior empty object yields defaults`() {
        val r = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_id":"x","ad_inserted":true,"ad_behavior":{}}""",
        )
        assertNotNull(r.adBehavior)
        val b = r.adBehavior.toDomain()!!
        assertEquals(0, b.close.delaySeconds)
        assertEquals(CloseCountdownUi.NONE, b.close.countdownUi)
        assertEquals(ClosePosition.TOP_RIGHT, b.close.position)
        assertEquals(CloseSize.STANDARD, b.close.size)
        assertEquals(CloseMotion.STATIC, b.close.motion)
        assertEquals(StoreOpen.EXTERNAL, b.storeOpen)
    }

    @Test
    fun `ad_behavior partial close fills defaults`() {
        val b = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_behavior":{"close":{"delay_seconds":3}}}""",
        ).adBehavior.toDomain()!!
        assertEquals(3, b.close.delaySeconds)
        assertEquals(CloseCountdownUi.NONE, b.close.countdownUi)
        assertEquals(ClosePosition.TOP_RIGHT, b.close.position)
        assertEquals(StoreOpen.EXTERNAL, b.storeOpen)
    }

    @Test
    fun `ad_behavior normalizes hyphenated values`() {
        val payload = """
            {"ad_behavior":{"close":{"countdown_ui":"numeric-always","position":"top-left",
              "motion":"reposition-on-tap"},"store_open":"external-browser"}}
        """.trimIndent()
        val b = json.decodeFromString<AdLoadApiResponse>(payload).adBehavior.toDomain()!!
        assertEquals(CloseCountdownUi.NUMERIC_ALWAYS, b.close.countdownUi)
        assertEquals(ClosePosition.TOP_LEFT, b.close.position)
        assertEquals(CloseMotion.REPOSITION_ON_TAP, b.close.motion)
        assertEquals(StoreOpen.EXTERNAL, b.storeOpen)
    }

    @Test
    fun `ad_behavior honors legacy aliases`() {
        val payload = """
            {"ad_behavior":{"close":{"countdown_ui":"bar","position":"bottom_corner","size":"small"},
              "store_open":"sk_overlay"}}
        """.trimIndent()
        val b = json.decodeFromString<AdLoadApiResponse>(payload).adBehavior.toDomain()!!
        assertEquals(CloseCountdownUi.BAR, b.close.countdownUi)
        assertEquals(ClosePosition.BOTTOM_RIGHT, b.close.position)
        assertEquals(CloseSize.SMALL, b.close.size)
        assertEquals(StoreOpen.SKSTOREPRODUCT, b.storeOpen)
    }

    @Test
    fun `ad_behavior maps inline_install and sk_store_product`() {
        val a = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_behavior":{"store_open":"inline_install"}}""",
        ).adBehavior.toDomain()!!
        assertEquals(StoreOpen.INLINE_INSTALL, a.storeOpen)

        val b = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_behavior":{"store_open":"sk_store_product"}}""",
        ).adBehavior.toDomain()!!
        assertEquals(StoreOpen.SKSTOREPRODUCT, b.storeOpen)
    }

    @Test
    fun `ad_behavior is resilient to unknown enum values`() {
        val payload = """
            {"ad_behavior":{"close":{"delay_seconds":12,"countdown_ui":"spinner","position":"middle",
              "size":"huge","motion":"teleport"},"store_open":"warp"}}
        """.trimIndent()
        val b = json.decodeFromString<AdLoadApiResponse>(payload).adBehavior.toDomain()!!
        assertEquals(12, b.close.delaySeconds)
        assertEquals(CloseCountdownUi.NONE, b.close.countdownUi)
        assertEquals(ClosePosition.TOP_RIGHT, b.close.position)
        assertEquals(CloseSize.STANDARD, b.close.size)
        assertEquals(CloseMotion.STATIC, b.close.motion)
        assertEquals(StoreOpen.EXTERNAL, b.storeOpen)
    }

    @Test
    fun `ad_behavior clamps negative delay to zero`() {
        val b = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_behavior":{"close":{"delay_seconds":-5}}}""",
        ).adBehavior.toDomain()!!
        assertEquals(0, b.close.delaySeconds)
    }

    @Test
    fun `ad_behavior clamps oversized delay to max`() {
        // A bad/oversized delay must clamp so it can't trap the user behind a blocked close + Back.
        val b = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_behavior":{"close":{"delay_seconds":600}}}""",
        ).adBehavior.toDomain()!!
        assertEquals(MAX_CLOSE_DELAY_SECONDS, b.close.delaySeconds)
    }

    @Test
    fun `close size maps to PRD point values`() {
        assertEquals(16, CloseSize.SMALL.glyphSp)
        assertEquals(24, CloseSize.STANDARD.glyphSp)
        assertEquals(32, CloseSize.LARGE.glyphSp)
        assertEquals(44, CloseSize.STANDARD.boxDp)
    }
}
