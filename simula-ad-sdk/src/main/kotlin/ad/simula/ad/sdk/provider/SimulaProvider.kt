package ad.simula.ad.sdk.provider

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import ad.simula.ad.sdk.model.AdData
import ad.simula.ad.sdk.model.SimulaContextValue
import java.util.concurrent.ConcurrentHashMap

/**
 * CompositionLocal providing the Simula context to child composables.
 * Must be accessed within a [SimulaProvider] composable scope.
 */
internal val LocalSimulaContext = staticCompositionLocalOf<SimulaContextValue> {
    error("useSimula() / LocalSimulaContext must be used within a SimulaProvider")
}

/**
 * Convenience accessor for the Simula context value.
 * Equivalent to React's useSimula() hook.
 */
@Composable
internal fun useSimula(): SimulaContextValue = LocalSimulaContext.current

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

    val effectiveUserID = if (hasPrivacyConsent) primaryUserID else null

    // Session holder — coalesces concurrent creation, retryable on failure.
    val sessionStore = remember(apiKey, devMode, effectiveUserID) {
        SimulaSessionStore(apiKey, devMode, effectiveUserID)
    }

    ProvideSimulaContext(sessionStore, apiKey, devMode, hasPrivacyConsent, content)
}

/**
 * Provides [LocalSimulaContext] built from an existing [SimulaSessionStore].
 *
 * Extracted so the imperative interstitial Activity can reuse the session warmed
 * by `SimulaAds.initialize()` instead of creating a new one.
 */
@Composable
internal fun ProvideSimulaContext(
    store: SimulaSessionStore,
    apiKey: String,
    devMode: Boolean,
    hasPrivacyConsent: Boolean,
    content: @Composable () -> Unit,
) {
    // Ad caching infrastructure — thread-safe so I/O coroutines can populate
    // these directly from any dispatcher (matching React's useRef<Map> pattern).
    val adCache = remember { ConcurrentHashMap<String, AdData>() }
    val heightCache = remember { ConcurrentHashMap<String, Float>() }
    val noFillSet = remember { ConcurrentHashMap.newKeySet<String>() }

    // Kick off session creation off the critical path (idempotent / coalesced).
    LaunchedEffect(store) {
        store.ensureSession()
    }

    // Build context value — equivalent to React's useMemo
    val contextValue = remember(apiKey, devMode, store.sessionId, hasPrivacyConsent) {
        SimulaContextValue(
            apiKey = apiKey,
            devMode = devMode,
            sessionId = store.sessionId,
            hasPrivacyConsent = hasPrivacyConsent,
            ensureSession = { store.ensureSession() },
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
