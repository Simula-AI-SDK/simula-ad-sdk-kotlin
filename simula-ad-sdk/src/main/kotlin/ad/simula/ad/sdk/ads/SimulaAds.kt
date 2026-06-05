package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.privacy.SimulaPrivacy
import ad.simula.ad.sdk.privacy.SimulaPrivacyConfig
import ad.simula.ad.sdk.provider.SimulaSessionStore
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

    // ── Character context ────────────────────────────────────────────────────
    // Attached to every imperative interstitial (`/ads/load/interstitial`) request.
    // Seed it via [initialize]; update it on the fly by assigning these directly or
    // calling [setCharacter]. The next `SimulaInterstitialAd.load()` sends the
    // current values, so there is no stale per-ad copy to keep in sync.

    /** Character id sent on the imperative `/ads/load/interstitial` request. */
    @Volatile
    var charId: String? = null

    /** Character name sent on the imperative `/ads/load/interstitial` request. */
    @Volatile
    var charName: String? = null

    /** Character avatar URL sent on the imperative `/ads/load/interstitial` request. */
    @Volatile
    var charImage: String? = null

    /** Character description sent on the imperative `/ads/load/interstitial` request. */
    @Volatile
    var charDesc: String? = null

    private var currentActivityRef: WeakReference<Activity>? = null
    internal val currentActivity: Activity? get() = currentActivityRef?.get()

    /** True once [initialize] has been called with a valid key. */
    val isInitialized: Boolean get() = initialized

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
     * @param charId/charName/charImage/charDesc Optional initial character context for the
     *                imperative interstitial. Updatable later via [setCharacter] or by assigning
     *                the [charId]/[charName]/[charImage]/[charDesc] properties.
     */
    fun initialize(
        context: Context,
        apiKey: String,
        devMode: Boolean = false,
        primaryUserID: String? = null,
        hasPrivacyConsent: Boolean = true,
        privacy: SimulaPrivacyConfig? = null,
        charId: String? = null,
        charName: String? = null,
        charImage: String? = null,
        charDesc: String? = null,
    ) {
        if (initialized) return
        require(apiKey.isNotBlank()) { "SimulaAds.initialize requires a non-blank apiKey" }

        appContext = context.applicationContext
        this.apiKey = apiKey
        this.devMode = devMode
        // Seed the character context (changeable later via setCharacter()).
        this.charId = charId
        this.charName = charName
        this.charImage = charImage
        this.charDesc = charDesc

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

        registerActivityTracking()
        initialized = true

        // Warm the session before the first load() so it's off the ad critical path.
        SimulaScope.launch { store.ensureSession() }
    }

    /**
     * Replace the character context used for subsequent interstitial loads. Call this
     * when the active character changes; the next `SimulaInterstitialAd.load()` sends
     * the new values. Omitted arguments are cleared, so a character switch never
     * carries a stale field. For a single-field tweak, assign the property directly
     * (e.g. `SimulaAds.charName = "Luna"`). Safe to call before [initialize].
     */
    fun setCharacter(
        charId: String? = null,
        charName: String? = null,
        charImage: String? = null,
        charDesc: String? = null,
    ) {
        this.charId = charId
        this.charName = charName
        this.charImage = charImage
        this.charDesc = charDesc
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
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }
}
