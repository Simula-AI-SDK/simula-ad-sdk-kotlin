package ad.simula.ad.sdk.provider

import android.util.Log
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
import ad.simula.ad.sdk.ads.SimulaAds
import ad.simula.ad.sdk.model.AdData
import ad.simula.ad.sdk.model.SimulaAdContext
import ad.simula.ad.sdk.model.SimulaContextValue
import ad.simula.ad.sdk.nativead.NativeAdContextStore
import ad.simula.ad.sdk.network.SimulaConnectionType
import ad.simula.ad.sdk.network.SimulaDeviceId
import ad.simula.ad.sdk.network.SimulaDeviceSignals
import ad.simula.ad.sdk.network.SimulaUserAgent
import ad.simula.ad.sdk.privacy.SimulaPrivacy
import ad.simula.ad.sdk.privacy.SimulaPrivacyConfig
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * CompositionLocal providing the Simula context to child composables.
 *
 * Normally supplied by a [SimulaProvider] scope. When none is present — e.g. a
 * standalone [ad.simula.ad.sdk.nativead.NativeAdSlot] hosted directly by the React
 * Native wrapper, with no Compose `SimulaProvider` around it — it falls back to a
 * process-global context backed by the session [ad.simula.ad.sdk.ads.SimulaAds.initialize]
 * warmed. That way every such slot reuses ONE session instead of minting one per
 * composition (mirrors iOS, where a `NativeAdSlot` reads `SimulaAds.shared`).
 */
internal val LocalSimulaContext = staticCompositionLocalOf<SimulaContextValue> {
    globalSimulaContext()
}

// Process-global ad caches backing the fallback context (used by the older imperative
// menu ad path; native ads use the standalone NativeAdCache object). Kept tiny and
// thread-safe so the fallback is a complete, valid SimulaContextValue.
/** Size cap for the legacy host-facing ad caches (getCachedAd/cacheAd/markNoFill). They have no
 *  internal callers, but a host using the public cache API across many distinct slots would otherwise
 *  grow them without bound for the process lifetime — so each evicts its eldest beyond this many. */
private const val MAX_AD_CACHE_ENTRIES = 64

/** Thread-safe, access-ordered LRU map capped at [max] entries (eldest evicted on overflow). */
private fun <K, V> boundedLruMap(max: Int): MutableMap<K, V> =
    java.util.Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > max
        },
    )

/** Thread-safe set capped at [max] entries (eldest evicted on overflow). */
private fun boundedLruSet(max: Int): MutableSet<String> =
    java.util.Collections.newSetFromMap(boundedLruMap<String, Boolean>(max))

private val globalAdCache: MutableMap<String, AdData> = boundedLruMap(MAX_AD_CACHE_ENTRIES)
private val globalHeightCache: MutableMap<String, Float> = boundedLruMap(MAX_AD_CACHE_ENTRIES)
private val globalNoFillSet: MutableSet<String> = boundedLruSet(MAX_AD_CACHE_ENTRIES)

// Built once and reused. Only ever touched on the main thread (the
// staticCompositionLocalOf default factory runs during composition), so no
// synchronization is needed.
private var cachedGlobalContext: SimulaContextValue? = null

// Inert fallback (built once) for the not-yet-initialized case, plus a one-shot log guard so a
// misintegrated host is warned once rather than on every composition.
private var cachedEmptyContext: SimulaContextValue? = null
private var emptyContextWarned = false

/**
 * Builds (once) a [SimulaContextValue] backed by the global session that
 * [ad.simula.ad.sdk.ads.SimulaAds.initialize] warmed — the fallback used by
 * [LocalSimulaContext] when no [SimulaProvider] is in the tree.
 *
 * `ensureSession` and `apiKey` read the live global store, so a hosted `NativeAdSlot`
 * resolves the same warmed session every other surface uses. The snapshot fields
 * (`sessionId`, `consent`) are not consulted by the native-ad path (it reads targeting
 * from [ad.simula.ad.sdk.nativead.NativeAdContextStore] and consent from
 * [SimulaPrivacy] at request time), so caching a single instance is safe.
 *
 * @throws IllegalStateException if [ad.simula.ad.sdk.ads.SimulaAds.initialize] has not run.
 */
