package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.model.AdUnitType
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.CloseTreatment
import ad.simula.ad.sdk.model.MAX_CLOSE_DELAY_SECONDS
import ad.simula.ad.sdk.model.OverlayPosition
import ad.simula.ad.sdk.model.OverlayTiming
import ad.simula.ad.sdk.model.StorePromptPlatform
import ad.simula.ad.sdk.model.validatedHexColor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the `POST /ads/load/interstitial` request/response models. Mirrors the
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
        val body = AdLoadRequestBody(adUnitId = "unit_1", sessionId = "sess_9")
        val encoded = json.encodeToString(body)

        // Round-trip is the most robust assertion (key ordering is not guaranteed).
        val decoded = json.decodeFromString<AdLoadRequestBody>(encoded)
        assertEquals("unit_1", decoded.adUnitId)
        assertEquals("sess_9", decoded.sessionId)

        // The wire keys are snake_case, and defaults are emitted (encodeDefaults).
        assertTrue(encoded.contains("\"ad_unit_id\""))
        assertTrue(encoded.contains("\"session_id\""))
        // `rewarded` is no longer part of the request body.
        assertFalse(encoded.contains("\"rewarded\""))
    }

    @Test
    fun `request defaults empty session`() {
        val body = AdLoadRequestBody(adUnitId = "unit_1")
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
              "destination": "web",
              "rendered_format": "rewarded_video",
              "rendered_html": "<b>hi</b>",
              "tracking_url": "https://track/click"
            }
        """.trimIndent()

        val r = json.decodeFromString<AdLoadApiResponse>(payload)

        assertEquals("abc-123", r.adId)
        assertTrue(r.adInserted)
        assertEquals("unit_1", r.adUnitId)
        assertEquals("web", r.destination)
        assertEquals("rewarded_video", r.renderedFormat)
        assertEquals("<b>hi</b>", r.renderedHtml)
        assertEquals("https://track/click", r.trackingUrl)
    }

    // ── Response: tolerance / defaults ──────────────────────────────────────────

    @Test
    fun `empty object decodes to safe defaults`() {
        val r = json.decodeFromString<AdLoadApiResponse>("{}")
        assertEquals("", r.adId)
        assertFalse(r.adInserted)
        assertEquals("", r.adUnitId)
        assertEquals("appstore", r.destination)
        assertNull(r.renderedFormat)
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
        // The no-fill case: server returns ad_inserted=false (and typically no html).
        val r = json.decodeFromString<AdLoadApiResponse>("""{"ad_inserted":false}""")
        assertFalse(r.adInserted)
        assertNull(r.renderedHtml)
    }

    @Test
    fun `missing optional fields use defaults`() {
        val payload = """{"ad_id":"x","ad_inserted":true,"ad_unit_id":"u"}"""
        val r = json.decodeFromString<AdLoadApiResponse>(payload)
        assertEquals("appstore", r.destination)
        assertNull(r.renderedFormat)
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
              "rendered_html": "<html><body>hi</body></html>"
            }
        """.trimIndent()
        val r = json.decodeFromString<AdLoadApiResponse>(payload)
        assertEquals("<html><body>hi</body></html>", r.renderedHtml)
    }

    @Test
    fun `rendered_html absent is null`() {
        val r = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_id":"x","ad_inserted":true}""",
        )
        assertNull(r.renderedHtml)
    }

    // ── Response: ad_behavior (A/B render config, v2 schema) ─────────────────────

    @Test
    fun `ad_behavior absent maps to null domain`() {
        val r = json.decodeFromString<AdLoadApiResponse>("""{"ad_id":"x","ad_inserted":true}""")
        // Absent ad_behavior → null so the renderer falls back to today's literal behavior.
        assertNull(r.adBehavior)
        assertNull(r.adBehavior.toDomain())
        assertNull(r.creative)
        assertNull(r.experiment)
    }

    @Test
    fun `ad_behavior full config maps to domain`() {
        val payload = """
            {"ad_id":"x","ad_inserted":true,
             "creative":{"type":"playable","bundle_url":"https://b","ad_unit_type":"rewarded"},
             "experiment":{"experiment_id":"playable_close_q3","variant_id":"v1","layer":"close_chrome"},
             "ad_behavior":{"close":{"delay_seconds":3,"treatment":"countdown_circle",
               "position":"top_right","progress_bar_color":"#00FF00"},
               "store_prompt":{"enabled":true,"trigger":"midpoint","position":"top_left","platform":"android"},
               "skoverlay":{"enabled":true,"timing":"on_click","delay_seconds":0,"position":"bottom","dismissible":true}}}
        """.trimIndent()
        val r = json.decodeFromString<AdLoadApiResponse>(payload)
        val b = r.adBehavior.toDomain()!!
        assertEquals(3, b.close.delaySeconds)
        assertEquals(CloseTreatment.COUNTDOWN_CIRCLE, b.close.treatment)
        assertEquals(ClosePosition.TOP_RIGHT, b.close.position)
        assertEquals("#00FF00", b.close.progressBarColor)

        val prompt = b.storePrompt!!
        assertTrue(prompt.enabled)
        assertEquals(ClosePosition.TOP_LEFT, prompt.position)
        assertEquals(StorePromptPlatform.ANDROID, prompt.platform)

        val overlay = b.skoverlay!!
        assertTrue(overlay.enabled)
        assertEquals(OverlayTiming.ON_CLICK, overlay.timing)
        assertEquals(OverlayPosition.BOTTOM, overlay.position)
        assertTrue(overlay.dismissible)

        val creative = r.creative.toDomain()!!
        assertEquals(AdUnitType.REWARDED, creative.adUnitType)
        assertEquals("https://b", creative.bundleUrl)
        val experiment = r.experiment.toDomain()!!
        assertEquals("playable_close_q3", experiment.experimentId)
        assertEquals("close_chrome", experiment.layer)
    }

    @Test
    fun `ad_behavior empty object yields defaults`() {
        val r = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_id":"x","ad_inserted":true,"ad_behavior":{}}""",
        )
        assertNotNull(r.adBehavior)
        val b = r.adBehavior.toDomain()!!
        assertEquals(0, b.close.delaySeconds)
        assertEquals(CloseTreatment.HIDDEN, b.close.treatment)
        assertEquals(ClosePosition.TOP_RIGHT, b.close.position)
        assertEquals("#FFFFFF", b.close.progressBarColor)
        assertNull(b.storePrompt)
        assertNull(b.skoverlay)
    }

    @Test
    fun `ad_behavior partial close fills defaults`() {
        val b = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_behavior":{"close":{"delay_seconds":3}}}""",
        ).adBehavior.toDomain()!!
        assertEquals(3, b.close.delaySeconds)
        assertEquals(CloseTreatment.HIDDEN, b.close.treatment)
        assertEquals(ClosePosition.TOP_RIGHT, b.close.position)
        assertEquals("#FFFFFF", b.close.progressBarColor)
    }

    @Test
    fun `ad_behavior normalizes hyphenated values`() {
        val payload = """
            {"ad_behavior":{"close":{"treatment":"reward-or-close-label","position":"top-left"},
              "skoverlay":{"enabled":true,"timing":"during-play","position":"bottom-raised"}}}
        """.trimIndent()
        val b = json.decodeFromString<AdLoadApiResponse>(payload).adBehavior.toDomain()!!
        assertEquals(CloseTreatment.REWARD_OR_CLOSE_LABEL, b.close.treatment)
        assertEquals(ClosePosition.TOP_LEFT, b.close.position)
        assertEquals(OverlayTiming.DURING_PLAY, b.skoverlay!!.timing)
        assertEquals(OverlayPosition.BOTTOM_RAISED, b.skoverlay!!.position)
    }

    @Test
    fun `close position excludes bottom_right`() {
        // v2 excludes bottom_right (and legacy bottom_corner) → snaps to the safe top_right default.
        for (raw in listOf("bottom_right", "bottom_corner")) {
            val b = json.decodeFromString<AdLoadApiResponse>(
                """{"ad_behavior":{"close":{"treatment":"hidden","position":"$raw"}}}""",
            ).adBehavior.toDomain()!!
            assertEquals(ClosePosition.TOP_RIGHT, b.close.position)
        }
    }

    @Test
    fun `close position honors bottom_left for all treatments`() {
        // bottom_left is honored for every treatment (no snap). progress_bar still renders its bar at
        // the top edge, but its resolved close position follows the config.
        for (treatment in listOf("hidden", "reward_or_close_label", "countdown_circle", "progress_bar")) {
            val b = json.decodeFromString<AdLoadApiResponse>(
                """{"ad_behavior":{"close":{"treatment":"$treatment","position":"bottom_left"}}}""",
            ).adBehavior.toDomain()!!
            assertEquals(ClosePosition.BOTTOM_LEFT, b.close.position)
        }
    }

    @Test
    fun `close treatment unknown falls back to hidden`() {
        val b = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_behavior":{"close":{"treatment":"sparkles","position":"galaxy"}}}""",
        ).adBehavior.toDomain()!!
        assertEquals(CloseTreatment.HIDDEN, b.close.treatment)
        assertEquals(ClosePosition.TOP_RIGHT, b.close.position)
    }

    @Test
    fun `progress_bar_color validates and falls back`() {
        // Valid 6-digit hex (with/without #) is normalized to upper-case with a #.
        assertEquals("#3B82F6", validatedHexColor("#3b82f6"))
        assertEquals("#00FF00", validatedHexColor("00FF00"))
        // Malformed → white fallback.
        assertEquals("#FFFFFF", validatedHexColor("#FFF"))
        assertEquals("#FFFFFF", validatedHexColor("#GGGGGG"))
        assertEquals("#FFFFFF", validatedHexColor(null))

        // And through the decode path.
        val good = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_behavior":{"close":{"progress_bar_color":"#abcdef"}}}""",
        ).adBehavior.toDomain()!!
        assertEquals("#ABCDEF", good.close.progressBarColor)
        val bad = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_behavior":{"close":{"progress_bar_color":"not-a-color"}}}""",
        ).adBehavior.toDomain()!!
        assertEquals("#FFFFFF", bad.close.progressBarColor)
    }

    @Test
    fun `store_prompt position is verbatim and platform parsed`() {
        val b = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_behavior":{"store_prompt":{"enabled":true,"position":"bottom_left","platform":"ios"}}}""",
        ).adBehavior.toDomain()!!
        val prompt = b.storePrompt!!
        assertTrue(prompt.enabled)
        assertEquals(ClosePosition.BOTTOM_LEFT, prompt.position) // rendered verbatim — never recomputed
        assertEquals(StorePromptPlatform.IOS, prompt.platform)
        assertEquals("midpoint", prompt.trigger)
    }

    @Test
    fun `skoverlay defaults and explicit values`() {
        // Empty skoverlay object → defaults (disabled, on_click, bottom, dismissible).
        val d = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_behavior":{"skoverlay":{}}}""",
        ).adBehavior.toDomain()!!.skoverlay!!
        assertFalse(d.enabled)
        assertEquals(OverlayTiming.ON_CLICK, d.timing)
        assertEquals(OverlayPosition.BOTTOM, d.position)
        assertTrue(d.dismissible)

        // Explicit values, including a clamped negative delay.
        val o = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_behavior":{"skoverlay":{"enabled":true,"timing":"delayed","delay_seconds":-3,
                "position":"bottom_raised","dismissible":false}}}""",
        ).adBehavior.toDomain()!!.skoverlay!!
        assertTrue(o.enabled)
        assertEquals(OverlayTiming.DELAYED, o.timing)
        assertEquals(0, o.delaySeconds) // negative clamps to 0
        assertEquals(OverlayPosition.BOTTOM_RAISED, o.position)
        assertFalse(o.dismissible)
    }

    @Test
    fun `ad_unit_type falls back to legacy flags`() {
        // No creative node: adUnitType derives from the legacy `rendered_format` (the imperative
        // HTML model dropped the flat `rewarded` flag, so a stray `rewarded` key is ignored).
        val rewardedFormat = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_id":"x","ad_inserted":true,"rendered_format":"rewarded_video"}""",
        )
        val r1 = SimulaApiClient.AdLoadResult(
            adId = rewardedFormat.adId, adInserted = rewardedFormat.adInserted, adUnitId = rewardedFormat.adUnitId,
            destination = rewardedFormat.destination, renderedFormat = rewardedFormat.renderedFormat,
            trackingUrl = rewardedFormat.trackingUrl, renderedHtml = rewardedFormat.renderedHtml,
            adBehavior = null,
        )
        assertEquals(AdUnitType.REWARDED, r1.adUnitType)

        val plain = SimulaApiClient.AdLoadResult(
            adId = "x", adInserted = true, adUnitId = "u", destination = "appstore",
            renderedFormat = null, trackingUrl = null, renderedHtml = null, adBehavior = null,
        )
        assertEquals(AdUnitType.INTERSTITIAL, plain.adUnitType)
    }

    @Test
    fun `ad_behavior is resilient to unknown enum values`() {
        val payload = """
            {"ad_behavior":{"close":{"delay_seconds":12,"treatment":"spinner","position":"middle",
              "progress_bar_color":"warp"}}}
        """.trimIndent()
        val b = json.decodeFromString<AdLoadApiResponse>(payload).adBehavior.toDomain()!!
        assertEquals(12, b.close.delaySeconds)
        assertEquals(CloseTreatment.HIDDEN, b.close.treatment)
        assertEquals(ClosePosition.TOP_RIGHT, b.close.position)
        assertEquals("#FFFFFF", b.close.progressBarColor)
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
}
