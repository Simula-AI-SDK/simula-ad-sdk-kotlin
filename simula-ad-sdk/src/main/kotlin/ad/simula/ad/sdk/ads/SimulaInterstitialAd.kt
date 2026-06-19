package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.model.AdBehavior
import ad.simula.ad.sdk.model.AdUnitType
import ad.simula.ad.sdk.model.AdValue
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
import ad.simula.ad.sdk.nativead.NativeAdContextStore
import ad.simula.ad.sdk.network.AdUnitNotFoundException
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.telemetry.Telemetry
import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    // Character context is passed per `load()` call (see below); there is no global
    // character state to keep in sync.

    private sealed interface State {
        object Idle : State
        object Loading : State
        /** [loadedAtMs] is `elapsedRealtime()` at the moment the creative became ready (for staleness). */
        class Ready(val ad: SimulaApiClient.AdLoadResult, val loadedAtMs: Long) : State
        object Showing : State
    }

    // Confined to the main thread (all reads/writes happen there).
    private var state: State = State.Idle
    private val mainHandler = Handler(Looper.getMainLooper())

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

    // Monotonic stage markers for telemetry latencies (0 = not yet started).
    private var loadStartNanos = 0L
    private var showStartNanos = 0L

    /**
     * Preload an interstitial for the given character context.
     *
     * The character fields are sent on the `/ads/load/interstitial` request so the
     * backend can target the creative; all are optional. Behavior:
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
                    reportLoadBlocked(now)
                    return
                }
            // Nothing held — proceed.
            State.Idle -> Unit
        }

        // Supersede any in-flight load / discard any ready ad, then start fresh.
        val generation = ++loadGeneration
        currentKey = key
        currentKeyAtMs = now
        loadStartNanos = System.nanoTime()
        state = State.Loading
        SimulaScope.launch {
            try {
                val sessionId = SimulaAds.store.ensureSession()
                if (generation != loadGeneration) return@launch // superseded
                if (sessionId.isNullOrBlank()) {
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
                val ad = SimulaApiClient.loadAd(
                    adUnitId = adUnitId,
                    sessionId = sessionId,
                    charId = charId,
                    charName = charName,
                    charImage = charImage,
                    charDesc = charDesc,
                    // AdContext (contextual targeting) now rides on the full-screen request too, read
                    // from the same provider-level store the native surface uses.
                    context = NativeAdContextStore.current,
                )
                if (generation != loadGeneration) return@launch // superseded
                // Fillable only when the payload carries a non-blank `rendered_html`
                // creative (whitespace-only HTML is treated as no-fill).
                val html = ad.renderedHtml?.takeIf { it.isNotBlank() }
                if (!ad.adInserted || html == null) {
                    failLoadOnMain(generation, SimulaAdError.NoFill)
                    return@launch
                }
                Telemetry.setExperiment(ad.experiment?.experimentId, ad.experiment?.variantId)
                Telemetry.recordLifecycle(
                    stage = "load_success",
                    adFormat = AD_FORMAT,
                    adUnitId = adUnitId,
                    adId = ad.impressionId,
                    serveId = null,
                    durationMs = elapsedSinceLoad(),
                    errorCode = null,
                )
                withContext(Dispatchers.Main) {
                    if (generation != loadGeneration) return@withContext // superseded
                    state = State.Ready(ad, SystemClock.elapsedRealtime())
                    listener?.onAdLoaded(this@SimulaInterstitialAd)
                }
            } catch (e: Exception) {
                // Genuine exception (network/decoding) — always-sent, deduped handled error,
                // in addition to the sampled `load_fail` lifecycle event from failLoad().
                // ad_unit_not_found is a distinct, non-retryable misconfiguration — surface it as
                // its own case rather than burying it in the generic Network bucket.
                val error =
                    if (e is AdUnitNotFoundException) SimulaAdError.AdUnitNotFound else SimulaAdError.Network(e)
                Telemetry.recordError(
                    signature = "interstitial:load",
                    errorCode = error.telemetryCode(),
                    message = e.message,
                    breadcrumb = "SimulaInterstitialAd.load",
                )
                failLoadOnMain(generation, error)
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
        val position = ClosePosition.from(closePosition)
        // Mirror the server's collision rule: place the store-prompt badge opposite the close button.
        // top_right → top_left; top_left → top_right; bottom_left → top_left (the default position,
        // since a bottom-left close doesn't occupy either top corner).
        val storePromptPosition = when (position) {
            ClosePosition.TOP_RIGHT -> ClosePosition.TOP_LEFT
            ClosePosition.TOP_LEFT -> ClosePosition.TOP_RIGHT
            ClosePosition.BOTTOM_LEFT -> ClosePosition.TOP_LEFT
        }
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
            impressionId = "",          // empty → CreativeInterstitial skips impression tracking
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
                    override fun onImpression() { listener?.onAdImpression(this@SimulaInterstitialAd) }
                    override fun onPaid(adValue: AdValue) { listener?.onAdPaid(this@SimulaInterstitialAd, adValue) }
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
        // A foreground Activity is required to present — matching Swift's
        // "no presentation context" semantics and the standard show(activity) entry point. A
        // background activity-start from the app context can be silently dropped
        // on Android 10+, which would strand us in the Showing state.
        if (activity == null) {
            failShow(SimulaAdError.NoPresentationContext)
            return
        }

        val token = UUID.randomUUID().toString()
        showStartNanos = System.nanoTime()
        InterstitialHandoff.put(
            token,
            InterstitialPresentation(
                ad = ad,
                apiKey = SimulaAds.apiKey,
                callbacks = bridge(ad.impressionId),
            ),
        )

        if (!launchActivity(token, activity)) {
            InterstitialHandoff.remove(token) // clean up the unused handoff
            failShow(SimulaAdError.NoPresentationContext)
            return
        }
        state = State.Showing
    }

    private fun bridge(adId: String): InterstitialCallbacks = object : InterstitialCallbacks {
        override fun onDisplayed() {
            Telemetry.recordLifecycle("displayed", AD_FORMAT, adUnitId, adId, null, elapsedSinceShow(), null)
            listener?.onAdDisplayed(this@SimulaInterstitialAd)
        }

        override fun onImpression() {
            Telemetry.recordLifecycle("impression", AD_FORMAT, adUnitId, adId, null, elapsedSinceShow(), null)
            listener?.onAdImpression(this@SimulaInterstitialAd)
        }

        override fun onPaid(adValue: AdValue) {
            Telemetry.recordLifecycle("paid", AD_FORMAT, adUnitId, adId, null, null, null)
            listener?.onAdPaid(this@SimulaInterstitialAd, adValue)
        }

        override fun onClicked() {
            Telemetry.recordLifecycle("click", AD_FORMAT, adUnitId, adId, null, null, null)
            listener?.onAdClicked(this@SimulaInterstitialAd)
        }

        override fun onClosed() {
            state = State.Idle
            Telemetry.recordLifecycle("closed", AD_FORMAT, adUnitId, adId, null, null, null)
            listener?.onAdClosed(this@SimulaInterstitialAd)
            // Auto-preload the next ad (iOS parity), reusing the last character context.
            load(lastCharId, lastCharName, lastCharImage, lastCharDesc)
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
        Telemetry.recordLifecycle("load_fail", AD_FORMAT, adUnitId, null, null, elapsedSinceLoad(), error.telemetryCode())
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
     * that triggered the block must survive (it stays loadable/showable). The error
     * message reflects whether that ad is ready (with the seconds left in the dedup
     * window) or still loading.
     */
    private fun reportLoadBlocked(nowMs: Long) {
        val error = if (state is State.Ready) {
            val remainingMs = DEDUP_WINDOW_MS - (nowMs - currentKeyAtMs)
            SimulaAdError.DuplicateRequest.ready(((remainingMs + 999) / 1000).toInt())
        } else {
            SimulaAdError.DuplicateRequest.loading()
        }
        // Observable like any other rejected load() (sampled); no real load ran, so no duration.
        Telemetry.recordLifecycle("load_fail", AD_FORMAT, adUnitId, null, null, null, error.telemetryCode())
        listener?.onAdFailedToLoad(this, error)
    }

    private fun failShow(error: SimulaAdError) {
        // State is unchanged (stays Ready / Showing) — matches Swift behavior.
        Telemetry.recordLifecycle("show_fail", AD_FORMAT, adUnitId, null, null, null, error.telemetryCode())
        listener?.onAdFailedToDisplay(this, error)
    }

    /** Monotonic ms since load() began (null if not started). */
    private fun elapsedSinceLoad(): Long? =
        if (loadStartNanos == 0L) null else (System.nanoTime() - loadStartNanos) / 1_000_000

    /** Monotonic ms since present() began (null if not started). */
    private fun elapsedSinceShow(): Long? =
        if (showStartNanos == 0L) null else (System.nanoTime() - showStartNanos) / 1_000_000

    /**
     * Returns true if already on the main thread (caller should proceed); returns
     * false after re-posting [block] to the main thread (caller should bail out).
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

/** Ad-format tag on this class's telemetry events. */
private const val AD_FORMAT = "interstitial"

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
