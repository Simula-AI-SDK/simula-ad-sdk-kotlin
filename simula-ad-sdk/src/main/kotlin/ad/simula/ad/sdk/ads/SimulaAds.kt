package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.model.SimulaAdContext
import ad.simula.ad.sdk.nativead.NativeAdCache
import ad.simula.ad.sdk.nativead.NativeAdContextStore
import ad.simula.ad.sdk.nativead.NativeAdPreloadCache
import ad.simula.ad.sdk.network.AdBeaconManager
import ad.simula.ad.sdk.network.Ipv4Beacon
import ad.simula.ad.sdk.network.RewardVerificationManager
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.network.SimulaConnectionType
import ad.simula.ad.sdk.network.SimulaDeviceId
import ad.simula.ad.sdk.network.SimulaDeviceSignals
import ad.simula.ad.sdk.network.SimulaUserAgent
import ad.simula.ad.sdk.privacy.SimulaPrivacy
import ad.simula.ad.sdk.privacy.SimulaPrivacyConfig
import ad.simula.ad.sdk.provider.SimulaSessionStore
import ad.simula.ad.sdk.telemetry.SimulaCrashGuard
import ad.simula.ad.sdk.telemetry.Telemetry
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Global entry point for the imperative ad API (mirrors the Swift `SimulaAds`).
 *
 * Call [initialize] once at app startup. It stores configuration, warms a shared
 * server session off the critical path, and starts tracking the current Activity
 * so an interstitial can be presented from anywhere.
 *
 * All methods are intended to be called on the main thread. [initialize] itself is
 * deliberately cheap on the calling thread: the steps that do disk I/O
 * (SharedPreferences/SQLite), telemetry-store construction, WebView prewarm, and the
 * session warm-up run in a deferred [SimulaScope] startup, so calling from
 * `Application.onCreate` adds no meaningful main-thread cost.
 */
object SimulaAds {

    @Volatile
    private var initialized = false

    internal lateinit var appContext: Context
        private set
    internal var apiKey: String = ""
        private set
    internal var devMode: Boolean = false
        private set
    internal lateinit var store: SimulaSessionStore
        private set

    /**
     * The deferred-startup gate of the current [initialize] run — completes once consent
     * (IAB attach), telemetry, and the durable beacon queue are in place, and always before
     * the startup's own session warm-up. Null before [initialize] (e.g. declarative-only
     * hosts). The declarative `SimulaProvider` reads it LIVE from its own [SimulaSessionStore]
     * gate provider (a provider can be composed — and its store remembered — before this is
     * published), so a session created from composition can't race ahead of the imperative
     * startup either. `@Volatile` because that live read happens on [SimulaScope] coroutines
     * while the write happens on [initialize]'s calling thread.
     */
    @Volatile
    internal var startupGate: CompletableDeferred<Unit>? = null
        private set

    // Character context is no longer global: pass charId/charName/charImage/charDesc
    // to each `SimulaInterstitialAd.load()` / `SimulaRewardedAd.load()` call instead.

    private var currentActivityRef: WeakReference<Activity>? = null
    internal val currentActivity: Activity? get() = currentActivityRef?.get()

    /** True once [initialize] has been called with a valid key. */
    val isInitialized: Boolean get() = initialized

    /**
     * The custom User-Agent the SDK sets on its native HTTP requests (PRD). Null until the SDK is
     * initialized. Exposed so a React Native bridge can retrieve the native string rather than
     * reconstructing it in JS.
     */
    val userAgent: String? get() = SimulaUserAgent.value

    /**
     * The device identifier the SDK sends as the `X-Device-Id` header on its native HTTP requests
     * (`Settings.Secure.ANDROID_ID`). Null until the SDK is initialized (or if the platform supplies
     * none). Exposed so a React Native bridge can retrieve the native value.
     */
    val deviceId: String? get() = SimulaDeviceId.value

