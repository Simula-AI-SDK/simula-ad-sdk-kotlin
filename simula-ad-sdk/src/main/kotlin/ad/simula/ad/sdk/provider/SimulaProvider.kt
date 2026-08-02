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
import ad.simula.ad.sdk.ads.SimulaAds
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.model.AdData
import ad.simula.ad.sdk.model.SimulaAdContext
import ad.simula.ad.sdk.model.SimulaContextValue
import ad.simula.ad.sdk.nativead.NativeAdContextStore
import ad.simula.ad.sdk.network.AdBeaconManager
import ad.simula.ad.sdk.network.SimulaConnectionType
import ad.simula.ad.sdk.network.SimulaDeviceId
import ad.simula.ad.sdk.network.SimulaDeviceSignals
import ad.simula.ad.sdk.network.SimulaUserAgent
import ad.simula.ad.sdk.privacy.SimulaPrivacy
import ad.simula.ad.sdk.privacy.SimulaPrivacyConfig
import ad.simula.ad.sdk.telemetry.SimulaTelemetryStartup
import ad.simula.ad.sdk.telemetry.awaitTelemetryReady
import ad.simula.ad.sdk.telemetry.Telemetry
import kotlinx.coroutines.CompletableDeferred
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

// Inert fallback (built once) for the not-yet-initialized case.
private var cachedEmptyContext: SimulaContextValue? = null

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
        // Telemetry may not exist yet; its facade buffers this one-shot and consumes it after
        // either provider or imperative startup publishes the telemetry manager.
        Telemetry.recordProviderMissingContext()
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

