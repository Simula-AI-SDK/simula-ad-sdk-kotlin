package ad.simula.ad.sdk.minigame

import ad.simula.ad.sdk.BuildConfig
import ad.simula.ad.sdk.ads.SimulaApiEnvironment
import ad.simula.ad.sdk.ads.SimulaAds
import ad.simula.ad.sdk.network.ApiEndpointConfiguration
import ad.simula.ad.sdk.network.ApiEnvironment
import ad.simula.ad.sdk.network.ApiEnvironmentResolverInputs
import ad.simula.ad.sdk.network.CatalogHttpException
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.network.readStagingEnvironmentManifestValue
import ad.simula.ad.sdk.network.resolveApiEnvironment
import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CatalogGame(val id: String, val name: String)
data class GameCatalog(val menuId: String, val games: List<CatalogGame>)

/** A catalog session has an immutable environment and never changes the host's ad session. */
class SimulaGameCatalog private constructor(
    private val apiKey: String,
    private val primaryUserID: String?,
    private val endpoint: ApiEndpointConfiguration,
) {
    private val sessionLock = Mutex()
    private var sessionId: String? = null

    suspend fun fetch(): GameCatalog {
        val result = SimulaApiClient.fetchCatalog(endpointConfiguration = endpoint)
        return GameCatalog(result.menuId, result.games.map { CatalogGame(it.id, it.name) })
    }

    /** Initializes exactly one game. The caller owns menu-expiry recovery and presentation. */
    suspend fun load(
        menuId: String,
        gameId: String,
        companionEnabled: Boolean? = null,
        width: Int,
        height: Int,
        charId: String,
        charName: String,
        charImage: String,
        charDesc: String? = null,
    ): String = sessionLock.withLock {
        // Consent and startup infrastructure must precede the scoped session too.
        SimulaAds.startupGate?.await()
        val sid = sessionId ?: SimulaApiClient.createSession(
            apiKey, false, primaryUserID, endpoint,
        )?.also { sessionId = it } ?: throw IllegalStateException("Session unavailable")
        try {
            val result = SimulaApiClient.getMinigame(
                gameType = gameId, sessionId = sid, screenWidth = width, screenHeight = height,
                charId = charId, charName = charName, charImage = charImage, charDesc = charDesc,
                menuId = menuId, endpointConfiguration = endpoint, companionEnabled = companionEnabled,
            )
            result.iframeUrl.takeIf { it.startsWith("https://") }
                ?: throw IllegalStateException("Invalid game response")
        } catch (error: CatalogHttpException) {
            if (error.status == 401) sessionId = null
            throw error
        }
    }

    companion object {
        /** Returns null when staging is unavailable; never silently substitutes production. */
        fun create(context: Context, apiKey: String, environment: SimulaApiEnvironment, primaryUserID: String?): SimulaGameCatalog? {
            if (apiKey.isBlank()) return null
            val endpoint = resolveApiEnvironment(ApiEnvironmentResolverInputs(
                requestedEnvironment = environment,
                stagingCapable = BuildConfig.SIMULA_STAGING_CAPABLE,
                stagingBaseUrl = BuildConfig.SIMULA_STAGING_BASE_URL,
                stagingManifestValue = readStagingEnvironmentManifestValue(context),
            ))
            if (environment == SimulaApiEnvironment.Staging && endpoint.environment != ApiEnvironment.Staging) return null
            return SimulaGameCatalog(apiKey, primaryUserID, endpoint)
        }
    }
}

/** Reuses the SDK player for a URL returned by [SimulaGameCatalog.load]. */
@Composable
fun CatalogGamePlayer(iframeUrl: String, onClose: () -> Unit) {
    GameWebViewWithPreparedUrl(
        gameId = "catalog", charID = "", charName = "", charImage = "",
        onClose = onClose, preparedIframeUrl = iframeUrl,
    )
}
