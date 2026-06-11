package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.model.AdBehavior
import ad.simula.ad.sdk.model.AdUnitType
import ad.simula.ad.sdk.model.Creative
import ad.simula.ad.sdk.model.Experiment
import ad.simula.ad.sdk.model.GameData
import ad.simula.ad.sdk.model.Message
import ad.simula.ad.sdk.privacy.SimulaPrivacy
import ad.simula.ad.sdk.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * API client for Simula ad platform.
 * All functions are suspend and safe to call from coroutine scopes.
 * Mirrors the React SDK's utils/api.ts exactly.
 *
 * Networking goes through [SimulaHttp] (native [java.net.HttpURLConnection]) —
 * no third-party HTTP dependency. JSON stays on kotlinx.serialization.
 */
internal object SimulaApiClient {

    private const val API_BASE_URL = "https://simula-api-701226639755.us-central1.run.app"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // Consent signals ride along on every request from this single chokepoint,
    // read from the process-wide store (see SimulaPrivacy).
    private fun jsonHeaders(): Map<String, String> =
        mapOf("Content-Type" to "application/json") + SimulaPrivacy.current.consentHeaders()

    private fun authHeaders(apiKey: String) =
        jsonHeaders() + ("Authorization" to "Bearer $apiKey")

    // ── Session ─────────────────────────────────────────────────────────────

