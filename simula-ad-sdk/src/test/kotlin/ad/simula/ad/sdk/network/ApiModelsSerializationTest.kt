package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.model.CloseAction
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.CloseTreatment
import ad.simula.ad.sdk.model.CreativeType
import ad.simula.ad.sdk.model.ProgressBarStyle
import ad.simula.ad.sdk.model.RewardEarnAt
import ad.simula.ad.sdk.model.isRenderable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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
    fun `fallback inline HTML retains a valid legacy origin and rejects non HTTP bases`() {
        for ((value, expected) in listOf(
            "\"https://api.example/iframe/serve\"" to "https://api.example/iframe/serve",
            "\"javascript:alert(1)\"" to null,
            "\"file:///etc/passwd\"" to null,
            "42" to null,
        )) {
            val response = json.decodeFromString<FallbackAdsApiResponse>(
                """{"ads":[{"ad_id":"a","html":"<a href='next'>Next</a>","iframe_url":$value}]}""",
            )
            val fallback = SimulaApiClient.fallbackAdsFromResponse(response).single()
            assertEquals(expected, fallback.creativeBaseUrl)
            assertTrue(fallback.renderedHtml?.contains("href='next'") == true)
        }
    }

    @Test
    fun `contract 2 ES1 video retains segments and progress treatment while ES2 video is rejected`() {
        val response = json.decodeFromString<FallbackAdsApiResponse>(
            """{"video_contract":2,"ads":[
              {"type":"video","url":"https://cdn.example/es1.mp4",
               "creative":{"type":"video","url":"https://cdn.example/es1.mp4",
                 "segments":[{"clip_index":0,"video_pool":"trailer","start_seconds":0,"end_seconds":10}]},
               "ad_behavior":{"progress_bar":{"style":"two_tone"}}},
              {"type":"video","url":"https://cdn.example/es2.mp4"}
            ]}""",
        )
        val fallback = SimulaApiClient.fallbackAdsFromResponse(response).single()
        assertEquals(0, fallback.sourceIndex)
        assertEquals(1, fallback.segments.size)
        assertEquals("trailer", fallback.segments.single().videoPool)
        assertEquals(ad.simula.ad.sdk.model.ProgressBarStyle.TWO_TONE, fallback.progressBarStyle)
    }

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
    fun `session advertises native click support but no retired video capabilities`() {
        val root = json.parseToJsonElement(
            sessionCreateBody(
                privacy = buildJsonObject { put("hasPrivacyConsent", true) },
                capabilities = ApiDeviceCapabilities(),
            ),
        ).jsonObject

        val capabilities = root.getValue("capabilities").jsonObject
        assertTrue(capabilities.containsKey("native_click_beacon_v1"))
        assertFalse(capabilities.containsKey("video_v1"))
        assertFalse(capabilities.containsKey("video_plan_v2"))
        assertFalse(root.containsKey("contracts"))
        assertTrue(root.containsKey("privacy"))
    }

    @Test
    fun `imperative load requests advertise only numeric video contract 2`() {
        val interstitial = json.parseToJsonElement(
            json.encodeToString(AdLoadRequestBody(adUnitId = "unit")),
        ).jsonObject
        val rewarded = json.parseToJsonElement(
            json.encodeToString(RewardedInitRequestBody(adUnitId = "unit")),
        ).jsonObject
        assertEquals(2, interstitial.getValue("contracts").jsonObject.getValue("video").jsonPrimitive.int)
        assertEquals(2, rewarded.getValue("contracts").jsonObject.getValue("video").jsonPrimitive.int)
        assertFalse(interstitial.toString().contains("video_plan_v2"))
        assertFalse(interstitial.toString().contains("video_v1"))
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
    fun `contract 2 accepts ES1 video and preserves playable source index`() {
        val response = json.decodeFromString<FallbackAdsApiResponse>(
            """{"video_contract":2,"ads":[
                {"ad_id":"video","type":"video","url":"https://cdn.example/video.mp4"},
                42,
                {"ad_id":"playable","rendered_html":"<html/>"}
            ]}""",
        )
        val ads = SimulaApiClient.fallbackAdsFromResponse(response)

        assertEquals(listOf(0, 2), response.ads.map { it.sourceIndex })
        assertEquals(listOf("video", "playable"), ads.map { it.adId })
        assertEquals(listOf(0, 2), ads.map { it.sourceIndex })
        assertTrue(ads.first().videoContract2)
    }

    @Test
    fun `only exact numeric response contract activates`() {
        fun result(marker: String) = SimulaApiClient.adLoadResultFromResponse(
            json.decodeFromString(
                """{"video_contract":$marker,"creative":{"type":"video","url":"https://cdn.example/v.mp4"}}""",
            ),
        )
        assertTrue(result("2").videoContract2)
        assertFalse(result("\"2\"").videoContract2)
        assertFalse(result("2.0").videoContract2)
        assertFalse(
            SimulaApiClient.adLoadResultFromResponse(
                json.decodeFromString(
                    """{"video_plan_version":"video_plan_v2","creative":{"type":"video","url":"https://cdn.example/v.mp4"}}""",
                ),
            ).videoContract2,
        )
    }

    @Test
    fun `stitched segment boundaries preserve canonical values with one millisecond tolerance`() {
        val accepted = validatedVideoSegments(
            listOf(
                ApiVideoSegment(0, "ugc", 0.0, 2.5),
                ApiVideoSegment(1, "brand", 2.501, 5.0),
            ),
        )
        assertEquals(2.5, accepted[1].startSeconds, 0.0)
        assertTrue(
            validatedVideoSegments(
                listOf(
                    ApiVideoSegment(0, "ugc", 0.0, 2.0),
                    ApiVideoSegment(1, "brand", 2.0011, 3.0),
                ),
            ).isEmpty(),
        )
        assertTrue(
            validatedVideoSegments(listOf(ApiVideoSegment(0, "x".repeat(65), 0.0, 1.0))).isEmpty(),
        )
        assertTrue(
            "end must exceed the supplied start before canonicalization",
            validatedVideoSegments(
                listOf(
                    ApiVideoSegment(0, "ugc", 0.0, 2.5),
                    ApiVideoSegment(1, "brand", 2.5009, 2.5008),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `one malformed segment discards the whole list without dropping stitched video`() {
        val response = json.decodeFromString<AdLoadApiResponse>(
            """{"video_contract":2,"creative":{"type":"video","url":"https://cdn.example/v.mp4",
                "segments":[
                    {"clip_index":0,"video_pool":"ugc","start_seconds":0,"end_seconds":2},
                    {"clip_index":"1","video_pool":"brand","start_seconds":2,"end_seconds":4},
                    {"clip_index":2,"video_pool":"brand","start_seconds":4,"end_seconds":6}
                ]}}""",
        )
        val result = SimulaApiClient.adLoadResultFromResponse(response)

        assertEquals(CreativeType.VIDEO, result.creative?.type)
        assertEquals("https://cdn.example/v.mp4", result.creative?.url)
        assertTrue(result.creative?.segments?.isEmpty() == true)
        assertTrue(requireNotNull(result.creative).isRenderable(result.renderedHtml))
    }

    @Test
    fun `malformed optional creative and behavior containers degrade independently`() {
        val primary = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_inserted":true,"rendered_html":"<html/>","creative":42,"ad_behavior":[]}""",
        )
        val rewarded = json.decodeFromString<RewardedInitApiResponse>(
            """{"rendered_html":"<html/>","creative":"bad","ad_behavior":false}""",
        )

        val primaryResult = SimulaApiClient.adLoadResultFromResponse(primary)
        val rewardedResult = SimulaApiClient.rewardedResultFromResponse(rewarded)
        assertNull(primaryResult.creative)
        assertNull(primaryResult.adBehavior)
        assertEquals("<html/>", primaryResult.renderedHtml)
        assertEquals(CreativeType.PLAYABLE, rewardedResult.creative.type)
        assertNull(rewardedResult.adBehavior)
        assertEquals("<html/>", rewardedResult.renderedHtml)
    }

    @Test
    fun `malformed optional creative fields do not reject behavior or response`() {
        val response = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_inserted":true,"rendered_html":"<html/>",
                "creative":{"type":{},"url":"https://cdn.example/v.mp4"},
                "ad_behavior":{"close":{"delay_seconds":3}}}""",
        )
        val result = SimulaApiClient.adLoadResultFromResponse(response)

        assertNull(result.creative)
        assertEquals(3, result.adBehavior?.close?.delaySeconds)
        assertEquals("<html/>", result.renderedHtml)
    }

    @Test
    fun `missing or malformed marker preserves v1 video but cannot activate contract 2 features`() {
        for (marker in listOf("", ",\"video_contract\":\"2\"", ",\"video_contract\":2.0")) {
            val response = json.decodeFromString<AdLoadApiResponse>(
                """{"ad_inserted":true,"impression_url":"https://measure.example/view",
                    "creative":{"type":"video","url":"https://cdn.example/v.mp4","video_pool":"ugc",
                        "clip_index":0,"segments":[{"clip_index":0,"video_pool":"ugc","start_seconds":0,"end_seconds":4}]},
                    "ad_behavior":{"video":{"style":"feed_card"},"reward":{"earn_at":"unit_end"},
                        "progress_bar":{"style":"two_tone"}}$marker}""",
            )
            val result = SimulaApiClient.adLoadResultFromResponse(response)

            assertFalse(result.videoContract2)
            assertEquals(CreativeType.VIDEO, result.creative?.type)
            assertEquals("https://cdn.example/v.mp4", result.creative?.url)
            assertTrue(result.creative?.segments?.isEmpty() == true)
            assertNull(result.creative?.videoPool)
            assertNull(result.creative?.clipIndex)
            assertNull(result.adBehavior?.video)
            assertNull(result.adBehavior?.reward)
            assertEquals(ProgressBarStyle.SINGLE, result.adBehavior?.progressBar?.style)
            assertNull(result.impressionUrl)
        }
    }

    @Test
    fun `unit end two tone and contract overlay defaults decode canonically`() {
        val response = json.decodeFromString<RewardedInitApiResponse>(
            """{"video_contract":2,"ad_behavior":{
                "reward":{"earn_at":"unit_end"},
                "progress_bar":{"style":"two_tone"},
                "skoverlay":{"delay_seconds":99}
            }}""",
        )
        val behavior = response.adBehavior.toDomain(videoContract2 = true)
        assertEquals(RewardEarnAt.UNIT_END, behavior?.reward?.earnAt)
        assertEquals(ProgressBarStyle.TWO_TONE, behavior?.progressBar?.style)
        assertEquals(3, behavior?.skoverlay?.delaySeconds)

        val legacy = json.decodeFromString<AdLoadApiResponse>(
            """{"ad_behavior":{"skoverlay":{"delay_seconds":300}}}""",
        ).adBehavior.toDomain(videoContract2 = false)
        assertEquals(300, legacy?.skoverlay?.delaySeconds)

        listOf("-1", "61", "\"7\"", "{}", "null").forEach { value ->
            val primary = json.decodeFromString<AdLoadApiResponse>(
                """{"video_contract":2,"ad_behavior":{"skoverlay":{"delay_seconds":$value}}}""",
            ).adBehavior.toDomain(videoContract2 = true)
            val fallback = fallbackSkOverlayConfig(
                json.parseToJsonElement("""{"skoverlay":{"delay_seconds":$value}}"""),
                videoContract2 = true,
            )
            assertEquals(3, primary?.skoverlay?.delaySeconds)
            assertEquals(3, fallback?.delaySeconds)
        }
        listOf(0, 60).forEach { value ->
            val valid = json.decodeFromString<AdLoadApiResponse>(
                """{"video_contract":2,"ad_behavior":{"skoverlay":{"delay_seconds":$value}}}""",
            ).adBehavior.toDomain(videoContract2 = true)
            assertEquals(value, valid?.skoverlay?.delaySeconds)
        }
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
        assertEquals(listOf("first", "bad"), ads.map { it.adId })
        assertEquals(listOf(0, 2), ads.map { it.sourceIndex })
    }

    @Test
    fun `fourth video segment invalidates before segment mapping`() {
        val creative = json.decodeFromString<ApiCreative>(
            """{"segments":[
                {"clip_index":0,"video_pool":"a","start_seconds":0.0,"end_seconds":1.0},
                {"clip_index":1,"video_pool":"b","start_seconds":1.0,"end_seconds":2.0},
                {"clip_index":2,"video_pool":"c","start_seconds":2.0,"end_seconds":3.0},
                {"clip_index":3,"video_pool":"d","start_seconds":3.0,"end_seconds":4.0}
            ]}""",
        )

        assertTrue(creative.segments.isEmpty())
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
