package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.bridge.BridgeWebViewInstaller
import ad.simula.ad.sdk.bridge.CreativeBridge
import ad.simula.ad.sdk.bridge.androidCreativeBridge
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
                    // END_SCREEN_N opens the primary ad's store (the same path as a CTA / PLAYABLE_END).
                    onAutoStoreRedirect = {
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
        presentation?.callbacks?.onClosed()
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

/** True if the close gate was already started in a prior Activity instance (config-change
 * recreation) and its wall-clock window has elapsed. Anchored so rotation can't reset the dwell. */
private fun gateAlreadyElapsed(p: InterstitialPresentation, total: Duration): Boolean =
    p.gateStartedAtMs != 0L &&
        (SystemClock.elapsedRealtime() - p.gateStartedAtMs).milliseconds >= total

@Composable
private fun CreativeInterstitial(
    presentation: InterstitialPresentation,
    onFinish: () -> Unit,
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

    // WebView ↔ SDK bridge (PRD §3). AD_EARLY_COMPLETE unlocks the close button immediately,
    // bypassing the close-delay gate.
    val context = LocalContext.current
    // auto_store_redirect: open the advertiser store once (no user tap). PLAYABLE_END fires when the
    // close button appears (below); END_SCREEN_1/2_OPEN fire when the creative navigates to the
    // matching end-screen marker (handled in the WebView client). A disabled/missing config no-ops.
    val autoRedirect = behavior?.autoStoreRedirect
    var autoRedirectFired by remember { mutableStateOf(false) }
    fun fireAutoStoreRedirect() {
        if (!autoRedirectFired) {
            autoRedirectFired = true
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
        LaunchedEffect(Unit) {
            // Anchor the dwell to wall-clock on first run so a config-change recreation resumes the
            // remaining time instead of restarting it.
            if (presentation.gateStartedAtMs == 0L) {
                presentation.gateStartedAtMs = SystemClock.elapsedRealtime()
            }
            val elapsed = (SystemClock.elapsedRealtime() - presentation.gateStartedAtMs).milliseconds
            val remaining = gateTotal - elapsed
            if (remaining.isPositive()) {
                when (treatment) {
                    // Ring / bar fill linearly over the remaining time (the animation IS the wait).
                    CloseTreatment.COUNTDOWN_CIRCLE, CloseTreatment.PROGRESS_BAR -> {
                        val start = (elapsed / gateTotal).toFloat().coerceIn(0f, 1f)
                        closeProgress.snapTo(start)
                        closeProgress.animateTo(
                            1f,
                            tween(remaining.inWholeMilliseconds.toInt(), easing = LinearEasing),
                        )
                    }
                    // Tick the visible seconds down once per second.
                    CloseTreatment.REWARD_OR_CLOSE_LABEL -> {
                        var left = ceil(remaining.toDouble(DurationUnit.SECONDS)).toInt()
                        closeRemaining = left
                        while (left > 0) {
                            delay(1000)
                            left -= 1
                            closeRemaining = left
                        }
                    }
                    else -> delay(remaining)
                }
            }
            closeEnabled = true
        }
    }

    // Mid-ad store prompt (`store_prompt`) — an early install affordance shown at the halfway point
    // to the close button (`closeTime / 2`, where closeTime is the server-driven close delay) and
    // removed the instant the real close button appears (see `!closeEnabled` at the render site).
    // Independent of close chrome + skoverlay. When the close is immediately available (no gate)
    // there is no pre-close window, so it never shows.
    val storePrompt = behavior?.storePrompt
    var storePromptVisible by remember { mutableStateOf(false) }
    if (storePrompt != null && storePrompt.enabled && gateTotal > Duration.ZERO) {
        LaunchedEffect(Unit) {
            // Anchor to the same wall-clock start as the close gate so a config-change recreation
            // resumes the half-way reveal rather than restarting it.
            if (presentation.gateStartedAtMs == 0L) {
                presentation.gateStartedAtMs = SystemClock.elapsedRealtime()
            }
            val elapsed = (SystemClock.elapsedRealtime() - presentation.gateStartedAtMs).milliseconds
            val remaining = gateTotal / 2 - elapsed
            if (remaining.isPositive()) delay(remaining)
            storePromptVisible = true
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

    // DISPLAYED + impression fire once the creative first composes. Guarded so an
    // Activity recreation (config change) doesn't double-report either.
    LaunchedEffect(Unit) {
        if (!presentation.displayedReported) {
            presentation.displayedReported = true
            presentation.callbacks.onDisplayed()
            // FIX M2: skip impression when there is no impression id.
            if (ad.impressionId.isNotBlank()) {
                SimulaScope.launch {
                    SimulaApiClient.trackImpression(ad.impressionId, presentation.apiKey, ad.experiment)
                }
            }
        }
    }

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

        // Close button — always shown with the compact AppLovin-style chrome. Driven by
        // `ad_behavior.close` when present; otherwise a default (top-right, always available) so ads
        // with no `ad_behavior` still get the small close, not a big one.
        val close = behavior?.close ?: CloseBehavior()
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
                onTap = { openDestination(ad) },
                rowHeight = MIN_TOUCH_TARGET_DP.dp,
            )
        }

        // Play Install Prompt banner — independent install affordance, pinned to the bottom.
        if (skoverlay != null && skoverlay.enabled && installBannerVisible) {
            PlayInstallBanner(
                config = skoverlay,
                onTap = { openDestination(ad) },
                onDismiss = { installBannerVisible = false },
            )
        }

        // Persistent ad-info "i" + report sheet (required disclosure). Last so its sheet overlays.
        AdInfoReportOverlay(
            adId = ad.impressionId,
            apiKey = presentation.apiKey,
            closeAtBottomLeft = (behavior?.close?.position ?: CloseBehavior().position) == ClosePosition.BOTTOM_LEFT,
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
    AndroidView(
        factory = { ctx ->
            WebViewPool.acquire(
                context = ctx,
                client = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
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
                BridgeWebViewInstaller.install(this, bridge)
                // Self-contained creative: asset URLs are absolute (baseURL = null).
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
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

// Visible close affordance sized to match AppLovin (a compact ~22dp circle); the tappable area stays
// at the 48dp Material minimum (see [MIN_TOUCH_TARGET_DP]), close to IAB MRAID's 50×50dp close
// region. The visible graphic and the touch target are deliberately decoupled.
private const val CLOSE_GLYPH_SP = 10
private const val CLOSE_BOX_DP = 16
// Shared with the rewarded minigame so its store prompt centers in the same touch-target band.
internal const val MIN_TOUCH_TARGET_DP = 48

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
    val alignment = when (position) {
        ClosePosition.TOP_RIGHT -> Alignment.TopEnd
        ClosePosition.TOP_LEFT -> Alignment.TopStart
        ClosePosition.BOTTOM_LEFT -> Alignment.BottomStart
    }

    // `progress_bar`: a full-width bar pinned just below the top safe-area inset (so it clears the
    // notch / status-bar region), shown during the delay and tinted by color. Edge-to-edge.
    if (!enabled && treatment == CloseTreatment.PROGRESS_BAR) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .fillMaxWidth()
                .height(4.dp)
                .background(Color(0x40FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(tint),
            )
        }
    }

    // The button (or its in-delay indicator), pinned to the configured corner with a tight 8dp inset
    // so it sits close to the edge (AdMob / AppLovin-style); each corner (incl. bottom-left) honored.
    Box(
        modifier = Modifier
            .align(alignment)
            // safeDrawing merges system bars + display cutout so a top-corner button never
            // lands under a notch (system bars are hidden, so navigationBars alone gave 0 at top).
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // When pinned bottom-left, nudge right just enough to clear the always-present info "i"
            // that sits tight in that corner (the "i" stays closest to the edge), so the two sit
            // snug side by side. (Tuned for the 16dp circles inside the 48dp touch frame.)
            .padding(start = if (position == ClosePosition.BOTTOM_LEFT) 2.dp else 0.dp)
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
                                sweepAngle = 360f * progress.coerceIn(0f, 1f),
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
        // Gray / translucent dark circle (AdMob / AppLovin style) rather than opaque white.
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
    // The close button's corner. The badge renders in its horizontal mirror (the opposite side) so
    // the two never share an edge; the server's `store_prompt.position` is not used for layout.
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
    // Horizontal mirror of the close corner: top-right ↔ top-left, bottom-left → bottom-right.
    val alignment = when (closePosition) {
        ClosePosition.TOP_RIGHT -> Alignment.TopStart
        ClosePosition.TOP_LEFT -> Alignment.TopEnd
        ClosePosition.BOTTOM_LEFT -> Alignment.BottomEnd
    }
    Box(
        modifier = Modifier
            .align(alignment)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(edgePadding)
            .then(if (rowHeight != null) Modifier.height(rowHeight) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        // Compact AppLovin-style pill: a filled skip-next glyph then the store name, with tight
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
 * matching the Material `skip_next` icon (and AppLovin's store-prompt badge). Drawn as a vector
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
