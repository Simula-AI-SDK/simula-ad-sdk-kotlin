package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.bridge.BridgeWebViewInstaller
import ad.simula.ad.sdk.bridge.CreativeBridge
import ad.simula.ad.sdk.bridge.CreativeTelemetryWebChromeClient
import ad.simula.ad.sdk.bridge.CreativeTelemetryWebViewClient
import ad.simula.ad.sdk.bridge.androidCreativeBridge
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.minigame.repaintOnNextFrame
import ad.simula.ad.sdk.model.AdUnitType
import ad.simula.ad.sdk.model.AutoStoreRedirectTrigger
import ad.simula.ad.sdk.model.CloseBehavior
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.CloseTreatment
import ad.simula.ad.sdk.model.OverlayPosition
import ad.simula.ad.sdk.model.OverlayTiming
import ad.simula.ad.sdk.model.SkOverlayConfig
import ad.simula.ad.sdk.model.StorePrompt
import ad.simula.ad.sdk.model.StorePromptPlatform
import ad.simula.ad.sdk.network.AdBeaconManager
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.provider.ProvideSimulaContext
import ad.simula.ad.sdk.util.ColorUtil
import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Transparent, full-screen host for the imperative interstitial. Reads its
 * [InterstitialPresentation] from [InterstitialHandoff] by token, injects the
 * shared (warmed) session via [ProvideSimulaContext], and renders the
 * server-rendered HTML creative (which owns its own CTA), with the server-driven
 * `ad_behavior` A/B chrome (close treatment, mid-ad store prompt, install banner)
 * layered on top.
 */