    /**
     * Initialize the SDK. Idempotent — the first valid call wins; later calls are
     * ignored.
     *
     * @param context any Context (its application context is retained).
     * @param apiKey  your Simula API key (must be non-blank).
     * @param hasPrivacyConsent Legacy coarse consent flag. When false, suppresses PII. Default true.
     * @param privacy Granular privacy / consent configuration (GDPR/TCF/CCPA/GPP/COPPA + IDFA
     *                opt-in). When provided it takes precedence over [hasPrivacyConsent]; when null
     *                the SDK seeds a config from [hasPrivacyConsent] and still auto-reads IAB CMP
     *                keys. Mirrors `SimulaProvider`'s `privacy` parameter.
     * @param telemetryEnabled Opt out of in-house SDK telemetry (handled-error + performance
     *                metrics sent to Simula). Default true. PII in telemetry is consent-gated
     *                exactly like ad tracking; set false to disable the pipeline entirely.
     */
    fun initialize(
        context: Context,
        apiKey: String,
        devMode: Boolean = false,
        primaryUserID: String? = null,
        hasPrivacyConsent: Boolean = true,
        privacy: SimulaPrivacyConfig? = null,
        telemetryEnabled: Boolean = true,
        adContext: SimulaAdContext? = null,
    ) {
        if (initialized) return
        require(apiKey.isNotBlank()) { "SimulaAds.initialize requires a non-blank apiKey" }

        // Monotonic start marker for the sdk_init telemetry duration (emitted once setup completes).
        val startNanos = System.nanoTime()

        // Double-checked under a lock so two concurrent initialize() calls can't both run the body
        // and double-init (e.g. two session warm-ups, two activity-tracking registrations). The
        // require() above stays outside the lock so a blank key still fails fast on every call.
        //
        // Gate released once the deferred startup has attached consent + installed telemetry
        // (see SimulaSessionStore.startupGate) — a host ad-load fired immediately after this
        // call returns still can't race a request out ahead of them.
        val gate = CompletableDeferred<Unit>()
        synchronized(this) {
            if (initialized) return

            appContext = context.applicationContext
            this.apiKey = apiKey
            this.devMode = devMode

            // Seed the process-wide native-ad targeting context so every POST /load/native carries it.
            NativeAdContextStore.set(adContext)

            // Build the custom User-Agent once (cheap, Build statics); SimulaHttp stamps it on every
            // request. The device id is a synchronous ContentProvider read, so it's resolved off the
            // main thread via prime() to keep it off the app-start critical path.
            SimulaUserAgent.build(appContext)
            SimulaDeviceId.prime(appContext)
            // Independent of telemetryEnabled: the X-Connection-Type header is a first-party-request
            // signal, not a telemetry one, so it must work even when telemetry is disabled.
            SimulaConnectionType.prime(appContext)
            // Device-context signals (timezone, storage, memory, battery, volume) attached to every API
            // request. Also a first-party-request signal, primed off the critical path.
            SimulaDeviceSignals.prime(appContext)

            // An explicit privacy config wins; otherwise the legacy hasPrivacyConsent flag
            // seeds it — identical resolution to SimulaProvider, so the imperative and
            // declarative entry points present the same consent signals.
            val resolved = privacy ?: SimulaPrivacyConfig(hasPrivacyConsent = hasPrivacyConsent)

            // Seed the process-wide privacy store so the imperative path honors consent:
            // SimulaApiClient reads SimulaPrivacy.current for the /session/create body and
            // per-request consent headers. Kept synchronous (in-memory) so consent ordering
            // is guaranteed; the IAB auto-read (disk) is deferred to the startup below.
            SimulaPrivacy.apply(resolved)

            store = SimulaSessionStore(apiKey, devMode, primaryUserID)
            store.startupGate = { gate }
            this.startupGate = gate

            registerActivityTracking()

            // Publish last: a concurrent initialize() that observed the volatile flag as true
            // is guaranteed to see all of the above writes (lateinit store/appContext etc.).
            initialized = true
        }

        // Deferred startup, OFF the calling thread (typically the main thread when the host
        // initializes from Application.onCreate): the remaining steps do disk I/O
        // (SharedPreferences first access, SQLite open/migration) or heavy one-time work
        // (Chromium provider bring-up) that used to run inline on the caller. Ordering is
        // preserved — IAB attach → telemetry install → initial GAID read → beacon-queue
        // build → crash-guard install (its replay records into telemetry) → recovery/beacon
        // drains → WebView prewarm → session warm-up — so the first /session/create is
        // built with attached consent + collected GAID and captured by telemetry, exactly
        // as before. Each step fails open independently: telemetry/consent infrastructure
        // must never break ads.
        SimulaScope.launch {
            try {
                // Wire IAB-standard CMP auto-read (default-SharedPreferences first access = disk).
                runCatching { SimulaPrivacy.attach(appContext) }

                // Install telemetry before the GAID read and the session warm-up: the read can
                // then report a failure (privacy:gaid_read_failed), and /session/create (and
                // every subsequent SDK request) is captured. The PPID is read live from the
                // session store so a mid-session updatePrimaryUserID is honored.
                runCatching {
                    Telemetry.initialize(
                        context = appContext,
                        apiKey = apiKey,
                        devMode = devMode,
                        enabled = telemetryEnabled,
                        sessionIdProvider = { store.sessionId },
                        primaryUserIdProvider = { store.effectiveUserID },
                    )
                }

                // Initial GAID read (coalesced + throttled internally; no-op when the host
                // didn't opt in via enableAdvertisingId, when the user limits ad tracking, or
                // when the play-services-ads-identifier dep is absent). Returns with the GAID
                // state settled, so the session warm-up below (and any gated host load) carries
                // the id on the first /session/create.
                runCatching { SimulaPrivacy.refreshAdvertisingId() }

                // Build the durable impression/click beacon queue BEFORE the gate releases:
                // a host load awaiting the gate can show an ad (and fire billing beacons) the
                // moment it proceeds, and AdBeaconManager.enqueue is a silent no-op until the
                // engine exists — building it here closes that drop window. Construction is
                // allocation-only (the SharedPreferences read happens lazily inside the queue),
                // so this adds no disk I/O to the gated section. The prior-process DRAIN stays
                // after the gate: those beacons are already persisted and self-contained.
                runCatching { AdBeaconManager.init(appContext, apiKey) }
            } finally {
                // Release session waiters (see SimulaSessionStore.startupGate) no matter what —
                // a dead/cancelled startup coroutine must never leave ensureSession callers
                // blocked forever. Consent, telemetry, and the beacon queue are now in place
                // (or failed open — never worth breaking ads over). MUST precede the warm-up
                // below, which awaits this same gate.
                gate.complete(Unit)
            }

            // Capture uncaught SDK crashes (+ ANR / native-renderer process exits on Android 11+)
            // into telemetry. AFTER Telemetry.initialize: install replays any prior-process crash
            // records into the pipeline, and a record landing before the manager exists would be
            // permanently dropped (the ordering SimulaCrashGuard.install documents).
            withContext(Dispatchers.Main) {
                runCatching { SimulaCrashGuard.install(appContext, enabled = telemetryEnabled) }
            }

            // Independently of session warm-up (each queued verification carries its own
            // session), drain any reward verifications a prior process left pending (e.g. a
            // crash/kill before a verify could land) so their server-side SSV postbacks fire
            // without waiting for the next rewarded play. After the IAB attach so recovery
            // requests carry current consent headers.
            runCatching { RewardVerificationManager.triggerProcessQueue(appContext) }

            // Drain any impression/click beacons a prior process left undelivered
            // (offline/killed). The queue was built inside the gated section above — any
            // gated load can fire billing beacons the moment it releases — while these
            // already-persisted entries can drain off the gated path.
            runCatching { AdBeaconManager.triggerProcessQueue() }

            // SDK-init + SDK-upgrade beacons BEFORE the WebView prewarm and the session warm-up,
            // so sdk_init measures startup work only — not the Web Content process bring-up or
            // the /session/create network round-trip (parity with iOS, which records both beacons
            // ahead of session warm-up).
            val initMs = (System.nanoTime() - startNanos) / 1_000_000
            val configSummary = runCatching {
                val c = SimulaPrivacy.current
                "dev=$devMode tel=$telemetryEnabled consent=${c.hasPrivacyConsent} " +
                    "coppa=${c.coppaApplies} adid=${c.advertisingId != null} ctx=${adContext != null}"
            }.getOrNull()
            Telemetry.recordOperation(
                name = "sdk_init",
                durationMs = initMs,
                success = true,
                breadcrumb = configSummary,
            )
            runCatching {
                val vPrefs = appContext.getSharedPreferences("simula_ad_sdk_version_prefs", Context.MODE_PRIVATE)
                val last = vPrefs.getString("last_seen_sdk_version", null)
                val current = ad.simula.ad.sdk.telemetry.SIMULA_SDK_VERSION
                if (last != null && last != current) {
                    Telemetry.recordOperation(name = "sdk_upgrade", durationMs = 0, success = true, breadcrumb = "from=$last;to=$current")
                }
                if (last != current) vPrefs.edit().putString("last_seen_sdk_version", current).apply()
            }

            // Prewarm a WebView so the first ad/menu/native slot never pays the Chromium provider
            // bring-up cold inside a feed layout or ad show. WebView creation must stay on the
            // main thread; queued from here it runs after the caller's launch-critical work.
            withContext(Dispatchers.Main) {
                runCatching { WebViewPool.prewarm(appContext) }
            }

            // Warm the session before the first load() so it's off the ad critical path.
            store.ensureSession()
        }
    }