/** One provider session and the immutable prerequisite gate every request from it awaits. */
private data class ProviderSessionStartup(
    val store: SimulaSessionStore,
    val ready: CompletableDeferred<Unit>,
)

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
 * @param telemetryEnabled Opt out of in-house SDK telemetry and crash diagnostics. Default true.
 *                      First-registration-wins: in mixed integrations (or when [SimulaAds.initialize]
 *                      ran first), the first entry point to register fixes this value for the
 *                      process, and changing it at runtime has no effect.
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
    telemetryEnabled: Boolean = true,
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
    //
    // Mixed hosts (SimulaAds.initialize + SimulaProvider): a provider WITHOUT an
    // explicit privacy config must not re-seed defaults over the imperative config —
    // apply() replaces the store wholesale, which would wipe enableAdvertisingId and
    // any GAID the imperative startup already collected. An explicit provider config
    // always wins (deliberate host choice); declarative-only hosts always seed.
    remember(resolvedConfig) {
        if (privacy != null || !SimulaAds.isInitialized) {
            SimulaPrivacy.apply(resolvedConfig)
        }
        resolvedConfig
    }

    // Re-read the GAID when the applied privacy config changes at runtime (host flips
    // enableAdvertisingId, CMP consent arrives) — not only on startup / ON_RESUME, or ads
    // and session creation keep using a stale advertising id until the next foregrounding.
    // Safe before attach (a no-op that doesn't consume the freshness stamp) and self-gated
    // (consent gate + TTL + coalescing), so an unchanged config costs nothing. Disabling
    // collection needs no trigger: apply()/update() clear the collected id synchronously.
    LaunchedEffect(resolvedConfig) {
        SimulaPrivacy.refreshAdvertisingId()
    }

    // Re-read the GAID on foreground: ad-tracking permission or the GAID itself
    // can change while the app is backgrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> scope.launch { SimulaPrivacy.refreshAdvertisingId() }
                // Declarative-only apps do not install SimulaAds' ActivityLifecycleCallbacks.
                // Flush here too so buffered telemetry is persisted as the host backgrounds.
                Lifecycle.Event.ON_STOP -> Telemetry.flush()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // CMPs write the IAB keys in a burst; debounce the snapshot that drives session
    // re-sync so a settled consent state triggers one /session/create, not a race.
    val sessionConsent by remember { SimulaPrivacy.snapshot.debounce(300L) }
        .collectAsState(initial = SimulaPrivacy.current)

    // ppid is attached as provided: consent/COPPA currently gate the advertising id (see
    // SimulaPrivacy), not the PPID — `SimulaPrivacyConfig.allowsPrimaryUserID` exists but is
    // not yet wired into the pipeline (pending product/privacy decision; it would also have
    // to cover /session/create and the ppid PATCH).
    val effectiveUserID = primaryUserID

    // Session holder — keyed on the (debounced) consent so a CMP refresh recreates
    // the session and the backend sees current signals. Coalesces concurrent
    // creation, retryable on failure.
    val providerStartup = remember(apiKey, devMode, effectiveUserID, sessionConsent) {
        val gate = CompletableDeferred<Unit>()
        val store = SimulaSessionStore(apiKey, devMode, effectiveUserID).apply {
            // Fixed for this store's lifetime. It never switches to a later imperative gate.
            startupGate = gate
        }
        ProviderSessionStartup(store, gate)
    }
    val sessionStore = providerStartup.store

    // Commit process-wide first-wins configuration only after this composition commits. The actual
    // prerequisites run in SimulaScope so disposing the composition cannot strand this store's fixed
    // gate. Every provider request waits for privacy attach, shared telemetry readiness, GAID,
    // and the durable beacon manager. (Crash-guard install is fired ungated by the engine — it
    // needs the main thread and ads must never wait on main-thread health.) Recovery draining
    // starts only after manager initialization and is safe to duplicate with imperative startup
    // (AdBeaconQueue serializes drains internally).
    LaunchedEffect(providerStartup, context, telemetryEnabled) {
        // Hoisted so the long-lived startup job never retains an Activity context.
        val appContext = context.applicationContext
        val telemetryReady = SimulaTelemetryStartup.register(
            context = appContext,
            apiKey = apiKey,
            devMode = devMode,
            enabled = telemetryEnabled,
            sessionIdProvider = { sessionStore.sessionId },
            primaryUserIdProvider = { sessionStore.effectiveUserID },
        )
        try {
            val startupJob = SimulaScope.launch {
                var beaconManagerReady = false
                try {
                    runCatching { SimulaPrivacy.attach(appContext) }
                    SimulaTelemetryStartup.start()
                    // Abandon only this wait on timeout. The process-wide startup keeps running,
                    // while this provider's gate is completed in finally and stays fail-open.
                    awaitTelemetryReady(telemetryReady)
                    // The beacon build has no dependency on the GAID read — ordered first so a
                    // wedged Play Services bind can never starve billing-beacon delivery.
                    beaconManagerReady = runCatching {
                        AdBeaconManager.init(appContext, apiKey)
                    }.isSuccess
                    runCatching { SimulaPrivacy.refreshAdvertisingId() }
                } finally {
                    providerStartup.ready.complete(Unit)
                }
                if (beaconManagerReady) runCatching { AdBeaconManager.triggerProcessQueue() }
            }
            // Also covers cancellation before the coroutine body gets a chance to enter its finally.
            startupJob.invokeOnCompletion { providerStartup.ready.complete(Unit) }
        } catch (_: Exception) {
            providerStartup.ready.complete(Unit)
        }
    }

    // Delegate cache + context construction to the shared builder. The imperative
    // interstitial Activity uses the same path with a session warmed by SimulaAds,
    // so the two entry points stay in lock-step.
    ProvideSimulaContext(sessionStore, apiKey, devMode, content)
}

/** Binary bridge for clients compiled against the pre-1.1.6 provider descriptor. */
@Deprecated("Binary compatibility bridge", level = DeprecationLevel.HIDDEN)
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
    SimulaProvider(
        apiKey = apiKey,
        devMode = devMode,
        primaryUserID = primaryUserID,
        hasPrivacyConsent = hasPrivacyConsent,
        privacy = privacy,
        adContext = adContext,
        telemetryEnabled = true,
        content = content,
    )
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

    // Kick off session creation off the critical path. The store's startup gate sequences privacy,
    // telemetry, crash guard, and GAID before this request for both public entry paths.
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
