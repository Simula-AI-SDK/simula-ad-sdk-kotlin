package ad.simula.ad.sdk.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

    @Test
    fun `session create advertises native click beacon capability`() {
        val root = json.parseToJsonElement(
            sessionCreateBody(
                privacy = buildJsonObject { put("hasPrivacyConsent", true) },
                capabilities = ApiDeviceCapabilities(videoV1 = true),
            ),
        ).jsonObject

        assertTrue(root.getValue("capabilities").jsonObject
            .getValue("native_click_beacon_v1").jsonPrimitive.boolean)
        assertTrue(root.getValue("capabilities").jsonObject
            .getValue("video_v1").jsonPrimitive.boolean)
        assertTrue(root.containsKey("privacy"))
    }

    @Test
    fun `load request preserves native click beacon capability`() {
        val root = json.parseToJsonElement(
            json.encodeToString(
                AdLoadRequestBody(
                    adUnitId = "unit",
                    capabilities = ApiDeviceCapabilities(videoV1 = true),
                ),
            ),
        ).jsonObject

        assertTrue(root.getValue("capabilities").jsonObject
            .getValue("native_click_beacon_v1").jsonPrimitive.boolean)
        assertTrue(root.getValue("capabilities").jsonObject
            .getValue("video_v1").jsonPrimitive.boolean)
    }

    @Test
    fun `neutral capabilities do not advertise video support`() {
        assertFalse(ApiDeviceCapabilities().videoV1)
    }

    // ── Error body (4xx {code, message}) ─────────────────────────────────────

    @Test
    fun `api error response decodes the stable code`() {
        val r = json.decodeFromString<ApiErrorResponse>(
            """{"code":"ad_unit_not_found","message":"Ad unit 'x' is not registered for this publisher."}""",
        )
        assertEquals("ad_unit_not_found", r.code)
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
            {"impression_id":"imp_1","native_click_beacon_v1_enabled":true,"ads":[
              {"ad_id":"a1","rendered_html":"<html>1</html>","iframe_url":"https://ignored/1"},
              {"ad_id":"a2","native_click_beacon_v1_enabled":false,"html":"<html>2</html>"}
            ]}
        """.trimIndent()
        val r = json.decodeFromString<FallbackAdsApiResponse>(payload)
        assertEquals("imp_1", r.impressionId)
        assertEquals(true, r.nativeClickBeaconV1Enabled)
        assertEquals(2, r.ads.size)
        assertEquals("a1", r.ads[0].adId)
        assertNull(r.ads[0].nativeClickBeaconV1Enabled)
        assertEquals("<html>1</html>", r.ads[0].renderedHtml)
        assertEquals("a2", r.ads[1].adId)
        assertEquals(false, r.ads[1].nativeClickBeaconV1Enabled)
    }

    @Test
    fun `fallbacks response tolerates empty and partial payloads`() {
        val empty = json.decodeFromString<FallbackAdsApiResponse>("""{"impression_id":"imp_1","ads":[]}""")
        assertTrue(empty.ads.isEmpty())

        val bare = json.decodeFromString<FallbackAdsApiResponse>("{}")
        assertEquals("", bare.impressionId)
        assertNull(bare.nativeClickBeaconV1Enabled)
        assertTrue(bare.ads.isEmpty())

        val partial = json.decodeFromString<FallbackAdsApiResponse>("""{"ads":[{"ad_id":"a1"}]}""")
        assertEquals("a1", partial.ads[0].adId)
        assertNull(partial.ads[0].renderedHtml)
        assertNull(partial.ads[0].html)
        assertNull(partial.ads[0].nativeClickBeaconV1Enabled)
    }

    @Test
    fun `malformed fallback beacon ownership fails closed without dropping screens`() {
        val payload = """
            {"native_click_beacon_v1_enabled":"true","ads":[
              {"ad_id":"a1","html":"<html>1</html>"},
              {"ad_id":"a2","native_click_beacon_v1_enabled":1,"html":"<html>2</html>"}
            ]}
        """.trimIndent()

        val response = json.decodeFromString<FallbackAdsApiResponse>(payload)

        assertNull(response.nativeClickBeaconV1Enabled)
        assertEquals(2, response.ads.size)
        assertNull(response.ads[1].nativeClickBeaconV1Enabled)
        assertFalse(
            requireNotNull(SimulaApiClient.fallbackAdFromBody(response.ads[0], false))
                .nativeClickBeaconV1Enabled,
        )
        assertFalse(
            requireNotNull(SimulaApiClient.fallbackAdFromBody(response.ads[1], false))
                .nativeClickBeaconV1Enabled,
        )
    }

    @Test
    fun `fallback beacon ownership defaults false and per-ad value overrides response`() {
        val inherited = SimulaApiClient.fallbackAdFromBody(
            FallbackAdBody(adId = "a1", html = "<html/>"),
            responseNativeClickBeaconV1Enabled = true,
        )
        val overridden = SimulaApiClient.fallbackAdFromBody(
            FallbackAdBody(
                adId = "a2",
                html = "<html/>",
                nativeClickBeaconV1Enabled = false,
            ),
            responseNativeClickBeaconV1Enabled = true,
        )
        val defaulted = SimulaApiClient.fallbackAdFromBody(
            FallbackAdBody(adId = "a3", renderedHtml = "<html/>") ,
            responseNativeClickBeaconV1Enabled = false,
        )

        assertEquals(true, inherited?.nativeClickBeaconV1Enabled)
        assertEquals(false, overridden?.nativeClickBeaconV1Enabled)
        assertEquals(false, defaulted?.nativeClickBeaconV1Enabled)
    }

    @Test
    fun `fallback prefers rendered HTML tolerates legacy html and never accepts iframe only`() {
        val preferred = SimulaApiClient.fallbackAdFromBody(
            FallbackAdBody(renderedHtml = "<new/>", html = "<legacy/>"),
            false,
        )
        val legacy = SimulaApiClient.fallbackAdFromBody(FallbackAdBody(html = "<legacy/>"), false)
        val iframeOnly = json.decodeFromString<FallbackAdBody>(
            """{"iframe_url":"https://ignored.example/creative"}""",
        )

        assertEquals("<new/>", preferred?.renderedHtml)
        assertEquals("<legacy/>", legacy?.renderedHtml)
        assertNull(SimulaApiClient.fallbackAdFromBody(iframeOnly, false))
    }

    @Test
    fun `fallback video and tolerant type map with constrained close behavior`() {
        val video = SimulaApiClient.fallbackAdFromBody(
            FallbackAdBody(
                type = "video",
                url = "https://cdn.example/video.mp4",
                posterUrl = "https://cdn.example/poster.jpg",
                destination = "web",
                trackingUrl = "https://tracker.example/click",
                androidStoreUrl = "https://play.google.com/store/apps/details?id=android.app",
                iosStoreUrl = "https://apps.apple.com/app/id123",
                adBehavior = ApiAdBehavior(
                    close = ApiCloseBehavior(delaySeconds = 99, treatment = "progress_bar", position = "top_left"),
                ),
            ),
            false,
        )
        val unknown = SimulaApiClient.fallbackAdFromBody(
            FallbackAdBody(type = "future", renderedHtml = "<html/>"),
            false,
        )

        assertEquals(ad.simula.ad.sdk.model.CreativeType.VIDEO, video?.type)
        assertEquals("web", video?.destination)
        assertEquals("https://tracker.example/click", video?.trackingUrl)
        assertEquals("https://play.google.com/store/apps/details?id=android.app", video?.androidStoreUrl)
        assertEquals("https://apps.apple.com/app/id123", video?.iosStoreUrl)
        assertEquals(60, video?.adBehavior?.close?.delaySeconds)
        assertEquals(ad.simula.ad.sdk.model.CloseTreatment.COUNTDOWN_CIRCLE, video?.adBehavior?.close?.treatment)
        assertEquals(ad.simula.ad.sdk.model.ClosePosition.TOP_LEFT, video?.adBehavior?.close?.position)
        assertEquals(ad.simula.ad.sdk.model.CreativeType.PLAYABLE, unknown?.type)
        assertEquals(5, unknown?.adBehavior?.close?.delaySeconds)
        assertEquals(ad.simula.ad.sdk.model.CloseTreatment.COUNTDOWN_CIRCLE, unknown?.adBehavior?.close?.treatment)
    }

    @Test
    fun `fallback routing fields decode exact backend keys`() {
        val body = json.decodeFromString<FallbackAdBody>(
            """{
                "ad_id":"video",
                "type":"video",
                "url":"https://cdn.example/video.mp4",
                "destination":"appstore",
                "tracking_url":"https://tracker.example/click",
                "android_store_url":"https://play.google.com/store/apps/details?id=android.app",
                "ios_store_url":"https://apps.apple.com/app/id123"
            }""",
        )
        val ad = requireNotNull(SimulaApiClient.fallbackAdFromBody(body, false))

        assertEquals("appstore", ad.destination)
        assertEquals("https://tracker.example/click", ad.trackingUrl)
        assertEquals("https://play.google.com/store/apps/details?id=android.app", ad.androidStoreUrl)
        assertEquals("https://apps.apple.com/app/id123", ad.iosStoreUrl)
    }

    @Test
    fun `malformed optional fallback routing fields become null without dropping item`() {
        val response = json.decodeFromString<FallbackAdsApiResponse>(
            """{"ads":[{
                "ad_id":"video",
                "type":"video",
                "url":"https://cdn.example/video.mp4",
                "destination":{"bad":true},
                "tracking_url":42,
                "android_store_url":["bad"],
                "ios_store_url":false
            }]}""",
        )
        val ad = SimulaApiClient.fallbackAdsFromResponse(response).single()

        assertNull(ad.destination)
        assertNull(ad.trackingUrl)
        assertNull(ad.androidStoreUrl)
        assertNull(ad.iosStoreUrl)
        assertTrue(ad.routingFieldsPresent)
    }

    @Test
    fun `blank optional fallback routing fields normalize as absent`() {
        val response = json.decodeFromString<FallbackAdsApiResponse>(
            """{"ads":[{
                "ad_id":"video",
                "type":"video",
                "url":"https://cdn.example/video.mp4",
                "destination":"  ",
                "tracking_url":"\n",
                "android_store_url":" ",
                "ios_store_url":"\t"
            }]}""",
        )
        val ad = SimulaApiClient.fallbackAdsFromResponse(response).single()

        assertNull(ad.destination)
        assertNull(ad.trackingUrl)
        assertNull(ad.androidStoreUrl)
        assertNull(ad.iosStoreUrl)
        assertFalse(ad.routingFieldsPresent)
    }

    @Test
    fun `fallback close treatment trims whitespace before normalization`() {
        val hidden = fallbackAdBehavior(
            ApiAdBehavior(close = ApiCloseBehavior(treatment = "  hidden \n")),
        )
        val countdown = fallbackAdBehavior(
            ApiAdBehavior(close = ApiCloseBehavior(treatment = " countdown-circle ")),
        )

        assertEquals(ad.simula.ad.sdk.model.CloseTreatment.HIDDEN, hidden.close.treatment)
        assertEquals(ad.simula.ad.sdk.model.CloseTreatment.COUNTDOWN_CIRCLE, countdown.close.treatment)
    }

    @Test
    fun `fallback array decoding is lossy per item and retains original stage indices`() {
        val response = json.decodeFromString<FallbackAdsApiResponse>(
            """{"ads":[
                {"ad_id":"first","rendered_html":"<first/>"},
                42,
                {"ad_id":"bad","rendered_html":"<bad/>","ad_behavior":"invalid"},
                {"ad_id":"later","rendered_html":"<later/>"}
            ]}""",
        )
        val ads = SimulaApiClient.fallbackAdsFromResponse(response)

        assertEquals(listOf(0, 3), response.ads.map { it.sourceIndex })
        assertEquals(listOf("first", "later"), ads.map { it.adId })
        assertEquals(listOf(0, 3), ads.map { it.sourceIndex })
    }

    @Test
    fun `invalid creative skip does not renumber later fallback stage`() {
        val response = json.decodeFromString<FallbackAdsApiResponse>(
            """{"ads":[
                {"iframe_url":"https://ignored.example/only"},
                {"ad_id":"end-two","rendered_html":"<html/>"}
            ]}""",
        )
        val ads = SimulaApiClient.fallbackAdsFromResponse(response)

        assertEquals(1, ads.single().sourceIndex)
        assertEquals(
            ad.simula.ad.sdk.model.AutoStoreRedirectTrigger.END_SCREEN_2_OPEN,
            ad.simula.ad.sdk.model.endScreenTriggerForIndex(ads.single().sourceIndex),
        )
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
