package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.ProcessLaunchSettledGate
import ad.simula.ad.sdk.core.ProcessApiKeyOwner
import ad.simula.ad.sdk.core.ImperativeInitializationAttempt
import ad.simula.ad.sdk.core.ImperativeInitializationGate
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.model.SimulaAdContext
import ad.simula.ad.sdk.nativead.NativeAdCache
import ad.simula.ad.sdk.nativead.NativeAdContextStore
import ad.simula.ad.sdk.nativead.NativeAdPreloadCache
import ad.simula.ad.sdk.network.Ipv4Beacon
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.network.SimulaConnectionType
import ad.simula.ad.sdk.network.SimulaDeviceId
import ad.simula.ad.sdk.network.SimulaDeviceSignals
import ad.simula.ad.sdk.network.SimulaUserAgent
import ad.simula.ad.sdk.privacy.SimulaPrivacy
import ad.simula.ad.sdk.privacy.SimulaPrivacyConfig
import ad.simula.ad.sdk.privacy.ProcessPrivacyOwner
import ad.simula.ad.sdk.provider.SimulaSessionStore
import ad.simula.ad.sdk.provider.awaitInitialAdvertisingIdRefresh
import ad.simula.ad.sdk.telemetry.EffectiveTelemetryConfig
import ad.simula.ad.sdk.telemetry.FirstWinsProcessTaskClaim
import ad.simula.ad.sdk.telemetry.ProcessStartupInfrastructure
import ad.simula.ad.sdk.telemetry.Telemetry
import ad.simula.ad.sdk.telemetry.ProcessSdkEntryOrigin
import ad.simula.ad.sdk.telemetry.ProcessTelemetryIdentityRouter
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

private data class ImperativeStartup(
    val telemetryClaim: FirstWinsProcessTaskClaim<EffectiveTelemetryConfig>,
    val gate: CompletableDeferred<Unit>,
    val startNanos: Long,
)

/**
 * Global entry point for the imperative ad API (mirrors the Swift `SimulaAds`).
 *
 * Call [initialize] once at app startup. It stores configuration, warms a shared
 * server session off the critical path, and starts tracking the current Activity
 * so an interstitial can be presented from anywhere.
 *
 * All methods are intended to be called on the main thread. [initialize] itself is
 * deliberately cheap on the calling thread: the steps that do disk I/O
     * (SharedPreferences/SQLite), durable-store construction, and the session warm-up
 * run in a deferred [SimulaScope] startup, so calling from
 * `Application.onCreate` adds no meaningful main-thread cost.
 */
object SimulaAds {