internal fun globalSimulaContext(): SimulaContextValue {
    // The SDK must never crash the host (PRD). A NativeAdSlot composed with no SimulaProvider
    // ancestor AND before SimulaAds.initialize() reaches here via the LocalSimulaContext default
    // factory; return an inert (empty) context so the slot renders blank instead of throwing
    // IllegalStateException into the host's composition.
    if (!SimulaAds.isInitialized) {
        if (!emptyContextWarned) {
            emptyContextWarned = true
            Log.w(
                "SimulaAdSDK",
                "NativeAdSlot used before SimulaAds.initialize() and outside a SimulaProvider — " +
                    "rendering a blank slot. Initialize the SDK or wrap the slot in a SimulaProvider.",
            )
        }
        return emptySimulaContext()
    }
    cachedGlobalContext?.let { return it }
    val consent = SimulaPrivacy.current
    return SimulaContextValue(
        apiKey = SimulaAds.apiKey,
        devMode = SimulaAds.devMode,
        sessionId = SimulaAds.store.sessionId,
        hasPrivacyConsent = consent.hasPrivacyConsent,
        consent = consent,
        updateConsent = { SimulaPrivacy.apply(it) },
        ensureSession = { SimulaAds.store.ensureSession() },
        getCachedAd = { slot, position -> globalAdCache[getCacheKey(slot, position)] },
        cacheAd = { slot, position, ad -> globalAdCache[getCacheKey(slot, position)] = ad },
        getCachedHeight = { slot, position -> globalHeightCache[getCacheKey(slot, position)] },
        cacheHeight = { slot, position, height -> globalHeightCache[getCacheKey(slot, position)] = height },
        hasNoFill = { slot, position -> globalNoFillSet.contains(getCacheKey(slot, position)) },
        markNoFill = { slot, position -> globalNoFillSet.add(getCacheKey(slot, position)) },
    ).also { cachedGlobalContext = it }
}

/**
 * Inert context returned by [globalSimulaContext] before [ad.simula.ad.sdk.ads.SimulaAds.initialize]
 * has run (and with no [SimulaProvider] in the tree): a valid, empty value with no api key and no
 * session, so a [ad.simula.ad.sdk.nativead.NativeAdSlot] resolves to a no-fill and renders blank
 * rather than crashing the host. Never reads the lateinit [ad.simula.ad.sdk.ads.SimulaAds.store].
 * Cached so repeated reads return a stable instance.
 */