    /**
     * Create a server session and return its id.
     * Returns null on network failure (but throws on 401 invalid API key).
     */
    suspend fun createSession(
        apiKey: String,
        devMode: Boolean? = null,
        primaryUserID: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val params = buildList {
                if (devMode != null) add("devMode=$devMode")
                if (!primaryUserID.isNullOrBlank()) {
                    add("ppid=${URLEncoder.encode(primaryUserID, "UTF-8")}")
                }
            }
            val url = "$API_BASE_URL/session/create" +
                if (params.isEmpty()) "" else "?" + params.joinToString("&")

            // Establish consent at session creation: the backend ties the `privacy`
            // block to the session and inherits it on subsequent calls.
            val body = buildJsonObject {
                put("privacy", SimulaPrivacy.current.privacyJson())
            }.toString()

            val response = SimulaHttp.request(
                url = url,
                method = "POST",
                headers = authHeaders(apiKey),
                body = body,
            )

            if (response.code == 401) {
                throw IllegalArgumentException(
                    "Invalid API key (please check dashboard or contact Simula team for a valid API key)"
                )
            }
            if (!response.isSuccessful) return@withContext null

            val data = json.decodeFromString<SessionResponse>(response.body)
            // Honor a server-side telemetry directive (kill-switch / sampling) if present.
            if (data.telemetryEnabled != null || data.telemetrySampleRate != null) {
                Telemetry.applyServerConfig(
                    enabled = data.telemetryEnabled ?: true,
                    sampleRate = data.telemetrySampleRate ?: 1.0,
                )
            }
            data.sessionId?.takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            throw e // Re-throw 401 errors
        } catch (_: Exception) {
            null
        }
    }

    // ── Catalog ─────────────────────────────────────────────────────────────

    data class CatalogResult(
        val menuId: String,
        val games: List<GameData>,
    )

    /**
     * Fetch the game catalog. Returns menuId + list of games.
     * Handles both new format (catalog field) and legacy format (data field).
     *
     * [sessionId] is passed through as the `session_id` query param when available (the
     * backend ties the catalog to the session); omitted when null/blank.
     */
    suspend fun fetchCatalog(sessionId: String? = null): CatalogResult = withContext(Dispatchers.IO) {
        val response = SimulaHttp.request(
            url = catalogUrl(sessionId),
            method = "GET",
            headers = jsonHeaders(),
        )
        if (!response.isSuccessful) {
            throw Exception("HTTP error! status: ${response.code}")
        }
        if (response.body.isBlank()) {
            throw Exception("Empty response body")
        }

        val responseJson = json.parseToJsonElement(response.body).jsonObject

        val menuId = responseJson["menu_id"]?.jsonPrimitive?.content ?: ""

        // Handle different response formats: catalog.data or direct data array
        val gamesList: JsonArray = run {
            val catalog = responseJson["catalog"]
            if (catalog != null) {
                when {
                    catalog is JsonArray -> catalog
                    catalog is JsonObject && catalog.containsKey("data") ->
                        catalog["data"]!!.jsonArray
                    else ->
                        responseJson["data"]?.jsonArray ?: JsonArray(emptyList())
                }
            } else {
                responseJson["data"]?.jsonArray ?: JsonArray(emptyList())
            }
        }

        // Map API response to GameData format (icon -> iconUrl)
        val games = gamesList.map { element ->
            val obj = element.jsonObject
            GameData(
                id = obj["id"]?.jsonPrimitive?.content ?: "",
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                iconUrl = obj["icon"]?.jsonPrimitive?.content ?: "",
                description = obj["description"]?.jsonPrimitive?.content ?: "",
                iconFallback = obj["iconFallback"]?.jsonPrimitive?.content,
                gifCover = (obj["gif_cover"] ?: obj["gifCover"])?.jsonPrimitive?.content,
            )
        }

        CatalogResult(menuId = menuId, games = games)
    }

    /** Builds the catalog request URL, adding `session_id` when available. Pure/testable. */
    internal fun catalogUrl(sessionId: String?): String = buildString {
        append("$API_BASE_URL/minigames/catalog")
        if (!sessionId.isNullOrBlank()) {
            append("?session_id=${URLEncoder.encode(sessionId, "UTF-8")}")
        }
    }

    // ── Minigame Init ───────────────────────────────────────────────────────

    data class MinigameResult(
        val adId: String,
        // The minigame serve id — the handle for the post-game `fetchFallbacks` call.
        val serveId: String,
        val iframeUrl: String,
    )

    /**
     * Initialize a minigame and get the iframe URL + ad ID.
     */
    suspend fun getMinigame(
        gameType: String,
        sessionId: String,
        currencyMode: Boolean = false,
        screenWidth: Int,
        screenHeight: Int,
        charId: String? = null,
        charName: String? = null,
        charImage: String? = null,
        charDesc: String? = null,
        messages: List<Message>? = null,
        delegateChar: Boolean = true,
        menuId: String? = null,
    ): MinigameResult = withContext(Dispatchers.IO) {
        val requestBody = InitMinigameRequestBody(
            gameType = gameType,
            sessionId = sessionId,
            currencyMode = currencyMode,
            w = screenWidth,
            h = screenHeight,
            charId = charId,
            charName = charName,
            charImage = charImage,
            charDesc = charDesc,
            messages = messages?.map { MessageBody(role = it.role, content = it.content) },
            delegateChar = delegateChar,
            menuId = menuId,
        )

        val response = SimulaHttp.request(
            url = "$API_BASE_URL/minigames/init",
            method = "POST",
            headers = jsonHeaders(),
            body = json.encodeToString(requestBody),
        )
        if (!response.isSuccessful) {
            throw Exception("HTTP error! status: ${response.code}")
        }
        if (response.body.isBlank()) {
            throw Exception("Empty response body")
        }
        val data = json.decodeFromString<MinigameApiResponse>(response.body)

        MinigameResult(
            adId = data.adResponse?.adId ?: "",
            serveId = data.adResponse?.serveId ?: "",
            iframeUrl = data.adResponse?.iframeUrl ?: "",
        )
    }

    // ── Native Creative Ad Load ─────────────────────────────────────────────

    data class AdLoadResult(
        // The impression id — the SDK's single handle for fallbacks, tracking and reporting.
        // Empty on a no-fill.
        val impressionId: String,
        val adInserted: Boolean,
        val adUnitId: String,
        val destination: String,
        val renderedFormat: String?,
        val trackingUrl: String?,
        val renderedHtml: String?,
        // Null when the payload omits `ad_behavior` (renderer falls back to today's defaults).
        val adBehavior: AdBehavior? = null,
        // Creative descriptor (`creative` node) and experiment metadata; null when omitted.
        val creative: Creative? = null,
        val experiment: Experiment? = null,
    ) {
        /** The ad format. Prefers the nested `creative.ad_unit_type`; falls back to the legacy
         * `rendered_format` (the imperative HTML model dropped the flat `rewarded` flag). Drives
         * close copy. */
        val adUnitType: AdUnitType
            get() = creative?.adUnitType
                ?: if (renderedFormat == "rewarded_video") AdUnitType.REWARDED else AdUnitType.INTERSTITIAL
    }

    /**
     * Load a native-creative interstitial via `POST /ads/load/interstitial`.
     *
     * `ad_inserted == false` is a valid no-fill response (NOT an error); callers
     * inspect [AdLoadResult.adInserted]/[AdLoadResult.renderedHtml] to decide.
     */
    suspend fun loadAd(
        adUnitId: String,
        sessionId: String = "",
        charId: String? = null,
        charName: String? = null,
        charImage: String? = null,
        charDesc: String? = null,
    ): AdLoadResult = withContext(Dispatchers.IO) {
        val requestBody = AdLoadRequestBody(
            adUnitId = adUnitId,
            sessionId = sessionId,
            charId = charId,
            charName = charName,
            charImage = charImage,
            charDesc = charDesc,
            capabilities = currentDeviceCapabilities(),
        )

        val response = SimulaHttp.request(
            url = "$API_BASE_URL/load/interstitial",
            method = "POST",
            headers = jsonHeaders(),
            body = json.encodeToString(requestBody),
        )
        if (!response.isSuccessful) {
            throw Exception("HTTP error! status: ${response.code}")
        }
        if (response.body.isBlank()) {
            throw Exception("Empty response body")
        }
        val data = json.decodeFromString<AdLoadApiResponse>(response.body)

        AdLoadResult(
            impressionId = data.impressionId.orEmpty(),
            adInserted = data.adInserted,
            adUnitId = data.adUnitId,
            destination = data.destination,
            renderedFormat = data.renderedFormat,
            trackingUrl = data.trackingUrl,
            renderedHtml = data.renderedHtml,
            adBehavior = data.adBehavior.toDomain(),
            creative = data.creative.toDomain(),
            experiment = data.experiment.toDomain(),
        )
    }

    // ── Rewarded Minigame ───────────────────────────────────────────────────

    data class RewardedInitResult(
        // The impression id — replaces the old `serve_id`/`ad_id` pair as the single handle
        // for verify-reward, fallbacks, tracking and reporting.
        val impressionId: String,
        val iframeUrl: String,
        val durationSeconds: Int,
        // Mid-ad store prompt routing + config (mirrors the interstitial). `adBehavior` is
        // null when the payload omits `ad_behavior` → no store prompt.
        val destination: String = "appstore",
        val trackingUrl: String? = null,
        val adBehavior: AdBehavior? = null,
    )

    /**
     * Initialize a rewarded minigame via `POST /load/rewarded`. Returns the iframe URL,
     * the `impression_id` tying this play to its later verification, and the
     * `duration_seconds` the SDK must enforce before a reward can be earned.
     */
    suspend fun loadRewarded(
        adUnitId: String,
        sessionId: String = "",
        minPlayThreshold: Int? = null,
        charId: String? = null,
        charName: String? = null,
        charImage: String? = null,
        charDesc: String? = null,
    ): RewardedInitResult = withContext(Dispatchers.IO) {
        val requestBody = RewardedInitRequestBody(
            adUnitId = adUnitId,
            sessionId = sessionId,
            minPlayThreshold = minPlayThreshold,
            charId = charId,
            charName = charName,
            charImage = charImage,
            charDesc = charDesc,
        )
        val response = SimulaHttp.request(
            url = "$API_BASE_URL/load/rewarded",
            method = "POST",
            headers = jsonHeaders(),
            body = json.encodeToString(requestBody),
        )
        if (!response.isSuccessful) {
            throw Exception("HTTP error! status: ${response.code}")
        }
        if (response.body.isBlank()) {
            throw Exception("Empty response body")
        }
        val data = json.decodeFromString<RewardedInitApiResponse>(response.body)
        RewardedInitResult(
            impressionId = data.impressionId,
            iframeUrl = data.iframeUrl,
            durationSeconds = data.durationSeconds,
            destination = data.destination,
            trackingUrl = data.trackingUrl,
            adBehavior = data.adBehavior.toDomain(),
        )
    }

    /**
     * Verify a rewarded play via `POST /minigames/verify-reward`. On a verified call
     * the backend fires the publisher's SSV postback server-side and returns the reward
     * token. Idempotent: a repeated call for the same `serve_id` returns HTTP 409, which
     * is treated here as a successful (already-claimed) verification so retries converge
     * without double-rewarding.
     */
    suspend fun verifyReward(
        serveId: String,
        sessionId: String,
        elapsedPlayTime: Double,
    ): VerifyRewardApiResponse = withContext(Dispatchers.IO) {
        val requestBody = VerifyRewardRequestBody(
            serveId = serveId,
            sessionId = sessionId,
            elapsedPlayTime = elapsedPlayTime,
        )
        val response = SimulaHttp.request(
            url = "$API_BASE_URL/minigames/verify-reward",
            method = "POST",
            headers = jsonHeaders(),
            body = json.encodeToString(requestBody),
        )
        // Already claimed — treat as a successful idempotent verification.
        if (response.code == 409) {
            return@withContext VerifyRewardApiResponse(verified = true, token = null)
        }
        if (!response.isSuccessful) {
            throw Exception("HTTP error! status: ${response.code}")
        }
        if (response.body.isBlank()) {
            throw Exception("Empty response body")
        }
        json.decodeFromString<VerifyRewardApiResponse>(response.body)
    }

    // ── Fallback Ads ────────────────────────────────────────────────────────

    /** One post-play ad screen from `GET /load/fallbacks/{impression_id}`. [adId] is the
     * screen's own impression id (drives its report overlay). */
    data class FallbackAd(
        val adId: String,
        val iframeUrl: String,
    )

    /**
     * Fetch the fallback ad screens for a serve via `GET /load/fallbacks/{impression_id}`,
     * keyed on the `impression_id` the load call returned. Returns every screen linked to
     * the serve (campaign creative, then the "Get the App" end screen) in reveal order.
     * Side-effect-free on the backend; best-effort here — an empty list on any failure.
     */
    suspend fun fetchFallbacks(impressionId: String): List<FallbackAd> = withContext(Dispatchers.IO) {
        try {
            val response = SimulaHttp.request(
                url = "$API_BASE_URL/load/fallbacks/$impressionId",
                method = "GET",
                headers = jsonHeaders(),
            )
            if (!response.isSuccessful) return@withContext emptyList()

            val data = json.decodeFromString<FallbackAdsApiResponse>(response.body)
            data.ads.mapNotNull { ad ->
                val url = ad.iframeUrl
                if (url.isNullOrBlank()) null
                else FallbackAd(
                    adId = ad.adId,
                    iframeUrl = url,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Tracking ────────────────────────────────────────────────────────────

    /**
     * Track a menu game click. Best-effort, silently fails.
     */
    suspend fun trackMenuGameClick(
        menuId: String,
        gameName: String,
        apiKey: String,
    ): Unit = withContext(Dispatchers.IO) {
        try {
            val clickBody = MenuGameClickBody(menuId = menuId, gameName = gameName)
            SimulaHttp.request(
                url = "$API_BASE_URL/minigames/menu/track/click",
                method = "POST",
                headers = authHeaders(apiKey),
                body = json.encodeToString(clickBody),
            )
        } catch (_: Exception) {
            // Silently fail — tracking is the best effort
        }
    }

    /**
     * Track an ad impression. Best-effort, silently fails. When the load response carried an
     * `experiment` node, its assignment metadata rides along so impressions can be attributed
     * to the A/B variant.
     */
    suspend fun trackImpression(
        adId: String,
        apiKey: String,
        experiment: Experiment? = null,
    ): Unit = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                experiment?.experimentId?.let { put("experiment_id", it) }
                experiment?.variantId?.let { put("variant_id", it) }
                experiment?.layer?.let { put("layer", it) }
            }
            SimulaHttp.request(
                url = "$API_BASE_URL/track/engagement/impression/$adId",
                method = "POST",
                headers = authHeaders(apiKey),
                body = json.encodeToString(body),
            )
        } catch (_: Exception) {
            // Silently fail
        }
    }

    /**
     * Submit a user-initiated ad report against the impression (`POST /impressions/{adId}/report`).
     * [flag] is one of the `AdReportReason` wire values; [note] is optional free text. The backend
     * stores flag + note against the ad serve (no moderation on receipt). Best-effort, silently
     * fails — a report must never disrupt the ad experience.
     */
    suspend fun reportAd(
        adId: String,
        flag: String,
        note: String? = null,
        apiKey: String,
    ): Unit = withContext(Dispatchers.IO) {
        if (adId.isBlank()) return@withContext
        try {
            val reportBody = buildJsonObject {
                put("flag", flag)
                if (!note.isNullOrBlank()) put("note", note)
            }
            SimulaHttp.request(
                url = "$API_BASE_URL/impressions/$adId/report",
                method = "POST",
                headers = authHeaders(apiKey),
                body = json.encodeToString(reportBody),
            )
        } catch (_: Exception) {
            // Silently fail
        }
    }

    // ── Telemetry ───────────────────────────────────────────────────────────

    /**
     * Deliver a telemetry batch (`POST /v1/telemetry/events`), reusing the auth + consent
     * headers so it inherits the same privacy posture as tracking. Returns the HTTP status
     * (or -1 on a connectivity failure) for the caller to map to accept/drop/retry. Passes
     * `instrument = false` so this request is never itself recorded as a network event.
     */
    suspend fun postTelemetry(apiKey: String, body: String): Int = withContext(Dispatchers.IO) {
        try {
            SimulaHttp.request(
                url = "$API_BASE_URL/v1/telemetry/events",
                method = "POST",
                headers = authHeaders(apiKey),
                body = body,
                instrument = false,
            ).code
        } catch (_: Exception) {
            -1
        }
    }
}
