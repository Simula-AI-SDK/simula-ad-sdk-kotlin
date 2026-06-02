package ad.simula.ad.sdk.provider

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ad.simula.ad.sdk.model.AdData
import ad.simula.ad.sdk.model.SimulaContextValue
import ad.simula.ad.sdk.privacy.SimulaPrivacy
import ad.simula.ad.sdk.privacy.SimulaPrivacyConfig
import kotlinx.coroutines.launch
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
 * @param hasPrivacyConsent Legacy coarse consent flag. When false, suppresses PII. Default true.
 * @param privacy       Granular privacy / consent configuration (GDPR/CCPA/GPP/COPPA + IDFA
 *                      opt-in). When provided it takes precedence over [hasPrivacyConsent];
 *                      when null the SDK seeds a config from [hasPrivacyConsent] and still
 *                      auto-reads IAB-standard CMP keys. See [SimulaPrivacy].
 * @param content       Child composable tree.
 */
@Composable
fun SimulaProvider(
    apiKey: String,
    devMode: Boolean = false,
    primaryUserID: String? = null,
    hasPrivacyConsent: Boolean = true,
    privacy: SimulaPrivacyConfig? = null,
    content: @Composable () -> Unit,
) {
    // Validate props early (matches React's validateSimulaProviderProps)
    require(apiKey.isNotBlank()) { "SimulaProvider requires a valid \"apiKey\" (non-blank string)" }

    val context = LocalContext.current

    // An explicit privacy config wins; otherwise the legacy hasPrivacyConsent flag
    // seeds it so existing call sites behave exactly as before.
    val resolvedConfig = remember(privacy, hasPrivacyConsent) {
        privacy ?: SimulaPrivacyConfig(hasPrivacyConsent = hasPrivacyConsent)
    }

    // Seed the store synchronously during composition so the FIRST session
    // reflects the explicit config (correct ppid gating) rather than the default
    // snapshot — avoids an initial consent-less /session/create.
    remember(resolvedConfig) {
        SimulaPrivacy.apply(resolvedConfig)
        resolvedConfig
    }

    // Attach for IAB auto-read and (re)read the GAID — off the first frame.
    LaunchedEffect(context, resolvedConfig) {
        SimulaPrivacy.attach(context)
        SimulaPrivacy.refreshAdvertisingId()
    }

    // Re-read the GAID on foreground: ad-tracking permission or the GAID itself
    // can change while the app is backgrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { SimulaPrivacy.refreshAdvertisingId() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Resolved consent (explicit overrides merged over auto-read IAB keys). Drives
    // ppid gating and — via the session key below — re-sync on CMP refresh.
    val consent by SimulaPrivacy.snapshot.collectAsState()

    // ppid is suppressed without consent and additionally under COPPA.
    val effectiveUserID = if (consent.allowsPrimaryUserID) primaryUserID else null

    // Session holder — keyed on the resolved consent so a CMP refresh recreates
    // the session and the backend sees current signals. Coalesces concurrent
    // creation, retryable on failure.
    val sessionStore = remember(apiKey, devMode, consent) {
        SimulaSessionStore(apiKey, devMode, effectiveUserID)
    }

    // Ad caching infrastructure — thread-safe so I/O coroutines can populate
    // these directly from any dispatcher (matching React's useRef<Map> pattern).
    val adCache = remember { ConcurrentHashMap<String, AdData>() }
    val heightCache = remember { ConcurrentHashMap<String, Float>() }
    val noFillSet = remember { ConcurrentHashMap.newKeySet<String>() }

    // Kick off session creation off the critical path.
    LaunchedEffect(sessionStore) {
        sessionStore.ensureSession()
    }

    // Build context value — equivalent to React's useMemo
    val contextValue = remember(apiKey, devMode, sessionStore.sessionId, consent) {
        SimulaContextValue(
            apiKey = apiKey,
            devMode = devMode,
            sessionId = sessionStore.sessionId,
            hasPrivacyConsent = consent.hasPrivacyConsent,
            consent = consent,
            updateConsent = { SimulaPrivacy.apply(it) },
            ensureSession = { sessionStore.ensureSession() },
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