    // ── Native ad targeting context + preloading ──────────────────────────────

    /**
     * Replace the native-ad targeting [SimulaAdContext] at runtime (e.g. when the feed category
     * changes). This is a full replacement, not a merge (PRD). All subsequent `POST /load/native`
     * calls use the new context; ads already preloaded under the old context are unaffected.
     */
    fun updateContext(adContext: SimulaAdContext?) {
        NativeAdContextStore.set(adContext)
    }

    /**
     * Update the primary user id (PPID) mid-session — e.g. after a login, a logout, or a first
     * login that happened only after the session was already created. Mirrors [updateContext]:
     * safe to call any time after [initialize] (a no-op before it). A null/blank id clears the
     * PPID (logout).
     *
     * Effects: (1) the value the next `session/create` carries is updated; (2) telemetry reports
     * the new value; (3) when a session already exists, the live session is PATCHed server-side off
     * the caller's thread. Clearing (null) updates local + telemetry state only — the backend's
     * `PATCH …/ppid/{ppid}` path can't express an empty id. The network call is best-effort; a
     * failure is non-fatal because local state already reflects the new id.
     */
    fun updatePrimaryUserID(id: String?) {
        if (!initialized) return
        val normalized = id?.takeIf { it.isNotBlank() }
        store.updatePpid(normalized)
        // Reconcile the live server session toward the new id. Single-flight and serialized in the
        // store, so rapid switches can't leave the tracked session identity disagreeing with the
        // server. No-op when there's no session yet (the next createSession carries the value) or on
        // logout (which can't be pushed server-side; the session is then treated as stale).
        store.reconcileServerPpid()
        // IPv4 capture on a login/switch fires from INSIDE reconcileServerPpid, once the PATCH
        // has landed — so the sid it carries belongs to the store that was actually reconciled
        // and genuinely represents the new ppid server-side (the backend keys the capture by
        // sid first; beaconing here with store.sessionId could attach it to a session that
        // still represents the previous user, or one that provider-hosted ads never use). A
        // login with no session yet is covered by ensureSession's init beacon, which carries
        // the ppid current at creation. Only the logout reset lives here.
        if (normalized == null) Ipv4Beacon.onLogout()
    }

