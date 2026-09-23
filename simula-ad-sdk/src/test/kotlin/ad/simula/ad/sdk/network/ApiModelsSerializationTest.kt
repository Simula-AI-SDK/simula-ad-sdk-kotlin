package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.model.CloseAction
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.CloseTreatment
import ad.simula.ad.sdk.model.isVideoPlanV2
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
                capabilities = ApiDeviceCapabilities(videoV1 = true, videoPlanV2 = true),
            ),
        ).jsonObject

        assertTrue(root.getValue("capabilities").jsonObject
            .getValue("native_click_beacon_v1").jsonPrimitive.boolean)
        assertTrue(root.getValue("capabilities").jsonObject
            .getValue("video_v1").jsonPrimitive.boolean)
        assertTrue(root.getValue("capabilities").jsonObject
            .getValue("video_plan_v2").jsonPrimitive.boolean)
        assertTrue(root.containsKey("privacy"))
    }

    @Test
    fun `load request preserves native click beacon capability`() {
        val root = json.parseToJsonElement(
            json.encodeToString(
                AdLoadRequestBody(
                    adUnitId = "unit",
                    capabilities = ApiDeviceCapabilities(videoV1 = true, videoPlanV2 = true),
                ),
            ),
        ).jsonObject

        assertTrue(root.getValue("capabilities").jsonObject
            .getValue("native_click_beacon_v1").jsonPrimitive.boolean)
        assertTrue(root.getValue("capabilities").jsonObject
            .getValue("video_v1").jsonPrimitive.boolean)
        assertTrue(root.getValue("capabilities").jsonObject
            .getValue("video_plan_v2").jsonPrimitive.boolean)
    }

    @Test
    fun `neutral capabilities do not advertise video support`() {
        assertFalse(ApiDeviceCapabilities().videoV1)
        assertFalse(ApiDeviceCapabilities().videoPlanV2)
    }

    @Test
    fun `fully wired capabilities advertise both video versions`() {
        val capabilities = ApiDeviceCapabilities(videoV1 = true, videoPlanV2 = true)
        val root = json.parseToJsonElement(json.encodeToString(capabilities)).jsonObject
        assertTrue(root.getValue("video_v1").jsonPrimitive.boolean)
        assertTrue(root.getValue("video_plan_v2").jsonPrimitive.boolean)
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
    fun `v2 fallback supports nested creative metadata and retains raw source index after dead clip`() {
        val payload = """
            {"video_plan_version":"video_plan_v2","ads":[
              {"ad_id":"dead","creative":{"type":"video","url":"not-a-url","clip_index":0}},
              {"ad_id":"live","creative":{"type":"video","url":"https://cdn.example/live.mp4",
                "cta":"Install","app_name":"Game","app_icon_url":"https://cdn.example/icon.png",
                "video_pool":"ugc","clip_index":1},"ad_behavior":{"video":{"style":"bottom_card"}}}
            ]}
        """.trimIndent()
        val response = json.decodeFromString<FallbackAdsApiResponse>(payload)
        val ads = SimulaApiClient.fallbackAdsFromResponse(response)

        assertEquals(1, ads.size)
        assertEquals(1, ads.single().sourceIndex)
        assertEquals(1, ads.single().clipIndex)
        assertEquals("ugc", ads.single().videoPool)
        assertEquals("Install", ads.single().cta)
        assertTrue(ads.single().isVideoPlanV2)
    }

    @Test
    fun `canonical fallback marker activates presentation but missing clip remains v1 slot`() {
        val response = json.decodeFromString<FallbackAdsApiResponse>(
            """{"video_plan_version":"video_plan_v2","ads":[
                {"ad_id":"v","type":"video","url":"https://cdn.example/video.mp4"}
            ]}""",
        )
        val ad = SimulaApiClient.fallbackAdsFromResponse(response).single()

        assertTrue(ad.videoPlanV2)
        assertFalse(ad.isVideoPlanV2)
        assertNull(ad.clipIndex)
    }

    @Test
    fun `v2 marker accepts only the exact trimmed backend literal`() {
        assertTrue(canonicalVideoPlanV2Marker(" video_plan_v2 "))
        assertFalse(canonicalVideoPlanV2Marker("VIDEO_PLAN_V2"))
        assertFalse(canonicalVideoPlanV2Marker("Video_Plan_V2"))

        val trimmed = SimulaApiClient.adLoadResultFromResponse(
            json.decodeFromString(
                """{"video_plan_version":" video_plan_v2 ","creative":{"type":"video","url":"https://cdn.example/v.mp4","clip_index":0}}""",
            ),
        )
        val primary = SimulaApiClient.adLoadResultFromResponse(
            json.decodeFromString(
                """{"video_plan_version":"VIDEO_PLAN_V2","creative":{"type":"video","url":"https://cdn.example/v.mp4","clip_index":0}}""",
            ),
        )
        val fallback = SimulaApiClient.fallbackAdsFromResponse(
            json.decodeFromString(
                """{"video_plan_version":"VIDEO_PLAN_V2","ads":[{"ad_id":"v","type":"video","url":"https://cdn.example/v.mp4","clip_index":0}]}""",
            ),
        ).single()

        assertTrue(trimmed.videoPlanV2)
        assertTrue(requireNotNull(trimmed.creative).isVideoPlanV2)
        assertFalse(primary.videoPlanV2)
        assertFalse(requireNotNull(primary.creative).isVideoPlanV2)
        assertFalse(fallback.videoPlanV2)
        assertFalse(fallback.isVideoPlanV2)
    }

    @Test
    fun `clip and alias markers cannot activate fallback v2 slots`() {
        val payloads = listOf(
            """{"ads":[{"ad_id":"v","type":"video","url":"https://cdn.example/v.mp4","clip_index":0}]}""",
            """{"plan_version":"video_plan_v2","ads":[{"ad_id":"v","type":"video","url":"https://cdn.example/v.mp4","clip_index":0}]}""",
            """{"creative_plan_version":"video_plan_v2","ads":[{"ad_id":"v","type":"video","url":"https://cdn.example/v.mp4","clip_index":0}]}""",
            """{"video_plan_v2":true,"ads":[{"ad_id":"v","type":"video","url":"https://cdn.example/v.mp4","clip_index":0}]}""",
        )

        payloads.forEach { payload ->
            val ad = SimulaApiClient.fallbackAdsFromResponse(
                json.decodeFromString<FallbackAdsApiResponse>(payload),
            ).single()
            assertFalse(ad.videoPlanV2)
            assertFalse(ad.isVideoPlanV2)
        }
    }

    @Test
    fun `primary playable keeps presentation v2 scope for a valid v2 fallback without becoming a video slot`() {
        val primary = SimulaApiClient.adLoadResultFromResponse(
            json.decodeFromString(
                """{"video_plan_version":"video_plan_v2","creative":{"type":"playable","clip_index":0},"rendered_html":"<html/>"}""",
            ),
        )
        val fallback = SimulaApiClient.fallbackAdsFromResponse(
            json.decodeFromString(
                """{"video_plan_version":"video_plan_v2","ads":[{"ad_id":"v","type":"video","url":"https://cdn.example/v.mp4","clip_index":0}]}""",
            ),
        ).single()

        assertTrue(primary.videoPlanV2)
        assertFalse(requireNotNull(primary.creative).isVideoPlanV2)
        assertNull(primary.creative?.planVersion)
        assertTrue(fallback.videoPlanV2)
        assertTrue(fallback.isVideoPlanV2)
    }

    @Test
    fun `malformed canonical marker and index degrade primary and fallback slots to v1`() {
        val primary = SimulaApiClient.adLoadResultFromResponse(
            json.decodeFromString(
                """{"video_plan_version":{},"creative":{"type":"video","url":"https://cdn.example/v.mp4","clip_index":"bad"}}""",
            ),
        )
        val fallback = SimulaApiClient.fallbackAdsFromResponse(
            json.decodeFromString(
                """{"video_plan_version":"video_plan_v2","ads":[{"ad_id":"v","type":"video","url":"https://cdn.example/v.mp4","clip_index":"bad"}]}""",
            ),
        ).single()

        assertFalse(primary.videoPlanV2)
        assertFalse(requireNotNull(primary.creative).isVideoPlanV2)
        assertFalse(fallback.isVideoPlanV2)
    }

    @Test
    fun `Android v2 fallback skoverlay defaults disabled but explicit true decodes`() {
        fun overlay(adBehavior: String?): ad.simula.ad.sdk.model.SkOverlayConfig? {
            val behavior = adBehavior?.let { ",\"ad_behavior\":$it" }.orEmpty()
            val response = json.decodeFromString<FallbackAdsApiResponse>(
                """{"video_plan_version":"video_plan_v2","ads":[{"ad_id":"v","type":"video","url":"https://cdn.example/v.mp4","clip_index":0$behavior}]}""",
            )
            return SimulaApiClient.fallbackAdsFromResponse(response).single().skoverlay
        }

        assertFalse(requireNotNull(overlay(null)).enabled)
        assertFalse(requireNotNull(overlay("{\"skoverlay\":{\"delay_seconds\":5}}")).enabled)
        assertTrue(requireNotNull(overlay("{\"skoverlay\":{\"enabled\":true}}")).enabled)
    }

    @Test
    fun `clip index is bounded to backend ordinals`() {
        val response = json.decodeFromString<FallbackAdsApiResponse>(
            """{"ads":[
                {"ad_id":"v","type":"video","url":"https://cdn.example/video.mp4","clip_index":3}
            ]}""",
        )
        val ad = SimulaApiClient.fallbackAdsFromResponse(response).single()

        assertNull(ad.clipIndex)
        assertFalse(ad.isVideoPlanV2)
    }

    @Test
    fun `fallback close defaults survive missing null and malformed config`() {
        val behaviorValues = listOf(
            null,
            "null",
            "true",
            "\"bad\"",
            "{}",
            "{\"close\":null}",
            "{\"close\":\"bad\"}",
            "{\"close\":{\"delay_seconds\":\"5\",\"treatment\":false,\"position\":1,\"action\":[]}}",
        )
        behaviorValues.forEachIndexed { index, behavior ->
            val key = behavior?.let { ",\"ad_behavior\":$it" }.orEmpty()
            val response = json.decodeFromString<FallbackAdsApiResponse>(
                """{"ads":[{"ad_id":"a$index","html":"<html/>"$key}]}""",
            )
            val close = requireNotNull(SimulaApiClient.fallbackAdFromBody(response.ads.single(), false)).closeBehavior
            assertEquals(5, close.delaySeconds)
            assertEquals(CloseTreatment.COUNTDOWN_CIRCLE, close.treatment)
            assertEquals(ClosePosition.TOP_RIGHT, close.position)
            assertEquals(CloseAction.CLOSE_X, close.action)
        }
    }

    @Test
    fun `fallback close config is bounded restricted and independent per usable item`() {
        val payload = """
            {"ads":[
              {"ad_id":"a1","html":"<html>1</html>","ad_behavior":{"close":{
                "delay_seconds":-3,"treatment":"hidden","position":"bottom-left","action":"FoRwArD"}}},
              {"ad_id":"a2","html":"<html>2</html>","ad_behavior":{"close":{
                "delay_seconds":90,"treatment":"progress_bar","position":"top-left","action":"forward"}}},
              {"ad_id":"a3","html":"<html>3</html>","ad_behavior":{"close":{
                "delay_seconds":7,"treatment":"reward_or_close_label","position":"unknown","action":"close-x"}}},
              {"ad_id":"a4","html":"<html>4</html>","ad_behavior":{"close":{"treatment":"unknown"}}},
              {"ad_id":"a5","html":"<html>5</html>","ad_behavior":{"close":{"delay_seconds":0}}}
            ]}
        """.trimIndent()
        val response = json.decodeFromString<FallbackAdsApiResponse>(payload)
        val closes = response.ads.map { body ->
            requireNotNull(SimulaApiClient.fallbackAdFromBody(body, false)).closeBehavior
        }

        assertEquals(listOf(0, 60, 7, 5, 0), closes.map { it.delaySeconds })
        assertEquals(CloseTreatment.HIDDEN, closes[0].treatment)
        assertTrue(closes.drop(1).all { it.treatment == CloseTreatment.COUNTDOWN_CIRCLE })
        assertEquals(ClosePosition.BOTTOM_LEFT, closes[0].position)
        assertEquals(ClosePosition.TOP_LEFT, closes[1].position)
        assertEquals(ClosePosition.TOP_RIGHT, closes[2].position)
        assertEquals(CloseAction.FORWARD, closes[0].action)
        assertEquals(CloseAction.FORWARD, closes[1].action)
        assertEquals(CloseAction.CLOSE_X, closes[2].action)
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
            json.decodeFromString<FallbackAdBody>(
                """{
                    "type":"video",
                    "url":"https://cdn.example/video.mp4",
                    "poster_url":"https://cdn.example/poster.jpg",
                    "destination":"web",
                    "tracking_url":"https://tracker.example/click",
                    "android_store_url":"https://play.google.com/store/apps/details?id=android.app",
                    "ios_store_url":"https://apps.apple.com/app/id123",
                    "ad_behavior":{"close":{
                        "delay_seconds":99,"treatment":"progress_bar","position":"top_left"
                    }}
                }""",
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
        assertEquals(60, video?.closeBehavior?.delaySeconds)
        assertEquals(ad.simula.ad.sdk.model.CloseTreatment.COUNTDOWN_CIRCLE, video?.closeBehavior?.treatment)
        assertEquals(ad.simula.ad.sdk.model.ClosePosition.TOP_LEFT, video?.closeBehavior?.position)
        assertEquals(ad.simula.ad.sdk.model.CreativeType.PLAYABLE, unknown?.type)
        assertEquals(5, unknown?.closeBehavior?.delaySeconds)
        assertEquals(ad.simula.ad.sdk.model.CloseTreatment.COUNTDOWN_CIRCLE, unknown?.closeBehavior?.treatment)
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
        val hidden = fallbackCloseBehavior(
            json.parseToJsonElement("""{"close":{"treatment":"  hidden \n"}}"""),
        )
        val countdown = fallbackCloseBehavior(
            json.parseToJsonElement("""{"close":{"treatment":" countdown-circle "}}"""),
        )

        assertEquals(ad.simula.ad.sdk.model.CloseTreatment.HIDDEN, hidden.treatment)
        assertEquals(ad.simula.ad.sdk.model.CloseTreatment.COUNTDOWN_CIRCLE, countdown.treatment)
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

        assertEquals(listOf(0, 2, 3), response.ads.map { it.sourceIndex })
        assertEquals(listOf("first", "bad", "later"), ads.map { it.adId })
        assertEquals(listOf(0, 2, 3), ads.map { it.sourceIndex })
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
