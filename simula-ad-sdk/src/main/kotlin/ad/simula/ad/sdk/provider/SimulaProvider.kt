package ad.simula.ad.sdk.provider

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import ad.simula.ad.sdk.model.AdData
import ad.simula.ad.sdk.model.SimulaContextValue
import ad.simula.ad.sdk.network.SimulaApiClient

/**
 * CompositionLocal providing the Simula context to child composables.
 * Must be accessed within a [SimulaProvider] composable scope.
 */
val LocalSimulaContext = staticCompositionLocalOf<SimulaContextValue> {
    error("useSimula() / LocalSimulaContext must be used within a SimulaProvider")
}

/**
 * Convenience accessor for the Simula context value.
 * Equivalent to React's useSimula() hook.
 */
@Composable
fun useSimula(): SimulaContextValue = LocalSimulaContext.current

/**
 * Cache key helper — matches React's getCacheKey(slot, position).
 */
private fun getCacheKey(slot: String, position: Int): String = "$slot:$position"

/**
 * Root provider for the Simula Ad SDK.
 * Wraps content with a CompositionLocal that provides session, API key, and ad caching.
 *
 * Equivalent to React's <SimulaProvider apiKey={...} devMode={...} ...>{children}</SimulaProvider>
 *
 * @param apiKey        Your Simula API key (required, non-blank).
 * @param devMode       Enable dev mode for testing. Default false.
 * @param primaryUserID Optional user identifier for targeting.
 * @param hasPrivacyConsent Privacy consent flag. When false, suppresses PII. Default true.
 * @param content       Child composable tree.
 */
@Composable
fun SimulaProvider(
    apiKey: String,
    devMode: Boolean = false,
    primaryUserID: String? = null,
    hasPrivacyConsent: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Validate props early (matches React's validateSimulaProviderProps)
    require(apiKey.isNotBlank()) { "SimulaProvider requires a valid \"apiKey\" (non-blank string)" }

    var sessionId by remember { mutableStateOf<String?>(null) }

    // Ad caching infrastructure (matching React's useRef<Map> pattern)
    val adCache = remember { mutableMapOf<String, AdData>() }
    val heightCache = remember { mutableMapOf<String, Float>() }
    val noFillSet = remember { mutableSetOf<String>() }

    // Session creation — equivalent to React's useEffect([apiKey, devMode, primaryUserID, hasPrivacyConsent])
    LaunchedEffect(apiKey, devMode, primaryUserID, hasPrivacyConsent) {
        val effectiveUserID = if (hasPrivacyConsent) primaryUserID else null
        val id = SimulaApiClient.createSession(apiKey, devMode, effectiveUserID)
        if (id != null) {
            sessionId = id
        }
    }

    // Build context value — equivalent to React's useMemo
    val contextValue = remember(apiKey, devMode, sessionId, hasPrivacyConsent) {
        SimulaContextValue(
            apiKey = apiKey,
            devMode = devMode,
            sessionId = sessionId,
            hasPrivacyConsent = hasPrivacyConsent,
            getCachedAd = { slot, position ->
                adCache[getCacheKey(slot, position)]
            },
            cacheAd = { slot, position, ad ->
                adCache[getCacheKey(slot, position)] = ad
            },
            getCachedHeight = { slot, position ->
                heightCache[getCacheKey(slot, position)]
            },
            cacheHeight = { slot, position, height ->
                heightCache[getCacheKey(slot, position)] = height
            },
            hasNoFill = { slot, position ->
                noFillSet.contains(getCacheKey(slot, position))
            },
            markNoFill = { slot, position ->
                noFillSet.add(getCacheKey(slot, position))
            },
        )
    }

    CompositionLocalProvider(LocalSimulaContext provides contextValue) {
        content()
    }
}