    /**
     * Checks whether the user has hit their frequency cap for [adUnitId] — a read-only check
     * against the backend that records no impression (PRD). Publishers can call this before
     * rendering an ad-gated surface to skip it entirely when no ad would serve.
     *
     * @param adUnitId required.
     * @param primaryUserID optional; falls back to the SDK's current PPID (set at [initialize] or
     *                       via [updatePrimaryUserID]) when omitted, and ultimately to the
     *                       backend's IP/device/session signals when neither is available.
     * @return `true` if the cap has been reached (skip the surface); `false` if the user is still
     *         eligible, before [initialize], or on any network/server failure (fails open so a
     *         transport hiccup can never hide an ad surface that would otherwise have served).
     *
     * A `true` result is cached for the rest of the local day (reset at local midnight, per the
     * PRD) so repeated checks for the same ad unit + user don't re-hit the network.
     */
    suspend fun checkFrequencyCap(adUnitId: String, primaryUserID: String? = null): Boolean {
        if (!initialized || adUnitId.isBlank()) return false
        val ppid = primaryUserID?.takeIf { it.isNotBlank() } ?: store.effectiveUserID
        // Capture the local day at the START of the check and attribute the result to it. The network
        // round-trip can cross local midnight; stamping the cache with the completion time would file
        // a prior-day capped result under the new day and keep hiding surfaces after the backend's
        // daily reset. The start time is the day the result actually reflects (a capped==true near
        // midnight came from the backend evaluating before the reset).
        val nowMillis = System.currentTimeMillis()
        if (FrequencyCapCache.isCapped(adUnitId, ppid, nowMillis)) return true

        // Warm/ensure the session, but only attach its id when it represents the same identity we're
        // checking. After a mid-session login/logout/switch the server session can still reflect the
        // prior user (the PATCH is async, and logout can't be pushed at all); sending that stale id
        // could make the backend evaluate the cap for the wrong user. When it diverges we drop the id
        // and let the backend fall back to the ppid + device-id/IP signals.
        val sessionId = consistentSessionId(store.ensureSession(), store.sessionUserID, ppid)
        val capped = SimulaApiClient.checkFrequencyCap(apiKey, adUnitId, ppid, sessionId)
        if (capped) FrequencyCapCache.markCapped(adUnitId, ppid, nowMillis)
        return capped
    }