internal class SimulaInterstitialActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TOKEN = "ad.simula.ad.sdk.TOKEN"
    }

    private var presentation: InterstitialPresentation? = null
    private var token: String? = null
    private var closed = false
    // Store-exit funnel tracker for this presentation (store_opened/returned/abandoned). Main-thread only.
    private var storeExit: StoreExitTracker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        token = intent?.getStringExtra(EXTRA_TOKEN)
        // Non-destructive read so the presentation survives Activity recreation
        // (a config change not covered by configChanges, e.g. fontScale/density).
        val p = token?.let { InterstitialHandoff.get(it) }
        if (p == null) {
            // No presentation (e.g. process death after handoff cleared) — nothing to show.
            finish()
            return
        }
        presentation = p
        storeExit = StoreExitTracker(
            adId = p.ad.impressionId.takeIf { it.isNotBlank() },
            adFormat = "interstitial",
        )

        configureWindow()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        setContent {
            ProvideSimulaContext(
                store = SimulaAds.store,
                apiKey = SimulaAds.apiKey,
                devMode = SimulaAds.devMode,
            ) {
                // On close, fetch + show a fallback ad before finishing (minigame parity). CLOSED is
                // reported when the primary creative closes; the Activity finishes after the fallback.
                FallbackAdHost(
                    impressionId = p.ad.impressionId,
                    onFullyClosed = ::finishAd,
                    autoStoreRedirect = p.ad.adBehavior?.autoStoreRedirect,
                    // A user tap on an end-screen CTA is a click (parity with the creative CTA / store
                    // prompt): surface the PARENT interstitial ad to onAdClicked. The end-screen iframe
                    // self-reports its own click beacon, so fire the callback only — no SDK beacon here.
                    onAdClick = { p.callbacks.onClicked() },
                    // END_SCREEN_N opens the primary ad's store (the same path as a CTA / PLAYABLE_END).
                    onAutoStoreRedirect = {
                        storeExit?.recordStoreOpen("auto_redirect")
                        CreativeCtaRouter.open(
                            applicationContext,
                            p.ad.trackingUrl,
                            p.ad.destination,
                            p.ad.adBehavior?.storeOpen,
                        )
                    },
                ) { onClose ->
                    CreativeInterstitial(
                        presentation = p,
                        onFinish = {
                            reportClosed()
                            onClose()
                        },
                        recordStoreOpen = { trigger -> storeExit?.recordStoreOpen(trigger) },
                        openDestination = { ad ->
                            // applicationContext so the open survives auto-dismiss. `storeOpen` is
                            // null when the payload omits `ad_behavior` → today's store path.
                            CreativeCtaRouter.open(
                                applicationContext,
                                ad.trackingUrl,
                                ad.destination,
                                ad.adBehavior?.storeOpen,
                            )
                        },
                    )
                }
            }
        }
    }

    /** Fire CLOSED exactly once when the primary creative closes. Does NOT finish — the fallback-ad
     * host finishes the Activity via [finishAd] once any post-close fallback ad is done. */
    private fun reportClosed() {
        if (closed) return
        closed = true
        storeExit?.onAdClosed() // resolve any outstanding store visit as an abandon
        presentation?.callbacks?.onClosed()
    }

    override fun onResume() {
        super.onResume()
        storeExit?.onResume()
    }

    override fun onPause() {
        super.onPause()
        storeExit?.onPause()
    }

    /** Tear the Activity down (after the optional fallback ad). */
    private fun finishAd() {
        finish() // isFinishing becomes true → onDestroy drops the handoff entry
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only act when finishing for good. On a config-change recreation
        // (isFinishing == false) we keep the handoff so the new instance can read
        // it, and we must NOT report CLOSED.
        if (isFinishing) {
            token?.let { InterstitialHandoff.remove(it) }
            if (!closed) {
                closed = true
                storeExit?.onAdClosed() // finished without a normal close → resolve any open store visit
                presentation?.callbacks?.onClosed()
            }
        }
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * Foreground on-screen time after begin-to-render before the billable IMPRESSION + PAID fire for a
 * full-screen ad. The PRD's OMID rule: "fire IMPRESSION + PAID at begin-to-render after 2 seconds"
 * (viewability is measured by the OM SDK, not gated by us). Shared by the interstitial and rewarded
 * Activities.
 */
internal const val FULLSCREEN_IMPRESSION_DELAY_MS = 2_000L

/** Poll cadence for the foreground impression timer. Fine enough to land the 2s mark within ~1 frame,
 * coarse enough to stay negligible. Shared by the interstitial and rewarded Activities. */
internal const val IMPRESSION_TICK_MS = 200L

/** True if the close gate's foreground dwell was already satisfied in a prior Activity instance
 * (config-change recreation), so the close should start enabled. Based on accumulated foreground
 * time so rotation can't reset the dwell. */
private fun gateAlreadyElapsed(p: InterstitialPresentation, total: Duration): Boolean =
    p.accumulatedGateTimeMs >= total.inWholeMilliseconds

@Composable
private fun CreativeInterstitial(
    presentation: InterstitialPresentation,
    onFinish: () -> Unit,
    recordStoreOpen: (String) -> Unit,
    openDestination: (SimulaApiClient.AdLoadResult) -> Unit,
) {
    val ad = presentation.ad
    // The server-rendered HTML creative is the sole creative. load() only readies an
    // ad once `rendered_html` is non-blank, so this is effectively always present.
    val html = remember(ad) { ad.renderedHtml?.takeIf { it.isNotBlank() } }

    // Server-driven render config (null → render today's literal close button / store path).
    val behavior = ad.adBehavior
    val treatment = behavior?.close?.treatment ?: CloseTreatment.HIDDEN
    // "Reward in X" vs "Close in X" copy for the reward_or_close_label treatment.
    val isRewardCopy = ad.adUnitType == AdUnitType.REWARDED

    // Close gate: the interstitial gates its close button on the server-driven `close.delay_seconds`.
    val gateTotal: Duration = (behavior?.close?.delaySeconds ?: 0).seconds

    // Close is enabled immediately UNLESS a delay gate applies. A gate that already elapsed in a
    // prior Activity instance (config-change recreation) also starts closable — anchored to
    // wall-clock so rotation can't reset the dwell or strand the user with close blocked.
    var closeEnabled by remember {
        mutableStateOf(gateTotal <= Duration.ZERO || gateAlreadyElapsed(presentation, gateTotal))
    }

    // Countdown affordance state. `closeRemaining` drives the reward_or_close_label copy.
    var closeRemaining by remember {
        mutableStateOf(ceil(gateTotal.toDouble(DurationUnit.SECONDS)).toInt().coerceAtLeast(0))
    }
    val closeProgress = remember { Animatable(0f) }

    // Mid-ad store prompt (`store_prompt`) — an early install affordance revealed at the halfway
    // point to the close button and removed the instant the real close button appears (see
    // `!closeEnabled` at the render site). Revealed from the gate loop below so it tracks the same
    // foreground-only dwell; with no gate (immediate close) there is no pre-close window.
    val storePrompt = behavior?.storePrompt
    var storePromptVisible by remember { mutableStateOf(false) }

    // WebView ↔ SDK bridge (PRD §3). AD_EARLY_COMPLETE unlocks the close button immediately,
    // bypassing the close-delay gate.
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // auto_store_redirect: open the advertiser store once (no user tap). PLAYABLE_END fires when the
    // close button appears (below); END_SCREEN_1/2_OPEN fire when the creative navigates to the
    // matching end-screen marker (handled in the WebView client). A disabled/missing config no-ops.
    val autoRedirect = behavior?.autoStoreRedirect
    var autoRedirectFired by remember { mutableStateOf(false) }
    fun fireAutoStoreRedirect() {
        if (!autoRedirectFired) {
            autoRedirectFired = true
            recordStoreOpen("auto_redirect")
            openDestination(ad)
        }
    }
    val bridge = remember {
        androidCreativeBridge(
            appContext = context.applicationContext,
            activityProvider = { context as? Activity },
            onEarlyComplete = { closeEnabled = true },
        )
    }

    // PLAYABLE_END — open the store the moment the close button becomes available (SDK-native, no
    // bridge). The keyed effect runs on first composition (covers a delay-0 immediate close) and on
    // every flip of `closeEnabled`; the one-shot guard makes repeats a no-op.
    if (autoRedirect?.enabled == true && autoRedirect.trigger == AutoStoreRedirectTrigger.PLAYABLE_END) {
        LaunchedEffect(closeEnabled) {
            if (closeEnabled) fireAutoStoreRedirect()
        }
    }

    if (gateTotal > Duration.ZERO) {
        // Foreground-only close gate. Dwell accrues only while the Activity is RESUMED:
        // repeatOnLifecycle cancels the ticking loop when the app is backgrounded and restarts it on
        // return, so the close can't be unlocked by simply leaving the app for the gate duration. The
        // accumulated time lives on the presentation, so a config change (rotation) resumes it. The
        // mid-ad store prompt is revealed off the same accrued dwell at the halfway point.
        LaunchedEffect(Unit) {
            val totalMs = gateTotal.inWholeMilliseconds
            if (presentation.accumulatedGateTimeMs >= totalMs) {
                closeEnabled = true
                return@LaunchedEffect
            }
            val showsBar = treatment == CloseTreatment.COUNTDOWN_CIRCLE || treatment == CloseTreatment.PROGRESS_BAR
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (closeEnabled) return@repeatOnLifecycle
                // Bar/ring fill: ONE continuous, frame-clock animation to full over the remaining
                // foreground time — not a value snapped on each 250 ms tick. Anchored to the already-
                // accrued fraction on every (re)resume; backgrounding cancels this child alongside the
                // accounting loop so the fill freezes, and the next resume re-anchors + re-launches.
                // Driving it off the frame clock (instead of chasing a stepped target with an equal-
                // length tween) is what keeps it smooth when ticks land late under main-thread load.
                if (showsBar) {
                    val accruedFraction = (presentation.accumulatedGateTimeMs.toFloat() / totalMs).coerceIn(0f, 1f)
                    closeProgress.snapTo(accruedFraction)
                    val remainingMs = (totalMs - presentation.accumulatedGateTimeMs).coerceAtLeast(0L)
                    launch {
                        closeProgress.animateTo(1f, tween(durationMillis = remainingMs.toInt(), easing = LinearEasing))
                    }
                }
                // Re-anchor on each resume so a backgrounded interval is never counted. This loop now
                // owns ONLY the accounting (close-enable, countdown number, store prompt); the visual
                // fill is the continuous animation above.
                var lastTickMs = SystemClock.elapsedRealtime()
                while (true) {
                    delay(250L)
                    val now = SystemClock.elapsedRealtime()
                    presentation.accumulatedGateTimeMs += now - lastTickMs
                    lastTickMs = now
                    val accumulated = presentation.accumulatedGateTimeMs
                    if (treatment == CloseTreatment.REWARD_OR_CLOSE_LABEL) {
                        closeRemaining = ceil((totalMs - accumulated).coerceAtLeast(0L) / 1000.0).toInt()
                    }
                    // Reveal the mid-ad store prompt at the halfway point (foreground dwell only).
                    if (accumulated >= totalMs / 2) {
                        storePromptVisible = true
                    }
                    if (accumulated >= totalMs) {
                        closeEnabled = true
                        break
                    }
                }
            }
        }
    }

    // Play Install Prompt (`skoverlay`) — an SDK-presented bottom install banner. Gated to API 21+.
    val skoverlay = behavior?.skoverlay
    var installBannerVisible by remember { mutableStateOf(false) }
    if (skoverlay != null && skoverlay.enabled && Build.VERSION.SDK_INT >= 21) {
        when (skoverlay.timing) {
            // during_play / delayed present automatically (after the optional delay).
            OverlayTiming.DURING_PLAY, OverlayTiming.DELAYED -> LaunchedEffect(Unit) {
                if (skoverlay.delaySeconds > 0) delay(skoverlay.delaySeconds.seconds)
                installBannerVisible = true
            }
            // on_click is triggered from the CTA handler.
            OverlayTiming.ON_CLICK -> Unit
        }
    }

    // SHOWN — fired once the creative first composes
    // (begin-to-render), reporting the `/shown` beacon. Guarded so an Activity recreation
    // (config change) doesn't double-report.
    LaunchedEffect(Unit) {
        if (!presentation.displayedReported) {
            presentation.displayedReported = true
            presentation.callbacks.onDisplayed()
            // Durable beacon (was a fire-and-forget trackShown).
            AdBeaconManager.enqueue(ad.impressionId, "shown", adFormat = "interstitial")
        }
    }

    // IMPRESSION + PAID (the billable impression + paid event) — fired together once the creative
    // has been on screen for [FULLSCREEN_IMPRESSION_DELAY_MS] of FOREGROUND time after begin-to-render.
    // OMID measures viewability but does not gate us (PRD). Foreground-only (repeatOnLifecycle(RESUMED))
    // so a backgrounded ad can't accrue the delay; the accrued time lives on the presentation so a
    // config-change recreation resumes rather than restarts. The `/seen` beacon is the billing source
    // of truth; onPaid is local analytics only and needs no network (the value is already on-device).
    LaunchedEffect(Unit) {
        if (presentation.impressionReported) return@LaunchedEffect

        fun fireImpressionAndPaid() {
            if (presentation.impressionReported) return
            presentation.impressionReported = true
            presentation.callbacks.onImpression()
            presentation.callbacks.onPaid(ad.adValue)
            // Durable billable-impression beacon (was a fire-and-forget trackImpression).
            AdBeaconManager.enqueue(ad.impressionId, "seen", adFormat = "interstitial")
        }

        if (presentation.accumulatedImpressionTimeMs >= FULLSCREEN_IMPRESSION_DELAY_MS) {
            fireImpressionAndPaid()
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (presentation.impressionReported) return@repeatOnLifecycle
            var lastTickMs = SystemClock.elapsedRealtime()
            while (true) {
                delay(IMPRESSION_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                presentation.accumulatedImpressionTimeMs += now - lastTickMs
                lastTickMs = now
                if (presentation.accumulatedImpressionTimeMs >= FULLSCREEN_IMPRESSION_DELAY_MS) {
                    fireImpressionAndPaid()
                    return@repeatOnLifecycle
                }
            }
        }
    }

    // System back mirrors the close button: once the close gate has elapsed it drives the SAME
    // close → end-screen (FallbackAdHost) path via [onFinish]; while the gate is still counting it is
    // swallowed (you can't dismiss early). This is the fix for "back skips the end screen" — without
    // it the Activity's default back finished it outright, tearing down before FallbackAdHost ever
    // advanced to its end-screen phase. (The end-screen phase has its own BackHandler in
    // FallbackAdOverlay; this one is only composed during the primary creative.) Mirrors
    // SimulaRewardedActivity's `BackHandler { if (rewardEarned) onFinish(true) }`.
    BackHandler(enabled = true) { if (closeEnabled) onFinish() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // The server-rendered HTML creative is the sole creative; it owns its own CTA.
        if (html != null) {
            CreativeHtml(
                html = html,
                destination = ad.destination,
                bridge = bridge,
                onAdClick = {
                    presentation.callbacks.onClicked()
                    // The creative CTA opens the advertiser store (CreativeCtaRouter.open in CreativeHtml).
                    recordStoreOpen("cta")
                    // Play install banner timed to the click (independent of the store the CTA opens).
                    if (skoverlay != null && skoverlay.enabled &&
                        skoverlay.timing == OverlayTiming.ON_CLICK && Build.VERSION.SDK_INT >= 21
                    ) {
                        installBannerVisible = true
                    }
                },
                // Same config as the rewarded minigame WebView: fill the screen, inset only
                // vertically (top notch / status, bottom nav) and draw under any horizontal cutout.
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
            )
        }

        // Close button — always shown with the compact chrome. Driven by
        // `ad_behavior.close` when present; otherwise a default (top-right, always available) so ads
        // with no `ad_behavior` still get the small close, not a big one.
        val close = behavior?.close ?: CloseBehavior()
        val barAtBottom = closeBarAtBottom(close.treatment, close.position)
        AdCloseButton(
            treatment = close.treatment,
            position = close.position,
            progressBarColor = close.progressBarColor,
            isRewardCopy = isRewardCopy,
            enabled = closeEnabled,
            remaining = closeRemaining,
            progress = closeProgress.value,
            onClose = onFinish,
        )

        // Mid-ad store prompt — pinned to the corner opposite the close button (the SDK mirrors the
        // close position horizontally) during the [closeTime/2, closeTime) window. `!closeEnabled`
        // removes it the instant the real close button appears, so the two affordances never overlap.
        if (storePrompt != null && storePrompt.enabled && storePromptVisible && !closeEnabled) {
            // Center the badge in the same touch-target band as the close button so the two line up.
            StorePromptBadge(
                prompt = storePrompt,
                closePosition = close.position,
                onTap = {
                    // Surface the click to the publisher first (parity with the WebView CTA's
                    // onClicked), then the durable click beacon — only on a real user tap.
                    // openDestination is reused by auto_store_redirect (no tap), so the click
                    // signal lives here on the badge, not in openDestination.
                    presentation.callbacks.onClicked()
                    AdBeaconManager.enqueue(ad.impressionId, "click", adFormat = "interstitial")
                    recordStoreOpen("store_prompt")
                    openDestination(ad)
                },
                rowHeight = MIN_TOUCH_TARGET_DP.dp,
            )
        }

        // Play Install Prompt banner — independent install affordance, pinned to the bottom.
        if (skoverlay != null && skoverlay.enabled && installBannerVisible) {
            PlayInstallBanner(
                config = skoverlay,
                onTap = {
                    // A user tap on the install banner opens the primary ad's store — surface the click
                    // (parity with the store-prompt badge / creative CTA) plus the durable click beacon.
                    presentation.callbacks.onClicked()
                    AdBeaconManager.enqueue(ad.impressionId, "click", adFormat = "interstitial")
                    recordStoreOpen("store_prompt")
                    openDestination(ad)
                },
                onDismiss = { installBannerVisible = false },
            )
        }

        // Persistent ad-info "i" + report sheet (required disclosure). Last so its sheet overlays.
        AdInfoReportOverlay(
            adId = ad.impressionId,
            apiKey = presentation.apiKey,
            // A genuine bottom-left ✕ shares the bottom-left corner with the "i" (shrink its hit area);
            // a progress_bar bottom ✕ relocates to top-right, leaving the "i" its full hit area.
            closeAtBottomLeft = close.position == ClosePosition.BOTTOM_LEFT && !barAtBottom,
        )
    }
}

/**
 * Full-screen HTML creative. The server-rendered `rendered_html` owns its own CTA:
 * a user-initiated link navigation (gesture) is intercepted, reported as CLICKED via
 * [onAdClick], and routed to the advertiser destination through [CreativeCtaRouter].
 * Non-gesture navigations (impression pixels, JS/meta auto-redirects) load normally
 * so they can't fake a click. The interstitial is NOT dismissed on click — the close
 * button drives dismissal.
 */
@Composable
private fun CreativeHtml(
    html: String,
    destination: String,
    bridge: CreativeBridge,
    onAdClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // applicationContext so the store/browser open survives if the interstitial is
    // later dismissed.
    val appContext = LocalContext.current.applicationContext
    var creativeWebView by remember { mutableStateOf<WebView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Suspend the creative's JS/timers/video while the host is backgrounded — AndroidView won't pause
    // a WebView on its own, so an interstitial left open behind the home screen would keep running.
    // Resume when the host returns to the foreground. (The native-ad path pauses off-screen views too.)
    DisposableEffect(lifecycleOwner, creativeWebView) {
        val wv = creativeWebView
        // Track a real background (ON_STOP) so the repaint below fires only when the window actually
        // lost its drawing surface — not on an incidental ON_PAUSE (a dialog / permission sheet over a
        // still-visible Activity), which would cause a one-frame INVISIBLE flicker.
        var wasStopped = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> wv?.onPause()
                Lifecycle.Event.ON_STOP -> wasStopped = true
                Lifecycle.Event.ON_RESUME -> {
                    wv?.onResume()
                    if (wasStopped) {
                        wasStopped = false
                        // A hardware-accelerated WebView drops its draw functor on background; force the
                        // visibility transition that recreates it, else the creative returns black/blank.
                        wv?.repaintOnNextFrame()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { ctx ->
            WebViewPool.acquire(
                context = ctx,
                client = object : CreativeTelemetryWebViewClient("interstitial") {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon) // starts the page-load timer
                        // Bridge relay fallback when document-start injection is unavailable.
                        if (!BridgeWebViewInstaller.documentStartSupported() && url != "about:blank") {
                            view?.evaluateJavascript(BridgeWebViewInstaller.relayScript, null)
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        // Only a user gesture counts as the ad click-through; pixels
                        // and auto-redirects (no gesture) navigate normally.
                        if (!request.hasGesture()) return false
                        onAdClick() // CLICKED
                        CreativeCtaRouter.open(appContext, url, destination)
                        return true
                    }
                },
            ).apply {
                webChromeClient = CreativeTelemetryWebChromeClient("interstitial") // capture JS console errors
                BridgeWebViewInstaller.install(this, bridge)
                // Self-contained creative: asset URLs are absolute (baseURL = null).
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                creativeWebView = this
            }
        },
        modifier = modifier,
        onRelease = { webView ->
            BridgeWebViewInstaller.uninstall(webView)
            WebViewPool.release(webView)
        },
    )
}

// ── Ad-behavior close button ──────────────────────────────────────────────────

// Visible close affordance sized to a compact ~22dp circle; the tappable area stays
// at the 48dp Material minimum (see [MIN_TOUCH_TARGET_DP]), close to IAB MRAID's 50×50dp close
// region. The visible graphic and the touch target are deliberately decoupled.
private const val CLOSE_GLYPH_SP = 10
private const val CLOSE_BOX_DP = 16
// Shared with the rewarded minigame so its store prompt centers in the same touch-target band.
internal const val MIN_TOUCH_TARGET_DP = 48
// Height of the `progress_bar` gate bar.
private const val CLOSE_PROGRESS_BAR_HEIGHT_DP = 4
// When the gate bar sits at the bottom (progress_bar at bottom_left), raise it this far above the safe
// edge so it clears the bottom-left info "i" (a 16dp circle inset 6dp from the corner ≈ 22dp tall).
private const val CLOSE_BOTTOM_BAR_LIFT_DP = 26

/**
 * True for the `progress_bar` treatment pinned to `bottom_left`: the gate bar then spans the bottom,
 * so the ✕ moves up to the top-right (it can't sit on the bar) and the bar itself is raised to sit
 * just above the info "i" (which keeps its corner spot). For every OTHER bottom_left close the ✕ stays
 * bottom-left. (The store prompt sits top-right for any bottom_left close — see [StorePromptBadge].)
 */
internal fun closeBarAtBottom(treatment: CloseTreatment, position: ClosePosition): Boolean =
    treatment == CloseTreatment.PROGRESS_BAR && position == ClosePosition.BOTTOM_LEFT

/**
 * The `ad_behavior`-driven close button. Renders the assigned [treatment] at the configured corner:
 * `HIDDEN` shows nothing until the gate unlocks, `COUNTDOWN_CIRCLE` draws a ring, `PROGRESS_BAR` a
 * top-edge bar, `REWARD_OR_CLOSE_LABEL` a counting-down text pill. [progressBarColor] tints the
 * ring/bar fill. The label copy is reward- vs interstitial-aware via [isRewardCopy].
 */
@Composable
internal fun BoxScope.AdCloseButton(
    treatment: CloseTreatment,
    position: ClosePosition,
    progressBarColor: String,
    isRewardCopy: Boolean,
    enabled: Boolean,
    remaining: Int,
    progress: Float,
    onClose: () -> Unit,
) {
    val tint = remember(progressBarColor) { ColorUtil.parseColor(progressBarColor) }
    // [progress] is already a continuous, frame-clock-driven fill: the caller animates its backing
    // Animatable to 1f over the remaining foreground gate time, so it advances every frame on its own.
    // Use it directly — a second animateFloatAsState pass here would only re-introduce lag (smoothing
    // an already-smooth value) and was the source of the stepping/jank on slower devices. (The
    // countdown *number* still steps per second — it can't show fractions — only the bar/ring fills.)
    val animatedProgress = progress.coerceIn(0f, 1f)
    // The ✕ honors its configured corner, EXCEPT progress_bar at bottom_left: the gate bar takes the
    // bottom edge there, so the ✕ moves up to the top-right. (The mid-ad store prompt sits top-right
    // for any bottom_left close — diagonally opposite a bottom-left ✕, or sharing the top-right with
    // the relocated one.)
    val barAtBottom = closeBarAtBottom(treatment, position)
    val alignment = when {
        barAtBottom -> Alignment.TopEnd
        else -> when (position) {
            ClosePosition.TOP_RIGHT -> Alignment.TopEnd
            ClosePosition.TOP_LEFT -> Alignment.TopStart
            ClosePosition.BOTTOM_LEFT -> Alignment.BottomStart
        }
    }

    // `progress_bar`: a full-width bar tinted by color, shown during the delay. Pinned just inside the
    // top safe-area inset by default (clearing the notch); at bottom_left it sits near the bottom,
    // raised just above the info "i" so the two don't overlap (the "i" keeps its corner spot).
    if (!enabled && treatment == CloseTreatment.PROGRESS_BAR) {
        Box(
            modifier = Modifier
                .align(if (barAtBottom) Alignment.BottomCenter else Alignment.TopCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        if (barAtBottom) WindowInsetsSides.Bottom else WindowInsetsSides.Top,
                    ),
                )
                .padding(bottom = if (barAtBottom) CLOSE_BOTTOM_BAR_LIFT_DP.dp else 0.dp)
                .fillMaxWidth()
                .height(CLOSE_PROGRESS_BAR_HEIGHT_DP.dp)
                .background(Color(0x40FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .height(CLOSE_PROGRESS_BAR_HEIGHT_DP.dp)
                    .background(tint),
            )
        }
    }

    // The button (or its in-delay indicator), pinned to the configured corner with a tight 8dp inset
    // so it sits close to the edge; each corner (incl. bottom-left) honored.
    Box(
        modifier = Modifier
            .align(alignment)
            // safeDrawing merges system bars + display cutout so a top-corner button never
            // lands under a notch (system bars are hidden, so navigationBars alone gave 0 at top).
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // A genuine bottom-left ✕ nudges right to clear the always-present info "i" beside it; the
            // relocated (top-right) ✕ of the progress_bar bottom layout doesn't need it.
            .padding(start = if (position == ClosePosition.BOTTOM_LEFT && !barAtBottom) 2.dp else 0.dp)
            .padding(8.dp),
    ) {
        // All close states share one centerline: center within the touch-target height so the gated
        // "… in N" pill / countdown ring and the unlocked ✕ line up with the store badge (opposite
        // corner) and the glyph doesn't jump when the close unlocks.
        Box(
            modifier = Modifier.height(MIN_TOUCH_TARGET_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // Unlocked: the compact ✕ for every treatment (matches all other close buttons).
                enabled -> CloseCircle(onClick = onClose) { CloseGlyph() }
                // Nothing in the corner during the delay (the bar shows progress separately).
                treatment == CloseTreatment.HIDDEN || treatment == CloseTreatment.PROGRESS_BAR -> Unit
                treatment == CloseTreatment.REWARD_OR_CLOSE_LABEL ->
                    LabelPill(text = "${if (isRewardCopy) "Reward" else "Close"} in ${remaining.coerceAtLeast(0)}")
                treatment == CloseTreatment.COUNTDOWN_CIRCLE -> {
                    // Same footprint as the unlocked button so the glyph doesn't jump when it activates.
                    Box(
                        modifier = Modifier.size(maxOf(MIN_TOUCH_TARGET_DP, CLOSE_BOX_DP).dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CloseCircle(alpha = 0.5f) { CloseGlyph() }
                        Canvas(modifier = Modifier.size(CLOSE_BOX_DP.dp)) {
                            // Stroke in dp (not raw px, which was ~1dp on a 3x screen), inset by half
                            // its width so the ring isn't drawn half-outside the canvas bounds.
                            // 2dp matches the Swift SDK's ring stroke.
                            val stroke = 2.dp.toPx()
                            drawArc(
                                color = tint,
                                startAngle = -90f,
                                sweepAngle = 360f * animatedProgress,
                                useCenter = false,
                                topLeft = Offset(stroke / 2f, stroke / 2f),
                                size = Size(size.width - stroke, size.height - stroke),
                                style = Stroke(width = stroke, cap = StrokeCap.Round),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** White circular control at the standard size; tappable only when [onClick] is non-null. When
 * tappable, the hit area is expanded to at least [MIN_TOUCH_TARGET_DP] around the circle. */
@Composable
private fun CloseCircle(
    alpha: Float = 1f,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val circle = Modifier
        .size(CLOSE_BOX_DP.dp)
        .clip(CircleShape)
        // Gray / translucent dark circle rather than opaque white.
        .background(Color.Black.copy(alpha = 0.5f * alpha))
    if (onClick != null) {
        Box(
            modifier = Modifier
                .size(maxOf(MIN_TOUCH_TARGET_DP, CLOSE_BOX_DP).dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(circle, contentAlignment = Alignment.Center) { content() }
        }
    } else {
        Box(circle, contentAlignment = Alignment.Center) { content() }
    }
}

/** The "✕" glyph at the standard point size — white, to sit on the dark/translucent circle. */
@Composable
private fun CloseGlyph() {
    Text(
        text = "✕",
        color = Color.White,
        fontSize = CLOSE_GLYPH_SP.sp,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * The text pill used by the `reward_or_close_label` treatment (counting down, then "Close").
 * Compact, to match the small close chrome of the other treatments.
 */
@Composable
private fun LabelPill(text: String, onClick: (() -> Unit)? = null) {
    val base = Modifier
        .clip(RoundedCornerShape(12.dp))
        .background(Color.Black.copy(alpha = 0.5f))
    val mod = if (onClick != null) {
        base.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    } else {
        base
    }
    Box(mod.padding(horizontal = 10.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Mid-ad store prompt ─────────────────────────────────────────────────────────

/**
 * The mid-ad store prompt (`store_prompt`): a tappable skip-next ▶| badge labelled "App Store" / "Google Play"
 * rendered at the server-resolved corner. The SDK never recomputes the position — it trusts the
 * backend's collision resolution (opposite the close button). Shared with the rewarded minigame
 * ([SimulaRewardedActivity]).
 */
@Composable
internal fun BoxScope.StorePromptBadge(
    prompt: StorePrompt,
    // The close button's CONFIG corner. The badge sits top-right for a bottom_left close (diagonally
    // opposite a bottom-left ✕, or sharing the corner with a progress_bar bottom ✕ that relocated
    // there), and in the horizontal mirror of the close corner otherwise (top-right ↔ top-left). The
    // server's `store_prompt.position` is not used for layout.
    closePosition: ClosePosition,
    onTap: () -> Unit,
    // Inset from the safe-area edge. Both the interstitial and the rewarded minigame use 8dp so the
    // badge shares its close affordance's baseline.
    edgePadding: Dp = 8.dp,
    // When set, the pill is vertically centered within this height so it lines up with a close button
    // whose glyph sits in a touch-target band (the interstitial). null → bare pill (rewarded).
    rowHeight: Dp? = null,
) {
    val label = if (prompt.platform == StorePromptPlatform.IOS) "App Store" else "Google Play"
    val alignment = when (closePosition) {
        // Mirror of a top-right close.
        ClosePosition.TOP_RIGHT -> Alignment.TopStart
        // top_left mirrors to top-right; bottom_left relocates to top-right (same corner as the ✕).
        else -> Alignment.TopEnd
    }
    Box(
        modifier = Modifier
            .align(alignment)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(edgePadding)
            .then(if (rowHeight != null) Modifier.height(rowHeight) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        // Compact pill: a filled skip-next glyph then the store name, with tight
        // padding, a fully-rounded (capsule) outline, and a small gap between the two.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(Color(0x99000000))
                .clickable(onClick = onTap)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SkipNextIcon(size = 7.dp, color = Color.White)
            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The filled "skip-next" glyph (▶|) — a right-pointing triangle with a trailing vertical bar,
 * matching the Material `skip_next` icon. Drawn as a vector
 * path so it renders crisply at any [size] without a Material-icons font dependency.
 */
@Composable
private fun SkipNextIcon(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        // Material skip_next geometry, normalized to a 12×12 content box (the icon's drawn region
        // inside the standard 24×24 viewport): triangle x0→8.5, gap, bar x10→12, both full-height.
        val u = this.size.minDimension / 12f
        drawPath(
            path = Path().apply {
                moveTo(0f, 0f)
                lineTo(8.5f * u, 6f * u)
                lineTo(0f, 12f * u)
                close()
            },
            color = color,
        )
        drawRect(
            color = color,
            topLeft = Offset(10f * u, 0f),
            size = Size(2f * u, 12f * u),
        )
    }
}

// ── Play Install Prompt banner ──────────────────────────────────────────────────

/**
 * The Play Install Prompt (`skoverlay`): an SDK-presented bottom install banner, independent of the
 * creative click handler. Android has no first-party SKOverlay analog, so this is a custom Play-style
 * banner that launches the Play install flow (via [CreativeCtaRouter]) on tap. `BOTTOM_RAISED` floats
 * it higher above the CTA than `BOTTOM`.
 */
@Composable
private fun BoxScope.PlayInstallBanner(
    config: SkOverlayConfig,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp)
            // Sit a little lower / closer to the bottom edge (BOTTOM_RAISED still floats higher).
            .padding(bottom = if (config.position == OverlayPosition.BOTTOM_RAISED) 120.dp else 60.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF01875F)),
            contentAlignment = Alignment.Center,
        ) {
            Text("▶", color = Color.White, fontSize = 16.sp)
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text("Google Play", color = Color(0xFF5F6368), fontSize = 12.sp)
            Text(
                "Install the app",
                color = Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Button(
            onClick = onTap,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF01875F),
                contentColor = Color.White,
            ),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text("GET", fontWeight = FontWeight.Bold)
        }
        if (config.dismissible) {
            Text(
                "✕",
                color = Color(0xFF9CA3AF),
                fontSize = 16.sp,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable(onClick = onDismiss),
            )
        }
    }
}
