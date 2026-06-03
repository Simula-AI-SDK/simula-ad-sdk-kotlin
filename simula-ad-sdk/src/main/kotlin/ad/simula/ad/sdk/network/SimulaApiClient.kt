package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.model.AdBehavior
import ad.simula.ad.sdk.model.GameData
import ad.simula.ad.sdk.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private val jsonHeaders = mapOf("Content-Type" to "application/json")

    private fun authHeaders(apiKey: String) =
        jsonHeaders + ("Authorization" to "Bearer $apiKey")

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

            val response = SimulaHttp.request(
                url = url,
                method = "POST",
                headers = authHeaders(apiKey),
                body = "{}",
            )

            if (response.code == 401) {
                throw IllegalArgumentException(
                    "Invalid API key (please check dashboard or contact Simula team for a valid API key)"
                )
            }
            if (!response.isSuccessful) return@withContext null

            val data = json.decodeFromString<SessionResponse>(response.body)
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
     */
    suspend fun fetchCatalog(): CatalogResult = withContext(Dispatchers.IO) {
        val response = SimulaHttp.request(
            url = "$API_BASE_URL/minigames/catalogv2",
            method = "GET",
            headers = jsonHeaders,
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

    // ── Minigame Init ───────────────────────────────────────────────────────

    data class MinigameResult(
        val adId: String,
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
            headers = jsonHeaders,
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
            iframeUrl = data.adResponse?.iframeUrl ?: "",
        )
    }

    // ── Native Creative Ad Load ─────────────────────────────────────────────

    data class AdLoadResult(
        val adId: String,
        val adInserted: Boolean,
        val adUnitId: String,
        val rewarded: Boolean,
        val destination: String,
        val renderedFormat: String?,
        val renderedAssets: List<String>,
        val trackingUrl: String?,
        // Null when the payload omits `ad_behavior` (renderer falls back to today's defaults).
        val adBehavior: AdBehavior?,
    )

    /**
     * Load a native-creative interstitial via `POST /ads/load`.
     *
     * `ad_inserted == false` is a valid no-fill response (NOT an error); callers
     * inspect [AdLoadResult.adInserted]/[AdLoadResult.renderedAssets] to decide.
     */
    suspend fun loadAd(
        adUnitId: String,
        rewarded: Boolean = false,
        sessionId: String = "",
    ): AdLoadResult = withContext(Dispatchers.IO) {
        val requestBody = AdLoadRequestBody(
            adUnitId = adUnitId,
            rewarded = rewarded,
            sessionId = sessionId,
        )

        val response = SimulaHttp.request(
            url = "$API_BASE_URL/ads/load",
            method = "POST",
            headers = jsonHeaders,
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
            adId = data.adId,
            adInserted = data.adInserted,
            adUnitId = data.adUnitId,
            rewarded = data.rewarded,
            destination = data.destination,
            renderedFormat = data.renderedFormat,
            renderedAssets = data.renderedAssets,
            trackingUrl = data.trackingUrl,
            adBehavior = data.adBehavior.toDomain(),
        )
    }

    // ── Minigame Fallback Ad ────────────────────────────────────────────────

    /**
     * Fetch fallback ad iframe URL for a minigame ad ID.
     * Returns the iframe URL or null if not available.
     */
    suspend fun fetchAdForMinigame(adId: String): String? = withContext(Dispatchers.IO) {
        try {
            val response = SimulaHttp.request(
                url = "$API_BASE_URL/minigames/fallback_ad/$adId",
                method = "POST",
                headers = jsonHeaders,
                body = "",
            )
            if (!response.isSuccessful) return@withContext null

            val data = json.decodeFromString<MinigameApiResponse>(response.body)
            data.adResponse?.iframeUrl
        } catch (_: Exception) {
            null
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
     * Track an ad impression. Best-effort, silently fails.
     */
    suspend fun trackImpression(
        adId: String,
        apiKey: String,
    ): Unit = withContext(Dispatchers.IO) {
        try {
            SimulaHttp.request(
                url = "$API_BASE_URL/track/engagement/impression/$adId",
                method = "POST",
                headers = authHeaders(apiKey),
                body = "{}",
            )
        } catch (_: Exception) {
            // Silently fail
        }
    }
}