    /**
     * Returns [sessionId] only when the session's identity ([sessionUserID]) matches the [ppid]
     * being checked; otherwise null (drop the stale session). Pure/testable. Both-null (anonymous)
     * counts as a match.
     */
    internal fun consistentSessionId(sessionId: String?, sessionUserID: String?, ppid: String?): String? =
        sessionId?.takeIf { sessionUserID == ppid }

    /**
     * Callback overload of [checkFrequencyCap] for Java / React Native interop, where a
     * `suspend` function isn't directly callable. Runs on [SimulaScope] and delivers [onResult]
     * on the main thread.
     */
    fun checkFrequencyCap(adUnitId: String, primaryUserID: String? = null, onResult: (Boolean) -> Unit) {
        SimulaScope.launch {
            val result = checkFrequencyCap(adUnitId, primaryUserID)
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /**
     * Imperatively preload one native ad before its slot scrolls into view. Fires a single
     * `POST /load/native` using the current provider context, caches the full response, and returns
     * a `preloadedAdId` to pass into a [ad.simula.ad.sdk.nativead.NativeAdSlot] — which then renders
     * from cache with no live network call. The entry is evicted once consumed; release any
     * unconsumed id with [destroyPreloadedAd]. At most 5 ads are kept (excess is dropped with an
     * internal warning). Returns null before [initialize].
     *
     * @param theme `"dark"`, `"light"`, or `"system"` (resolves from the device's current UI
     *              mode). Null omits the field (backend default).
     */
    fun preloadNativeAd(
        adUnitId: String? = null,
        position: Int = 0,
        theme: String? = null,
    ): String? {
        if (!initialized) return null
        val resolvedTheme = resolveThemeImperative(theme)
        return NativeAdPreloadCache.preload(adUnitId = adUnitId, position = position, theme = resolvedTheme)
    }

    /** Release a preloaded native ad that was never consumed, cancelling its request if in flight. */
    fun destroyPreloadedAd(preloadedAdId: String) {
        NativeAdPreloadCache.destroy(preloadedAdId)
    }

    /**
     * Drop the cached ad for a native slot so its next appearance fetches a fresh one. A
     * [ad.simula.ad.sdk.nativead.NativeAdSlot] caches its resolved ad per `(adUnitId, position)` so
     * scrolling it out and back reuses the same serve (no duplicate request or impression); call this
     * to force a refresh for that slot. Pass no args + [invalidateNativeAds] to clear them all.
     */
    fun invalidateNativeAd(adUnitId: String? = null, position: Int = 0) {
        NativeAdCache.invalidate(adUnitId, position)
    }

    /** Clear every cached native ad (all slots). */
    fun invalidateNativeAds() {
        NativeAdCache.invalidateAll()
    }

    private fun resolveThemeImperative(theme: String?): String? = when (theme?.lowercase()) {
        "dark" -> "dark"
        "light" -> "light"
        "system" -> {
            val uiMode = appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (uiMode == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        }
        else -> null
    }

    private fun registerActivityTracking() {
        val app = appContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivityRef = WeakReference(activity)
                // Re-read the GAID on foreground: ad-tracking permission or the GAID itself
                // can change while the app is backgrounded. Internally throttled (4h TTL), so
                // this is cheap on every resume. Mirrors the SimulaProvider ON_RESUME hook.
                SimulaScope.launch { runCatching { SimulaPrivacy.refreshAdvertisingId() } }
            }

            // Keep the reference while merely paused — a paused Activity is still a
            // valid context to launch from, which avoids a NEW_TASK fallback during
            // a normal A→B transition. Clear only once it's actually destroyed.
            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivityRef?.get() === activity) currentActivityRef = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}

            // Persist + deliver buffered telemetry as the app heads to the background — the
            // window where a process is most likely to be killed. Cheap + guarded (no-op when
            // the buffer is empty / telemetry is disabled).
            override fun onActivityStopped(activity: Activity) {
                Telemetry.flush()
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }
}
