package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.model.AdBehavior
import ad.simula.ad.sdk.model.AdUnitType
import ad.simula.ad.sdk.model.CloseBehavior
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.CloseTreatment
import ad.simula.ad.sdk.model.Creative
import ad.simula.ad.sdk.model.MAX_CLOSE_DELAY_SECONDS
import ad.simula.ad.sdk.model.OverlayTiming
import ad.simula.ad.sdk.model.SkOverlayConfig
import ad.simula.ad.sdk.model.StorePrompt
import ad.simula.ad.sdk.model.StorePromptPlatform
import ad.simula.ad.sdk.model.validatedHexColor
import ad.simula.ad.sdk.network.SimulaApiClient
import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Imperative full-screen interstitial (mirrors the Swift `SimulaInterstitialAd`).
 *
 * Lifecycle: `load()` calls `POST /ads/load/interstitial`, prefetches the
 * server-rendered HTML creative, then reports LOADED; `show(...)` presents that
 * creative full-screen in a web view (it owns its own CTA, routing to the ad's
 * destination). Callbacks are delivered on the main thread via [listener]. After
 * the ad closes, the next one is preloaded automatically.
 *
 * [load] and [show] may be called from any thread — they confine themselves to
 * the main thread internally.
 */
class SimulaInterstitialAd(val adUnitId: String) {

    var listener: SimulaInterstitialAdListener? = null

    // Character context is global: set it on `SimulaAds` (via initialize() or
    // setCharacter()), and every load() reads the current values from there.

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
                    sessionId = sessionId,
                    charId = SimulaAds.charId,
                    charName = SimulaAds.charName,
                    charImage = SimulaAds.charImage,
                    charDesc = SimulaAds.charDesc,
                )
                // Fillable only when the payload carries a non-blank `rendered_html`
                // creative (whitespace-only HTML is treated as no-fill).
                val html = ad.renderedHtml?.takeIf { it.isNotBlank() }
                if (!ad.adInserted || html == null) {
                    failLoadOnMain(SimulaAdError.NoFill)
                    return@launch
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

    // ── Preview (debug / QA) ──────────────────────────────────────────────────

    /**
     * Present the interstitial with a **hardcoded** `ad_behavior` and a placeholder creative —
     * no network call and no impression tracked. Lets a host/sample app preview the A/B
     * close-button treatments without the backend assigning the variant.
     *
     * Parameters take the same wire strings the server would send; unknown values fall back
     * exactly as a real payload would (e.g. an unknown treatment → `hidden`).
     *
     * @param activity foreground Activity to present from.
     * @param closeTreatment `hidden` / `countdown_circle` / `progress_bar` / `reward_or_close_label`.
     * @param closePosition `top_right` / `top_left` / `bottom_left`.
     * @param delaySeconds the pre-tap close delay the treatment animates over.
     * @param progressBarColor 6-digit hex (optional leading `#`) tinting the ring / bar fill.
     * @param adUnitType `interstitial` / `rewarded` — drives the `reward_or_close_label` copy.
     * @param storePrompt also show the mid-ad store-prompt badge.
     * @param installBanner also present the Play install banner.
     */
    @JvmOverloads
    fun showPreview(
        activity: Activity,
        closeTreatment: String,
        closePosition: String = "top_right",
        delaySeconds: Int = 5,
        progressBarColor: String = "#FFFFFF",
        adUnitType: String = "interstitial",
        storePrompt: Boolean = false,
        installBanner: Boolean = false,
    ) {
        if (!confineToMain {
                showPreview(
                    activity, closeTreatment, closePosition, delaySeconds,
                    progressBarColor, adUnitType, storePrompt, installBanner,
                )
            }
        ) return

        if (state == State.Showing) {
            failShow(SimulaAdError.AlreadyShowing)
            return
        }

        val treatment = CloseTreatment.from(closeTreatment)
        var position = ClosePosition.from(closePosition)
        // Mirror the wire→domain snap: an edge-anchored treatment can't sit bottom_left.
        if (position == ClosePosition.BOTTOM_LEFT && !treatment.allowsBottomLeft) {
            position = ClosePosition.TOP_RIGHT
        }
        // Mirror the server's collision rule: render the store-prompt badge opposite the close button.
        val storePromptPosition =
            if (position == ClosePosition.TOP_RIGHT) ClosePosition.TOP_LEFT else ClosePosition.TOP_RIGHT
        val behavior = AdBehavior(
            close = CloseBehavior(
                delaySeconds = delaySeconds.coerceIn(0, MAX_CLOSE_DELAY_SECONDS),
                treatment = treatment,
                position = position,
                progressBarColor = validatedHexColor(progressBarColor),
            ),
            storePrompt = if (storePrompt) {
                StorePrompt(enabled = true, position = storePromptPosition, platform = StorePromptPlatform.ANDROID)
            } else null,
            skoverlay = if (installBanner) {
                SkOverlayConfig(enabled = true, timing = OverlayTiming.DURING_PLAY)
            } else null,
        )
        val ad = SimulaApiClient.AdLoadResult(
            adId = "",                  // empty → CreativeInterstitial skips impression tracking
            adInserted = true,
            adUnitId = adUnitId,
            destination = "appstore",
            renderedFormat = null,
            trackingUrl = PREVIEW_TRACKING_URL,  // lets a store-prompt / install-banner tap route
            renderedHtml = PREVIEW_CREATIVE_HTML,
            adBehavior = behavior,
            creative = Creative(type = "preview", adUnitType = AdUnitType.from(adUnitType)),
        )

        val token = UUID.randomUUID().toString()
        InterstitialHandoff.put(
            token,
            InterstitialPresentation(
                ad = ad,
                apiKey = SimulaAds.apiKey,
                // Preview is local-only: report lifecycle but do NOT auto-preload a real ad on close.
                callbacks = object : InterstitialCallbacks {
                    override fun onDisplayed() { listener?.onAdDisplayed(this@SimulaInterstitialAd) }
                    override fun onClicked() { listener?.onAdClicked(this@SimulaInterstitialAd) }
                    override fun onClosed() {
                        state = State.Idle
                        listener?.onAdClosed(this@SimulaInterstitialAd)
                    }
                },
            ),
        )
        if (!launchActivity(token, activity)) {
            InterstitialHandoff.remove(token)
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
                apiKey = SimulaAds.apiKey,
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

/** A self-contained placeholder creative so [SimulaInterstitialAd.showPreview] can render the
 * close-button A/B chrome over a visible surface without fetching a network creative. */
private const val PREVIEW_CREATIVE_HTML =
    "<!doctype html><html><head><meta name=\"viewport\" " +
        "content=\"width=device-width, initial-scale=1, viewport-fit=cover\"></head>" +
        "<body style=\"margin:0;height:100vh;display:flex;align-items:center;justify-content:center;" +
        "background:linear-gradient(160deg,#1e3a8a,#7c3aed);font-family:sans-serif;color:#fff;text-align:center\">" +
        "<div><div style=\"font-size:22px;font-weight:700\">A/B Close Button Preview</div>" +
        "<div style=\"opacity:.8;margin-top:8px;font-size:15px\">Hardcoded ad_behavior — no network</div></div>" +
        "</body></html>"

/** A real Play Store URL used only by [SimulaInterstitialAd.showPreview] so a store-prompt-badge or
 * install-banner tap can resolve a Play package and route there (the banner itself renders without
 * it; only the tap needs a destination). */
private const val PREVIEW_TRACKING_URL =
    "https://play.google.com/store/apps/details?id=com.google.android.apps.maps"
