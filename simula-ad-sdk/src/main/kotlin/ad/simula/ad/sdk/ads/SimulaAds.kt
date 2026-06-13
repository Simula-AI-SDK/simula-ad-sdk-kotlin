package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.model.SimulaAdContext
import ad.simula.ad.sdk.nativead.NativeAdCache
import ad.simula.ad.sdk.nativead.NativeAdContextStore
import ad.simula.ad.sdk.nativead.NativeAdPreloadCache
import ad.simula.ad.sdk.network.RewardVerificationManager
import ad.simula.ad.sdk.network.SimulaDeviceId
import ad.simula.ad.sdk.network.SimulaUserAgent
import ad.simula.ad.sdk.privacy.SimulaPrivacy
import ad.simula.ad.sdk.privacy.SimulaPrivacyConfig
import ad.simula.ad.sdk.provider.SimulaSessionStore
import ad.simula.ad.sdk.telemetry.Telemetry
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Global entry point for the imperative ad API (mirrors the Swift `SimulaAds`).
 *
 * Call [initialize] once at app startup. It stores configuration, warms a shared
 * server session off the critical path, and starts tracking the current Activity
 * so an interstitial can be presented from anywhere.
 *
 * All methods are intended to be called on the main thread.
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

        appContext = context.applicationContext
        this.apiKey = apiKey
        this.devMode = devMode

        // Seed the process-wide native-ad targeting context so every POST /load/native carries it.
        NativeAdContextStore.set(adContext)

        // Build the custom User-Agent + device id once; SimulaHttp stamps them on every request.
        SimulaUserAgent.build(appContext)
        SimulaDeviceId.build(appContext)

        // An explicit privacy config wins; otherwise the legacy hasPrivacyConsent flag
        // seeds it — identical resolution to SimulaProvider, so the imperative and
        // declarative entry points present the same consent signals.
        val resolved = privacy ?: SimulaPrivacyConfig(hasPrivacyConsent = hasPrivacyConsent)

        // Seed the process-wide privacy store so the imperative path honors consent:
        // SimulaApiClient reads SimulaPrivacy.current for the /session/create body and
        // per-request consent headers. attach() also wires IAB-standard CMP auto-read.
        SimulaPrivacy.apply(resolved)
        SimulaPrivacy.attach(appContext)

        // ppid is suppressed without consent and additionally under COPPA — reads the
        // resolved snapshot, matching SimulaProvider's `sessionConsent.allowsPrimaryUserID` gate.
        val effectiveUserID = if (SimulaPrivacy.current.allowsPrimaryUserID) primaryUserID else null
        store = SimulaSessionStore(apiKey, devMode, effectiveUserID)

        // Install telemetry before the session warm-up so the /session/create call (and every
        // subsequent SDK request) is captured. The facade re-gates PII on the live consent
        // snapshot, so pass the raw primaryUserID, not the init-time effectiveUserID.
        Telemetry.initialize(
            context = appContext,
            apiKey = apiKey,
            devMode = devMode,
            enabled = telemetryEnabled,
            sessionIdProvider = { store.sessionId },
            primaryUserId = primaryUserID,
        )

        registerActivityTracking()
        initialized = true

        // Warm the session before the first load() so it's off the ad critical path.
        SimulaScope.launch { store.ensureSession() }

        // Independently of session warm-up (each queued verification carries its own
        // session), drain any reward verifications a prior process left pending (e.g. a
        // crash/kill before a verify could land) so their server-side SSV postbacks fire
        // without waiting for the next rewarded play. triggerProcessQueue launches its
        // own coroutine, so a slow/failed session create can't delay or skip recovery.
        RewardVerificationManager.triggerProcessQueue(appContext)
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
     * Imperatively preload one native ad before its slot scrolls into view. Fires a single
     * `POST /load/native` using the current provider context, caches the full response, and returns
     * a `preloadedAdId` to pass into a [ad.simula.ad.sdk.nativead.NativeAdSlot] — which then renders
     * from cache with no live network call. The entry is evicted once consumed; release any
     * unconsumed id with [destroyPreloadedAd]. At most 5 ads are kept (excess is dropped with an
     * internal warning). Returns null before [initialize].
     */
    fun preloadNativeAd(adUnitId: String? = null, position: Int = 0): String? {
        if (!initialized) return null
        return NativeAdPreloadCache.preload(adUnitId = adUnitId, position = position)
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

    private fun registerActivityTracking() {
        val app = appContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivityRef = WeakReference(activity)
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
