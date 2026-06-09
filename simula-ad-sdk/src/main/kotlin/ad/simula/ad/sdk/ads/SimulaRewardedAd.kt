package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.network.RewardVerificationManager
import ad.simula.ad.sdk.network.SimulaApiClient
import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * Imperative full-screen rewarded minigame ad (mirrors [SimulaInterstitialAd]).
 *
 * Lifecycle: `load()` calls `POST /minigames/init/rewarded` and prepares the playable
 * iframe; `show(...)` presents it full-screen. The reward is earned by playing for at
 * least the server-returned `duration_seconds`; the close button is available
 * throughout, and an exit confirmation appears if the user leaves early. On a
 * qualifying dismiss the play is verified server-side (`/minigames/verify-reward`,
 * which fires the publisher's SSV postback) off the UI path via a durable, idempotent
 * retry queue. Callbacks are delivered on the main thread via [listener]. After the ad
 * closes, the next one is preloaded automatically.
 *
 * [load] and [show] may be called from any thread — they confine themselves to the
 * main thread internally.
 */
class SimulaRewardedAd(val adUnitId: String) {

    var listener: SimulaRewardedAdListener? = null

    /**
     * Optional minimum play time (seconds) requested from the server. When `> 0` it is
     * sent as `min_play_threshold`; the server's returned `duration_seconds` is what
     * the SDK actually enforces.
     */
    var minPlayThreshold: Int = 0

    private sealed interface State {
        object Idle : State
        object Loading : State
        class Ready(val ad: SimulaApiClient.RewardedInitResult) : State
        object Showing : State
    }

    // Confined to the main thread (all reads/writes happen there).
    private var state: State = State.Idle
    private val mainHandler = Handler(Looper.getMainLooper())

    // Captured at load so verification can run after the Ready state is cleared on close.
    private var sessionId: String? = null
    private var serveId: String? = null

    /** Preload the next rewarded minigame. No-op if already loading, ready, or showing. */
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
                val session = SimulaAds.store.ensureSession()
                if (session.isNullOrBlank()) {
                    failLoadOnMain(SimulaAdError.NoSession)
                    return@launch
                }
                val ad = SimulaApiClient.loadRewarded(
                    adUnitId = adUnitId,
                    sessionId = session,
                    minPlayThreshold = minPlayThreshold.takeIf { it > 0 },
                    charId = SimulaAds.charId,
                    charName = SimulaAds.charName,
                    charImage = SimulaAds.charImage,
                    charDesc = SimulaAds.charDesc,
                )
                // A rewarded ad with no iframe to render is a no-fill.
                if (ad.iframeUrl.isBlank()) {
                    failLoadOnMain(SimulaAdError.NoFill)
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    sessionId = session
                    serveId = ad.serveId
                    // Warm a WebView so show() doesn't pay cold-start on the critical path.
                    WebViewPool.prewarm(SimulaAds.appContext)
                    state = State.Ready(ad)
                    listener?.onAdLoaded(this@SimulaRewardedAd)
                }
            } catch (e: Exception) {
                failLoadOnMain(SimulaAdError.Network(e))
            }
        }
    }

    /**
     * Present a loaded rewarded minigame from an explicit [activity] (recommended —
     * guarantees correct same-task window stacking and transitions).
     */
    fun show(activity: Activity) {
        if (!confineToMain { show(activity) }) return
        present(activity)
    }

    /** Present using the currently-tracked Activity. Prefer the [Activity] overload. */
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
        if (activity == null) {
            failShow(SimulaAdError.NoPresentationContext)
            return
        }

        val token = UUID.randomUUID().toString()
        RewardedHandoff.put(
            token,
            RewardedPresentation(
                iframeUrl = ad.iframeUrl,
                durationSeconds = ad.durationSeconds,
                adId = ad.adId,
                apiKey = SimulaAds.apiKey,
                callbacks = bridge(),
            ),
        )

        if (!launchActivity(token, activity)) {
            RewardedHandoff.remove(token)
            failShow(SimulaAdError.NoPresentationContext)
            return
        }
        state = State.Showing
    }

    private fun bridge(): RewardedCallbacks = object : RewardedCallbacks {
        override fun onDisplayed() {
            listener?.onAdDisplayed(this@SimulaRewardedAd)
        }

        override fun onClose(earned: Boolean, elapsedPlayTimeSeconds: Double) {
            state = State.Idle
            if (earned) {
                listener?.onAdEarnedReward(this@SimulaRewardedAd)
                val sid = serveId
                val sess = sessionId
                if (!sid.isNullOrBlank() && !sess.isNullOrBlank()) {
                    // Off-UI, durable, idempotent verification → SSV postback server-side.
                    // Capture the ad weakly (and `mainHandler` as a local, so the lambda
                    // doesn't capture `this`): a verification still pending in the durable
                    // queue — e.g. retrying after an outage — must not pin the ad, and its
                    // listener (often an Activity), in memory until the next drain. If the
                    // ad has been collected by the time verification lands, the server-side
                    // SSV postback still fires; only the client callback is skipped.
                    val weakAd = WeakReference(this@SimulaRewardedAd)
                    val handler = mainHandler
                    RewardVerificationManager.queueVerification(
                        context = SimulaAds.appContext,
                        serveId = sid,
                        sessionId = sess,
                        elapsedPlayTime = elapsedPlayTimeSeconds,
                    ) { result ->
                        handler.post {
                            val ad = weakAd.get() ?: return@post
                            result.fold(
                                onSuccess = { token ->
                                    ad.listener?.onAdRewardVerified(ad, token)
                                },
                                onFailure = { err ->
                                    ad.listener?.onAdRewardVerificationFailed(ad, err)
                                },
                            )
                        }
                    }
                }
            }
            listener?.onAdClosed(this@SimulaRewardedAd)
            load() // auto-preload the next ad (iOS parity)
        }
    }

    private fun launchActivity(token: String, activity: Activity): Boolean {
        return try {
            val intent = Intent(activity, SimulaRewardedActivity::class.java)
                .putExtra(SimulaRewardedActivity.EXTRA_TOKEN, token)
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
        // State is unchanged (stays Ready / Showing) — matches the interstitial.
        listener?.onAdFailedToDisplay(this, error)
    }

    /**
     * Returns true if already on the main thread (caller should proceed); returns false
     * after re-posting [block] to the main thread (caller should bail out).
     */
    private inline fun confineToMain(crossinline block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return true
        mainHandler.post { block() }
        return false
    }
}