    private val initialization = ImperativeInitializationGate()
    private val privacyOwnerToken = ProcessPrivacyOwner.createToken()

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
     * the startup's own session warm-up. Null before [initialize] (including declarative-only
     * hosts). A declarative provider reads it live from its local store's gate provider so mixed
     * hosts cannot race ahead of an imperative startup published before the request. `@Volatile`
     * because reads happen on [SimulaScope] while the write happens on the initialize thread.
     */
    @Volatile
    internal var startupGate: CompletableDeferred<Unit>? = null
        private set

    // Character context is no longer global: pass charId/charName/charImage/charDesc
    // to each `SimulaInterstitialAd.load()` / `SimulaRewardedAd.load()` call instead.

    private var currentActivityRef: WeakReference<Activity>? = null
    internal val currentActivity: Activity? get() = currentActivityRef?.get()

    /** True once [initialize] has been called with a valid key. */
    val isInitialized: Boolean get() = initialization.isInitialized

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
        initializeShared(
            context = context,
            apiKey = apiKey,
            devMode = devMode,
            primaryUserID = primaryUserID,
            hasPrivacyConsent = hasPrivacyConsent,
            privacy = privacy,
            telemetryEnabled = telemetryEnabled,
            adContext = adContext,
        )
    }

    private fun initializeShared(
        context: Context,
        apiKey: String,
        devMode: Boolean,
        primaryUserID: String?,
        hasPrivacyConsent: Boolean,
        privacy: SimulaPrivacyConfig?,
        telemetryEnabled: Boolean,
        adContext: SimulaAdContext?,
    ) {
        require(apiKey.isNotBlank()) { "SimulaAds.initialize requires a non-blank apiKey" }
        val applicationContext = context.applicationContext
        val resolvedPrivacy = privacy ?: SimulaPrivacyConfig(hasPrivacyConsent = hasPrivacyConsent)
        val launchSettledGate = ProcessLaunchSettledGate
        var reservedTelemetry: ad.simula.ad.sdk.telemetry.FirstWinsProcessTaskClaim<EffectiveTelemetryConfig>? = null
        val attempt = initialization.initialize(
            claimAndSeed = {
                ProcessApiKeyOwner.claimAndSeedPrivacyThen(
                    apiKey = apiKey,
                    privacyOwnerToken = privacyOwnerToken,
                    privacy = resolvedPrivacy,
                    explicitPrivacy = privacy != null,
                ) {
                    Telemetry.claimInitialization(
                        context = applicationContext,
                        apiKey = apiKey,
                        devMode = devMode,
                        enabled = telemetryEnabled,
                        launchSettledGate = launchSettledGate,
                    )
                }.also { reservedTelemetry = it.value }.ownership
            },
            onWinner = {
                ProcessSdkEntryOrigin.markEntry()
                val startNanos = System.nanoTime()
                // Gate released once consent, telemetry, and billing infrastructure are ready.
                val gate = CompletableDeferred<Unit>()

                appContext = applicationContext
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
                store = SimulaSessionStore(apiKey, devMode, primaryUserID)
                // Publish the store identity in the same critical section as imperative initialization.
                // Telemetry configuration remains first-wins. Envelopes prefer this live imperative
                // identity only when its API key is compatible with the winning telemetry sender.
                ProcessTelemetryIdentityRouter.bindImperative(
                    apiKey = apiKey,
                    sessionId = { store.sessionId },
                    primaryUserId = { store.effectiveUserID },
                )
                store.startupGate = { gate }
                this.startupGate = gate

                registerActivityTracking()
                seedWebViewRetentionState(context)

                // Reserve immutable telemetry configuration before publishing initialized=true. This
                // only creates a lazy SimulaScope task: no I/O starts and the main thread never waits.
                val claim = reservedTelemetry ?: Telemetry.claimInitialization(
                    context = appContext,
                    apiKey = apiKey,
                    devMode = devMode,
                    enabled = telemetryEnabled,
                    launchSettledGate = launchSettledGate,
                )

                ImperativeStartup(claim, gate, startNanos)
            },
        )
        val startup = when (attempt) {
            ImperativeInitializationAttempt.Duplicate -> {
                Telemetry.recordDuplicateInitialize()
                return
            }
            ImperativeInitializationAttempt.Incompatible -> {
                ProcessApiKeyOwner.warnIncompatibleEntry()
                return
            }
            is ImperativeInitializationAttempt.Winner -> attempt.value
        }
        val gate = startup.gate
        val telemetryClaim = startup.telemetryClaim
        val startNanos = startup.startNanos

        // Deferred startup, OFF the calling thread (typically the main thread when the host
        // initializes from Application.onCreate): the remaining steps do disk I/O
        // (SharedPreferences first access, SQLite open/migration) or heavy one-time work
        // that used to run inline on the caller. Ordering is
        // preserved — IAB attach → telemetry install → initial GAID read → beacon-queue
        // build → crash-handler install → recovery triggers → session warm-up. Outbound recovery,
        // crash replay, telemetry, and IPv4 sends independently wait on the launch-settled gate, so
        // the first /session/create is
        // built with attached consent + collected GAID and captured by telemetry, exactly
        // as before. Each step fails open independently: telemetry/consent infrastructure
        // must never break ads.
        SimulaScope.launch {
            var effectiveTelemetry: EffectiveTelemetryConfig? = null
            try {
                // Wire IAB-standard CMP auto-read (default-SharedPreferences first access = disk).
                runCatching { SimulaPrivacy.attach(appContext) }

                // Install telemetry before the GAID read and the session warm-up: the read can
                // then report a failure (privacy:gaid_read_failed), and /session/create (and
                // every subsequent SDK request) is captured. Envelope identity is read live from
                // the process router so mixed-host attribution follows the imperative store.
                effectiveTelemetry = runCatching { telemetryClaim.startAndAwait() }.getOrNull()

                // Initial GAID read (coalesced + throttled internally; no-op when the host
                // didn't opt in via enableAdvertisingId, when the user limits ad tracking, or
                // when the play-services-ads-identifier dep is absent). Returns with the GAID
                // state settled, so the session warm-up below (and any gated host load) carries
                // the id on the first /session/create.
                awaitInitialAdvertisingIdRefresh(
                    refreshAdvertisingId = { SimulaPrivacy.refreshAdvertisingId() },
                )

                // Build process infrastructure from telemetry's effective first-wins config. This
                // guarantees the durable beacon sender and crash capture cannot use a losing local
                // key/enabled value. Recovery sends remain launch-settled-gated internally.
                effectiveTelemetry?.let { telemetry ->
                    runCatching {
                        ProcessStartupInfrastructure.initialize(appContext, telemetry, launchSettledGate)
                    }
                }
            } finally {
                // Release session waiters (see SimulaSessionStore.startupGate) no matter what —
                // a dead/cancelled startup coroutine must never leave ensureSession callers
                // blocked forever. Consent, telemetry, and the beacon queue are now in place
                // (or failed open — never worth breaking ads over). MUST precede the warm-up
                // below, which awaits this same gate.
                gate.complete(Unit)
            }

            // SDK-init + SDK-upgrade beacons before session warm-up, so sdk_init excludes the
            // /session/create network round-trip (parity with iOS).
            val initMs = (System.nanoTime() - startNanos) / 1_000_000
            val configSummary = runCatching {
                val c = SimulaPrivacy.current
                val telemetry = effectiveTelemetry
                "dev=${telemetry?.devMode ?: devMode} tel=${telemetry?.enabled ?: false} consent=${c.hasPrivacyConsent} " +
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
        if (!initialization.isInitialized) return
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
        if (!initialization.isInitialized || adUnitId.isBlank()) return false
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
        if (!initialization.isInitialized) return null
        val resolvedTheme = resolveThemeImperative(theme)
        return NativeAdPreloadCache.preload(
            adUnitId = adUnitId,
            position = position,
            theme = resolvedTheme,
        )
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
                WebViewPool.markApplicationActive(activity)
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

    /**
     * Activity callbacks do not replay an already-delivered resume when hosts initialize lazily
     * (notably React Native bridges). Seed only a truly foreground process; later resume and
     * UI-hidden callbacks remain authoritative and this never changes the pressure cooldown.
     */
    private fun seedWebViewRetentionState(context: Context) {
        val markIfForeground = {
            val info = ActivityManager.RunningAppProcessInfo()
            val foreground = runCatching {
                ActivityManager.getMyMemoryState(info)
                isForegroundProcessImportance(info.importance)
            }.getOrDefault(false)
            if (foreground) WebViewPool.markApplicationActive(context)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            markIfForeground()
        } else {
            Handler(Looper.getMainLooper()).post(markIfForeground)
        }
    }
}

/** Exact foreground importance excludes visible/background services that have no resumed UI. */
internal fun isForegroundProcessImportance(importance: Int): Boolean =
    importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
