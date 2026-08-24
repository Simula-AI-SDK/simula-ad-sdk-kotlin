package ad.simula.ad.sdk.provider

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ad.simula.ad.sdk.ads.SimulaAds
import ad.simula.ad.sdk.core.ApiKeyOwnership
import ad.simula.ad.sdk.core.PostCommitApiKeyClaim
import ad.simula.ad.sdk.core.ProcessApiKeyOwner
import ad.simula.ad.sdk.core.ProcessLaunchSettledGate
import ad.simula.ad.sdk.model.AdData
import ad.simula.ad.sdk.model.SimulaAdContext
import ad.simula.ad.sdk.model.SimulaContextValue
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.nativead.NativeAdContextStore
import ad.simula.ad.sdk.network.SimulaConnectionType
import ad.simula.ad.sdk.network.SimulaDeviceId
import ad.simula.ad.sdk.network.SimulaDeviceSignals
import ad.simula.ad.sdk.network.SimulaUserAgent
import ad.simula.ad.sdk.privacy.SimulaPrivacy
import ad.simula.ad.sdk.privacy.SimulaPrivacyConfig
import ad.simula.ad.sdk.privacy.ProcessPrivacyOwner
import ad.simula.ad.sdk.telemetry.ProcessSdkEntryOrigin
import ad.simula.ad.sdk.telemetry.ProcessStartupInfrastructure
import ad.simula.ad.sdk.telemetry.ProcessTelemetryIdentityRouter
import ad.simula.ad.sdk.telemetry.EffectiveTelemetryConfig
import ad.simula.ad.sdk.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        updateConsent = {},
        ensureSession = { null },
        getCachedAd = { _, _ -> null },
        cacheAd = { _, _, _ -> },
        getCachedHeight = { _, _ -> null },
        cacheHeight = { _, _, _ -> },
        hasNoFill = { _, _ -> false },
        markNoFill = { _, _ -> },
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
 * @param telemetryEnabled Opt out of in-house SDK telemetry. Default true.
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

    val resolvedConfig = remember(privacy, hasPrivacyConsent) {
        privacy ?: SimulaPrivacyConfig(hasPrivacyConsent = hasPrivacyConsent)
    }
    val privacyOwnerToken = remember { ProcessPrivacyOwner.createToken() }
    DisposableEffect(privacyOwnerToken) {
        onDispose { ProcessPrivacyOwner.release(privacyOwnerToken) }
    }
    val explicitPrivacy = privacy != null
    val currentPrivacy by rememberUpdatedState(resolvedConfig)
    val currentExplicitPrivacy by rememberUpdatedState(explicitPrivacy)
    val entryClaim = remember(apiKey, privacyOwnerToken) {
        PostCommitApiKeyClaim {
            ProcessApiKeyOwner.claimAndSeedPrivacy(
                apiKey = apiKey,
                privacyOwnerToken = privacyOwnerToken,
                privacy = currentPrivacy,
                explicitPrivacy = currentExplicitPrivacy,
            )
        }
    }
    var apiKeyOwnership by remember(entryClaim) { mutableStateOf(entryClaim.result()) }
    var committedPrivacy by remember(entryClaim) {
        mutableStateOf<Pair<SimulaPrivacyConfig, Boolean>?>(null)
    }
    if (apiKeyOwnership == null) {
        // A speculative/abandoned composition never executes this claim. Children are withheld so
        // no SDK effect or request can run before key ownership and privacy seeding have committed.
        SideEffect {
            val ownership = entryClaim.commit()
            if (ownership.isCompatible) {
                committedPrivacy = currentPrivacy to currentExplicitPrivacy
            }
            apiKeyOwnership = ownership
        }
        return
    }
    if (apiKeyOwnership == ApiKeyOwnership.Incompatible) {
        SideEffect { ProcessApiKeyOwner.warnIncompatibleEntry() }
        CompositionLocalProvider(LocalSimulaContext provides emptySimulaContext(), content = content)
        return
    }

    val requestedPrivacy = resolvedConfig to explicitPrivacy
    if (committedPrivacy != requestedPrivacy) {
        // Keep the disposal registration mounted while a committed prop update refreshes this entry.
        SideEffect {
            ProcessPrivacyOwner.seed(privacyOwnerToken, resolvedConfig, explicitPrivacy)
            committedPrivacy = requestedPrivacy
        }
        return
    }
    ProcessSdkEntryOrigin.markEntry()

    // Seed the process-wide native-ad context so NativeAdSlot requests carry it. A full replace on
    // change (mirrors the privacy seed below); preloaded ads keep the value current at preload time.
    remember(adContext) { NativeAdContextStore.set(adContext); adContext }

    val context = LocalContext.current

    // Build only the cheap Build/package-name User-Agent during composition. Every Binder/disk
    // signal primes itself on SimulaScope, matching the imperative path without blocking Compose.
    remember(context) {
        SimulaUserAgent.build(context.applicationContext)
        SimulaDeviceId.prime(context.applicationContext)
        SimulaConnectionType.prime(context.applicationContext)
        SimulaDeviceSignals.prime(context.applicationContext)
    }

    // Attach CMP state, install telemetry, and settle the initial GAID read off composition. The
    // coordinator is the declarative startup gate: no provider-local session resolver can pass it.
    val privacySessionCoordinator = remember(context) { PrivacySessionCoordinator() }
    LaunchedEffect(privacySessionCoordinator) {
        var effectiveTelemetry: EffectiveTelemetryConfig? = null
        privacySessionCoordinator.preparePrivacy(
            attach = { withContext(Dispatchers.IO) { SimulaPrivacy.attach(context) } },
            installTelemetry = {
                effectiveTelemetry = Telemetry.initialize(
                    context = context.applicationContext,
                    apiKey = apiKey,
                    devMode = devMode,
                    enabled = telemetryEnabled,
                )
            },
            refreshAdvertisingId = { SimulaPrivacy.refreshAdvertisingId() },
            prepareInfrastructure = {
                effectiveTelemetry?.let { telemetry ->
                    ProcessStartupInfrastructure.initialize(
                        context.applicationContext,
                        telemetry,
                        ProcessLaunchSettledGate,
                    )
                }
            },
        )
    }
    LaunchedEffect(privacySessionCoordinator, resolvedConfig) {
        privacySessionCoordinator.awaitPrivacyReady()
        SimulaPrivacy.refreshAdvertisingId()
    }
    DisposableEffect(privacySessionCoordinator) {
        onDispose { privacySessionCoordinator.completeFailOpen() }
    }

    // Re-read the GAID on foreground: ad-tracking permission or the GAID itself
    // can change while the app is backgrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            WebViewPool.markApplicationActive(context)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                WebViewPool.markApplicationActive(context)
                scope.launch { SimulaPrivacy.refreshAdvertisingId() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // CMPs write the IAB keys in a burst; debounce the snapshot that drives session re-sync.
    val sessionConsent by remember { SimulaPrivacy.snapshot.debounce(300L) }
        .collectAsState(initial = SimulaPrivacy.current)

    // Provider-local ownership is deliberate: its key/dev mode/user must never resolve through an
    // unrelated imperative store. Consent changes recreate only this provider's session holder.
    val sessionStore = remember(apiKey, devMode, primaryUserID, sessionConsent) {
        SimulaSessionStore(apiKey, devMode, primaryUserID).apply {
            // Mixed hosts still wait for an imperative startup published before the request. This is
            // resolved live because a provider can compose before SimulaAds.initialize is called.
            startupGate = { SimulaAds.startupGate }
        }
    }
    val telemetryIdentityToken = remember { ProcessTelemetryIdentityRouter.createProviderToken() }
    SideEffect {
        // Rebind the stable token after commit without first removing it. The router updates this
        // mount in place, so an outer recomposition cannot steal precedence from a mounted inner.
        ProcessTelemetryIdentityRouter.bindProvider(
            token = telemetryIdentityToken,
            apiKey = apiKey,
            sessionId = { sessionStore.sessionId },
            primaryUserId = { sessionStore.effectiveUserID },
        )
    }
    DisposableEffect(telemetryIdentityToken) {
        // Only actual provider disposal removes the token and restores the prior active provider.
        onDispose { ProcessTelemetryIdentityRouter.unbindProvider(telemetryIdentityToken) }
    }

    // Delegate cache + context construction to the shared builder. The imperative
    // interstitial Activity uses the same path with a session warmed by SimulaAds,
    // so the two entry points stay in lock-step.
    ProvideSimulaContext(
        store = sessionStore,
        apiKey = apiKey,
        devMode = devMode,
        privacySessionCoordinator = privacySessionCoordinator,
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
    privacySessionCoordinator: PrivacySessionCoordinator? = null,
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

    val sessionResolver: suspend () -> String? = remember(store, privacySessionCoordinator) {
        {
            if (privacySessionCoordinator != null) {
                privacySessionCoordinator.ensureSession { store.ensureSession() }
            } else {
                store.ensureSession()
            }
        }
    }

    // Provider children wait for CMP + telemetry + GAID through the coordinator. Imperative
    // Activities retain their existing attach/refresh behavior and shared startup gate.
    val context = LocalContext.current
    LaunchedEffect(store, privacySessionCoordinator) {
        if (privacySessionCoordinator == null) {
            withContext(Dispatchers.IO) { SimulaPrivacy.attach(context) }
            SimulaPrivacy.refreshAdvertisingId()
        }
        sessionResolver()
    }

    // Build context value — equivalent to React's useMemo
    val contextValue = remember(apiKey, devMode, store.sessionId, consent, sessionResolver) {
        SimulaContextValue(
            apiKey = apiKey,
            devMode = devMode,
            sessionId = store.sessionId,
            hasPrivacyConsent = consent.hasPrivacyConsent,
            consent = consent,
            updateConsent = { SimulaPrivacy.apply(it) },
            ensureSession = sessionResolver,
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