private fun emptySimulaContext(): SimulaContextValue {
    cachedEmptyContext?.let { return it }
    val consent = SimulaPrivacy.current
    return SimulaContextValue(
        apiKey = "",
        devMode = false,
        sessionId = null,
        hasPrivacyConsent = consent.hasPrivacyConsent,
        consent = consent,
        updateConsent = { SimulaPrivacy.apply(it) },
        ensureSession = { null },
        getCachedAd = { slot, position -> globalAdCache[getCacheKey(slot, position)] },
        cacheAd = { slot, position, ad -> globalAdCache[getCacheKey(slot, position)] = ad },
        getCachedHeight = { slot, position -> globalHeightCache[getCacheKey(slot, position)] },
        cacheHeight = { slot, position, height -> globalHeightCache[getCacheKey(slot, position)] = height },
        hasNoFill = { slot, position -> globalNoFillSet.contains(getCacheKey(slot, position)) },
        markNoFill = { slot, position -> globalNoFillSet.add(getCacheKey(slot, position)) },
    ).also { cachedEmptyContext = it }
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
 * @param adContext     Native-ad targeting context auto-attached to every `POST /load/native`
 *                      (search term, tags, category, …). Updating it replaces the value in full;
 *                      can also be set at runtime via [ad.simula.ad.sdk.ads.SimulaAds.updateContext].
 * @param content       Child composable tree.
 */
@OptIn(FlowPreview::class) // Flow.debounce — stable in practice, contained to this module.
@Composable
fun SimulaProvider(
    apiKey: String,
    devMode: Boolean = false,
    primaryUserID: String? = null,
    hasPrivacyConsent: Boolean = true,
    privacy: SimulaPrivacyConfig? = null,
    adContext: SimulaAdContext? = null,
    content: @Composable () -> Unit,
) {
    // Validate props early (matches React's validateSimulaProviderProps)
    require(apiKey.isNotBlank()) { "SimulaProvider requires a valid \"apiKey\" (non-blank string)" }

    // Seed the process-wide native-ad context so NativeAdSlot requests carry it. A full replace on
    // change (mirrors the privacy seed below); preloaded ads keep the value current at preload time.
    remember(adContext) { NativeAdContextStore.set(adContext); adContext }

    val context = LocalContext.current

    // Build the custom User-Agent synchronously during composition (cheap, Build statics) so it's set
    // before the first /session/create. The device id is a synchronous ContentProvider read, so it's
    // resolved off the main thread via prime() rather than blocking composition. The imperative
    // SimulaAds.initialize() path primes them too; first wins.
    remember(context) {
        SimulaUserAgent.build(context.applicationContext)
        SimulaDeviceId.prime(context.applicationContext)
        // Independent of telemetry: the X-Connection-Type header must work even when a host
        // never enables telemetry. Idempotent — the imperative SimulaAds.initialize() path
        // primes it too; first wins.
        SimulaConnectionType.prime(context.applicationContext)
        // Device-context signals (timezone, storage, memory, battery, volume) attached to every API
        // request. Idempotent; primed off the first frame like the signals above.
        SimulaDeviceSignals.prime(context.applicationContext)
    }

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

    // CMPs write the IAB keys in a burst; debounce the snapshot that drives session
    // re-sync so a settled consent state triggers one /session/create, not a race.
    val sessionConsent by remember { SimulaPrivacy.snapshot.debounce(300L) }
        .collectAsState(initial = SimulaPrivacy.current)

    // ppid is suppressed without consent and additionally under COPPA.
    val effectiveUserID = primaryUserID

    // Session holder — keyed on the (debounced) consent so a CMP refresh recreates
    // the session and the backend sees current signals. Coalesces concurrent
    // creation, retryable on failure.
    val sessionStore = remember(apiKey, devMode, sessionConsent) {
        SimulaSessionStore(apiKey, devMode, effectiveUserID)
    }

    // Delegate cache + context construction to the shared builder. The imperative
    // interstitial Activity uses the same path with a session warmed by SimulaAds,
    // so the two entry points stay in lock-step.
    ProvideSimulaContext(sessionStore, apiKey, devMode, content)
}

/**
 * Builds [LocalSimulaContext] from an existing [SimulaSessionStore] and provides it
 * to [content].
 *
 * Extracted from [SimulaProvider] so the imperative interstitial Activity
 * ([ad.simula.ad.sdk.ads.SimulaInterstitialActivity]) can reuse the session warmed
 * by `SimulaAds.initialize()` instead of creating a new one. Consent is read from
 * the process-wide [SimulaPrivacy] snapshot, so both entry points present identical
 * privacy signals to the nested game/ad composables.
 */
@Composable
internal fun ProvideSimulaContext(
    store: SimulaSessionStore,
    apiKey: String,
    devMode: Boolean,
    content: @Composable () -> Unit,
) {
    // Resolved consent (explicit overrides merged over auto-read IAB keys); drives
    // ppid gating and the context value.
    val consent by SimulaPrivacy.snapshot.collectAsState()

    // Ad caching infrastructure — thread-safe, so I/O coroutines can populate
    // these directly from any dispatcher (matching React's useRef<Map> pattern).
    val adCache = remember { boundedLruMap<String, AdData>(MAX_AD_CACHE_ENTRIES) }
    val heightCache = remember { boundedLruMap<String, Float>(MAX_AD_CACHE_ENTRIES) }
    val noFillSet = remember { boundedLruSet(MAX_AD_CACHE_ENTRIES) }

    // Kick off session creation off the critical path (idempotent / coalesced).
    LaunchedEffect(store) {
        store.ensureSession()
    }

    // Build context value — equivalent to React's useMemo
    val contextValue = remember(apiKey, devMode, store.sessionId, consent) {
        SimulaContextValue(
            apiKey = apiKey,
            devMode = devMode,
            sessionId = store.sessionId,
            hasPrivacyConsent = consent.hasPrivacyConsent,
            consent = consent,
            updateConsent = { SimulaPrivacy.apply(it) },
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
