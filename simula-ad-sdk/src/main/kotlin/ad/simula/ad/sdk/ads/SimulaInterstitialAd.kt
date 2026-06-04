package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.image.ImagePrefetch
import ad.simula.ad.sdk.network.SimulaApiClient
import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.Duration

/**
 * Imperative full-screen interstitial (mirrors the Swift `SimulaInterstitialAd`).
 *
 * Lifecycle: `load()` calls `POST /ads/load`, prefetches the rendered creative
 * assets, then reports LOADED; `show(...)` presents a native carousel of those
 * assets with a call-to-action that routes to the ad's destination. Callbacks are
 * delivered on the main thread via [listener]. After the ad closes, the next one
 * is preloaded automatically.
 *
 * [load] and [show] may be called from any thread — they confine themselves to
 * the main thread internally.
 */
class SimulaInterstitialAd(val adUnitId: String) {

    var listener: SimulaInterstitialAdListener? = null
    var ctaText: String = "Learn More"

    /** Request a rewarded interstitial. When true, the close is gated by [minPlayThreshold]. */
    var rewarded: Boolean = false

    /**
     * Optional character context sent on the `/ads/load` request so the backend can
     * target the creative. Left out of targeting when null.
     */
    var charId: String? = null

    /** Character name displayed in the creative header. */
    var charName: String? = null

    /** Character avatar URL. */
    var charImage: String? = null
    var charDesc: String? = null

    /**
     * Minimum dwell before a rewarded ad can be closed / the reward is earned. Only
     * applies when [rewarded] is true. e.g. `5.seconds` (`kotlin.time.Duration`).
     */
    var minPlayThreshold: Duration = Duration.ZERO

    private sealed interface State {
        object Idle : State
        object Loading : State
        class Ready(val ad: SimulaApiClient.AdLoadResult) : State
        object Showing : State
    }

    // Confined to the main thread (all reads/writes happen there).
    private var state: State = State.Idle
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Preload the next interstitial. No-op if already loading, ready, or showing. */
    fun load() {
        if (!confineToMain { load() }) return

        if (state != State.Idle) return // single in-flight load
        if (!SimulaAds.isInitialized) {
            failLoad(SimulaAdError.NotInitialized)
            return
        }
        state = State.Loading
        SimulaScope.launch {
            try {
                val sessionId = SimulaAds.store.ensureSession()
                if (sessionId.isNullOrBlank()) {
                    failLoadOnMain(SimulaAdError.NoSession)
                    return@launch
                }
                val ad = SimulaApiClient.loadAd(
                    adUnitId = adUnitId,
                    rewarded = rewarded,
                    sessionId = sessionId,
                    charId = charId,
                    charName = charName,
                    charImage = charImage,
                    charDesc = charDesc,
                )
                // A non-blank `rendered_html` takes precedence over the image assets.
                val html = ad.renderedHtml?.takeIf { it.isNotBlank() }
                // FIX M2: blank-asset no-fill + filter blanks before everything else.
                val assets = ad.renderedAssets.filter { it.isNotBlank() }
                // Fillable when we have an HTML creative OR at least one valid asset.
                if (!ad.adInserted || (html == null && assets.isEmpty())) {
                    failLoadOnMain(SimulaAdError.NoFill)
                    return@launch
                }
                // FIX M1: await the asset prefetch (off-main) BEFORE reporting LOADED.
                // HTML creatives have no image assets to prefetch.
                if (html == null) {
                    ImagePrefetch.preload(SimulaAds.appContext, assets)
                }
                withContext(Dispatchers.Main) {
                    state = State.Ready(ad)
                    listener?.onAdLoaded(this@SimulaInterstitialAd)
                }
            } catch (e: Exception) {
                failLoadOnMain(SimulaAdError.Network(e))
            }
        }
    }

    /**
     * Present a loaded interstitial from an explicit [activity] (recommended —
     * guarantees correct same-task window stacking and transitions).
     */
    fun show(activity: Activity) {
        if (!confineToMain { show(activity) }) return
        present(activity)
    }

    /**
     * Present a loaded interstitial using the currently-tracked Activity. Use the
     * [show] overload that takes an explicit `Activity` when you have one.
     */
    fun show() {
        if (!confineToMain { show() }) return
        present(SimulaAds.currentActivity)
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun present(activity: Activity?) {
        val ad = when (val current = state) {
            State.Showing -> {
                failShow(SimulaAdError.AlreadyShowing)
                return
            }
            State.Idle, State.Loading -> {
                failShow(SimulaAdError.NotReady)
                return
            }
            is State.Ready -> current.ad
        }
        // A foreground Activity is required to present — matching Swift's
        // "no presentation context" semantics and AdMob's show(activity). A
        // background activity-start from the app context can be silently dropped
        // on Android 10+, which would strand us in the Showing state.
        if (activity == null) {
            failShow(SimulaAdError.NoPresentationContext)
            return
        }

        val token = UUID.randomUUID().toString()
        InterstitialHandoff.put(
            token,
            InterstitialPresentation(
                ad = ad,
                ctaText = ctaText,
                apiKey = SimulaAds.apiKey,
                rewarded = rewarded,
                minPlayThreshold = minPlayThreshold,
                callbacks = bridge(),
            ),
        )

        if (!launchActivity(token, activity)) {
            InterstitialHandoff.remove(token) // clean up the unused handoff
            failShow(SimulaAdError.NoPresentationContext)
            return
        }
        state = State.Showing
    }

    private fun bridge(): InterstitialCallbacks = object : InterstitialCallbacks {
        override fun onDisplayed() {
            listener?.onAdDisplayed(this@SimulaInterstitialAd)
        }

        override fun onClicked() {
            listener?.onAdClicked(this@SimulaInterstitialAd)
        }

        override fun onEarnedReward() {
            listener?.onAdEarnedReward(this@SimulaInterstitialAd)
        }

        override fun onClosed() {
            state = State.Idle
            listener?.onAdClosed(this@SimulaInterstitialAd)
            load() // auto-preload the next ad (iOS parity)
        }
    }

    private fun launchActivity(token: String, activity: Activity): Boolean {
        return try {
            val intent = Intent(activity, SimulaInterstitialActivity::class.java)
                .putExtra(SimulaInterstitialActivity.EXTRA_TOKEN, token)
            activity.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun failLoad(error: SimulaAdError) {
        state = State.Idle
        listener?.onAdFailedToLoad(this, error)
    }

    private suspend fun failLoadOnMain(error: SimulaAdError) {
        withContext(Dispatchers.Main) { failLoad(error) }
    }

    private fun failShow(error: SimulaAdError) {
        // State is unchanged (stays Ready / Showing) — matches Swift behavior.
        listener?.onAdFailedToDisplay(this, error)
    }

    /**
     * Returns true if already on the main thread (caller should proceed); returns
     * false after re-posting [block] to the main thread (caller should bail out).
     */
    private inline fun confineToMain(crossinline block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return true
        mainHandler.post { block() }
        return false
    }
}
