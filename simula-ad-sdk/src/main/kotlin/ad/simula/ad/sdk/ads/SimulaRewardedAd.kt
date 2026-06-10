package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.model.AdBehavior
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.StorePrompt
import ad.simula.ad.sdk.model.StorePromptPlatform
import ad.simula.ad.sdk.network.RewardVerificationManager
import ad.simula.ad.sdk.network.SimulaApiClient
import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
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
        /** [loadedAtMs] is `elapsedRealtime()` at the moment the ad became ready (for staleness). */
        class Ready(val ad: SimulaApiClient.RewardedInitResult, val loadedAtMs: Long) : State
        object Showing : State
    }

    // Confined to the main thread (all reads/writes happen there).
    private var state: State = State.Idle
    private val mainHandler = Handler(Looper.getMainLooper())

    // Captured at load so verification can run after the Ready state is cleared on close.
    // `impressionId` is the verify-reward handle (the wire body still names it `serve_id`).
    private var sessionId: String? = null
    private var impressionId: String? = null

    // Dedup: the (ad unit, character, session) key of the load currently in flight or
    // ready, and when that load was initiated. Re-loads of the same key are blocked for
    // [DEDUP_WINDOW_MS]. Confined to the main thread.
    private var currentKey: String? = null
    private var currentKeyAtMs: Long = 0L

    // Character context of the last load(), replayed by the post-close auto-preload.
    private var lastCharId: String? = null
    private var lastCharName: String? = null
    private var lastCharImage: String? = null
    private var lastCharDesc: String? = null

    // Bumped on every load() that starts; an async result whose generation no longer
    // matches has been superseded (a newer load replaced it) and is dropped.
    @Volatile
    private var loadGeneration: Int = 0

    /**
     * Preload a rewarded minigame for the given character context.
     *
     * The character fields are sent on the `/load/rewarded` request so the backend can
     * target the creative; all are optional. Behavior:
     *
     * - **Single in-flight load.** While a load for the same ad is in flight or ready,
     *   calling `load()` again with the **same** key — (ad unit id, [charId],
     *   [charName], session id) — is blocked for 5 minutes and reports
     *   [SimulaAdError.DuplicateRequest]. A **different** ad unit or character is treated
     *   as new and supersedes the pending/ready ad.
     * - **Staleness.** A loaded ad expires after 1 hour; `show()` then fails with
     *   [SimulaAdError.Stale].
     * - **No-op while showing.** Ignored while an ad is on screen; the next ad is
     *   preloaded automatically on close.
     */
    @JvmOverloads
    fun load(
        charId: String? = null,
        charName: String? = null,
        charImage: String? = null,
        charDesc: String? = null,
    ) {
        if (!confineToMain { load(charId, charName, charImage, charDesc) }) return

        if (!SimulaAds.isInitialized) {
            failLoad(SimulaAdError.NotInitialized)
            return
        }

        // Replay the same character context on the post-close auto-preload.
        lastCharId = charId
        lastCharName = charName
        lastCharImage = charImage
        lastCharDesc = charDesc

        val key = dedupKey(charId, charName)
        val now = SystemClock.elapsedRealtime()

        when (state) {
            // An ad is on screen — the next one preloads on close. Don't start a load.
            State.Showing -> return
            // A matching ad is already loading/ready: block a same-key re-load within
            // the dedup window; a different key falls through and supersedes it.
            State.Loading, is State.Ready ->
                if (currentKey == key && now - currentKeyAtMs < DEDUP_WINDOW_MS) {
                    reportLoadBlocked()
                    return
                }
            // Nothing held — proceed.
            State.Idle -> Unit
        }

        // Supersede any in-flight load / discard any ready ad, then start fresh.
        val generation = ++loadGeneration
        currentKey = key
        currentKeyAtMs = now
        state = State.Loading
        SimulaScope.launch {
            try {
                val session = SimulaAds.store.ensureSession()
                if (generation != loadGeneration) return@launch // superseded
                if (session.isNullOrBlank()) {
                    failLoadOnMain(generation, SimulaAdError.NoSession)
                    return@launch
                }
                // The real session id is now known. Refresh the dedup key — the
                // synchronous gate at load() time may have keyed on an empty session
                // during cold-start warm-up — so a subsequent same-key load() still
                // deduplicates. The throttle timestamp (currentKeyAtMs) intentionally
                // stays at the original load time.
                withContext(Dispatchers.Main) {
                    if (generation == loadGeneration) currentKey = dedupKey(charId, charName)
                }
                if (generation != loadGeneration) return@launch // superseded during the hop
                val ad = SimulaApiClient.loadRewarded(
                    adUnitId = adUnitId,
                    sessionId = session,
                    minPlayThreshold = minPlayThreshold.takeIf { it > 0 },
                    charId = charId,
                    charName = charName,
                    charImage = charImage,
                    charDesc = charDesc,
                )
                if (generation != loadGeneration) return@launch // superseded
                // A rewarded ad with no iframe to render is a no-fill.
                if (ad.iframeUrl.isBlank()) {
                    failLoadOnMain(generation, SimulaAdError.NoFill)
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    if (generation != loadGeneration) return@withContext // superseded
                    sessionId = session
                    impressionId = ad.impressionId
                    // Warm a WebView so show() doesn't pay cold-start on the critical path.
                    WebViewPool.prewarm(SimulaAds.appContext)
                    state = State.Ready(ad, SystemClock.elapsedRealtime())
                    listener?.onAdLoaded(this@SimulaRewardedAd)
                }
            } catch (e: Exception) {
                failLoadOnMain(generation, SimulaAdError.Network(e))
            }
        }
    }

    /**
     * Dedup key: (ad unit id, character id, character name, current session id), joined
     * with a NUL separator so values containing spaces can't collide across fields.
     */
    private fun dedupKey(charId: String?, charName: String?): String {
        val session = SimulaAds.store.sessionId.orEmpty()
        return "$adUnitId\u0000${charId.orEmpty()}\u0000${charName.orEmpty()}\u0000$session"
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

    // ── Preview (debug / QA) ──────────────────────────────────────────────────

    /**
     * Present the rewarded minigame with a **hardcoded** placeholder playable and a mid-ad
     * store prompt — no network call and no impression tracked. Lets a host/sample app preview
     * the store-prompt badge timing: it appears at half the play-to-earn duration
     * (`durationSeconds / 2`) and is removed the moment the reward unlocks.
     *
     * @param activity foreground Activity to present from.
     * @param durationSeconds the play-to-earn gate; the badge shows at `durationSeconds / 2`.
     * @param storePrompt whether to show the mid-ad store-prompt badge.
     * @param storePromptPlatform `android` (▶| Google Play) / `ios` (▶| App Store).
     */
    @JvmOverloads
    fun showPreview(
        activity: Activity,
        durationSeconds: Int = 8,
        storePrompt: Boolean = true,
        storePromptPlatform: String = "android",
    ) {
        if (!confineToMain { showPreview(activity, durationSeconds, storePrompt, storePromptPlatform) }) return
        if (!SimulaAds.isInitialized) {
            failShow(SimulaAdError.NotInitialized)
            return
        }
        if (state == State.Showing) {
            failShow(SimulaAdError.AlreadyShowing)
            return
        }
        // The reward/close pill always sits top-right, so the badge defaults to the opposite
        // corner (top-left) — matching the server's collision resolution for a rewarded play.
        val behavior = if (storePrompt) {
            AdBehavior(
                storePrompt = StorePrompt(
                    enabled = true,
                    position = ClosePosition.TOP_LEFT,
                    platform = StorePromptPlatform.from(storePromptPlatform),
                ),
            )
        } else {
            AdBehavior()
        }

        val token = UUID.randomUUID().toString()
        RewardedHandoff.put(
            token,
            RewardedPresentation(
                iframeUrl = PREVIEW_MINIGAME_DATA_URL,
                durationSeconds = durationSeconds.coerceAtLeast(0),
                impressionId = "", // empty → no impression tracked
                apiKey = SimulaAds.apiKey,
                // Preview is local-only: report lifecycle but do NOT verify a reward or auto-preload.
                callbacks = object : RewardedCallbacks {
                    override fun onDisplayed() {
                        listener?.onAdDisplayed(this@SimulaRewardedAd)
                    }

                    override fun onClose(earned: Boolean, elapsedPlayTimeSeconds: Double) {
                        state = State.Idle
                        if (earned) listener?.onAdEarnedReward(this@SimulaRewardedAd)
                        listener?.onAdClosed(this@SimulaRewardedAd)
                    }
                },
                adBehavior = behavior,
                trackingUrl = PREVIEW_TRACKING_URL,
                destination = "appstore",
            ),
        )
        if (!launchActivity(token, activity)) {
            RewardedHandoff.remove(token)
            failShow(SimulaAdError.NoPresentationContext)
            return
        }
        state = State.Showing
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
            is State.Ready -> {
                // A loaded ad expires after 1 hour. Drop it (back to Idle so the host
                // can load() again — the dedup window is long gone) and report Stale.
                if (SystemClock.elapsedRealtime() - current.loadedAtMs > STALE_AFTER_MS) {
                    state = State.Idle
                    failShow(SimulaAdError.Stale)
                    return
                }
                current.ad
            }
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
                impressionId = ad.impressionId,
                apiKey = SimulaAds.apiKey,
                callbacks = bridge(),
                adBehavior = ad.adBehavior,
                trackingUrl = ad.trackingUrl,
                destination = ad.destination,
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
                val sid = impressionId
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
            // Auto-preload the next ad (iOS parity), reusing the last character context.
            load(lastCharId, lastCharName, lastCharImage, lastCharDesc)
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

    private suspend fun failLoadOnMain(generation: Int, error: SimulaAdError) {
        withContext(Dispatchers.Main) {
            if (generation != loadGeneration) return@withContext // superseded
            failLoad(error)
        }
    }

    /**
     * Report a dedup-blocked load without disturbing state — the in-flight or ready ad
     * that triggered the block must survive (it stays loadable/showable).
     */
    private fun reportLoadBlocked() {
        listener?.onAdFailedToLoad(this, SimulaAdError.DuplicateRequest)
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

    private companion object {
        /** A loaded ad expires this long after it became ready (staleness). */
        const val STALE_AFTER_MS = 60 * 60 * 1000L // 1 hour

        /** Re-loads of the same dedup key are blocked for this long. */
        const val DEDUP_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
    }
}

/** A real Play Store URL used only by [SimulaRewardedAd.showPreview] so a store-prompt tap can
 * resolve a Play package and route there. */
private const val PREVIEW_TRACKING_URL =
    "https://play.google.com/store/apps/details?id=com.google.android.apps.maps"

/** A self-contained placeholder "playable" so [SimulaRewardedAd.showPreview] can render the
 * play-to-earn gate + store-prompt chrome over a visible surface without loading a network iframe. */
private const val PREVIEW_MINIGAME_HTML =
    "<!doctype html><html><head><meta name=\"viewport\" " +
        "content=\"width=device-width, initial-scale=1, viewport-fit=cover\"></head>" +
        "<body style=\"margin:0;height:100vh;display:flex;align-items:center;justify-content:center;" +
        "background:linear-gradient(160deg,#0f766e,#7c3aed);font-family:sans-serif;color:#fff;text-align:center\">" +
        "<div><div style=\"font-size:22px;font-weight:700\">Rewarded Minigame Preview</div>" +
        "<div style=\"opacity:.8;margin-top:8px;font-size:15px\">Mid-ad store prompt — no network</div></div>" +
        "</body></html>"

/** The placeholder playable as a `data:` URL the pooled WebView can `loadUrl(...)` directly. */
private val PREVIEW_MINIGAME_DATA_URL: String =
    "data:text/html;base64," + Base64.encodeToString(PREVIEW_MINIGAME_HTML.toByteArray(), Base64.NO_WRAP)
