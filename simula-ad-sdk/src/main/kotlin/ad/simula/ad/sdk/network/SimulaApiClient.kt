package ad.simula.ad.sdk.network

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * API client for Simula ad platform.
 * All functions are suspend and safe to call from coroutine scopes.
 * Mirrors the React SDK's utils/api.ts exactly.
 */
object SimulaApiClient {

    private const val API_BASE_URL = "https://simula-api-701226639755.us-central1.run.app"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

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
            val urlBuilder = "$API_BASE_URL/session/create".toHttpUrl().newBuilder()
            if (devMode != null) {
                urlBuilder.addQueryParameter("devMode", devMode.toString())
            }
            if (!primaryUserID.isNullOrBlank()) {
                urlBuilder.addQueryParameter("ppid", primaryUserID)
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = client.newCall(request).execute()

            if (response.code == 401) {
                throw IllegalArgumentException(
                    "Invalid API key (please check dashboard or contact Simula team for a valid API key)"
                )
            }
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val data = json.decodeFromString<SessionResponse>(body)
            data.sessionId?.takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            throw e // Re-throw 401 errors
        } catch (e: Exception) {
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
        val request = Request.Builder()
            .url("$API_BASE_URL/minigames/catalogv2")
            .get()
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP error! status: ${response.code}")
        }

        val body = response.body?.string()
            ?: throw Exception("Empty response body")

        val responseJson = json.parseToJsonElement(body).jsonObject

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

        val request = Request.Builder()
            .url("$API_BASE_URL/minigames/init")
            .post(json.encodeToString(requestBody).toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP error! status: ${response.code}")
        }

        val body = response.body?.string()
            ?: throw Exception("Empty response body")
        val data = json.decodeFromString<MinigameApiResponse>(body)

        MinigameResult(
            adId = data.adResponse?.adId ?: "",
            iframeUrl = data.adResponse?.iframeUrl ?: "",
        )
    }

    // ── Minigame Fallback Ad ────────────────────────────────────────────────

    /**
     * Fetch fallback ad iframe URL for a minigame ad ID.
     * Returns the iframe URL or null if not available.
     */
    suspend fun fetchAdForMinigame(adId: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_BASE_URL/minigames/fallback_ad/$adId")
                .post("".toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val data = json.decodeFromString<MinigameApiResponse>(body)
            data.adResponse?.iframeUrl
        } catch (e: Exception) {
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
            val request = Request.Builder()
                .url("$API_BASE_URL/minigames/menu/track/click")
                .post(json.encodeToString(clickBody).toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            client.newCall(request).execute()
        } catch (_: Exception) {
            // Silently fail — tracking is best effort
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
            val request = Request.Builder()
                .url("$API_BASE_URL/track/engagement/impression/$adId")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            client.newCall(request).execute()
        } catch (_: Exception) {
            // Silently fail
        }
    }
}
