package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.bridge.BridgeWebViewInstaller
import ad.simula.ad.sdk.bridge.androidCreativeBridge
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.model.AutoStoreRedirectTrigger
import ad.simula.ad.sdk.model.CloseBehavior
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.provider.ProvideSimulaContext
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Transparent, full-screen host for the imperative rewarded minigame. Reads its
 * [RewardedPresentation] from [RewardedHandoff] by token and renders the playable
 * iframe in a pooled WebView with a play-to-earn status pill and an always-available
 * close button. Mirrors [SimulaInterstitialActivity].
 */
internal class SimulaRewardedActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TOKEN = "ad.simula.ad.sdk.REWARDED_TOKEN"
    }

    private var presentation: RewardedPresentation? = null
    private var token: String? = null
    private var closed = false
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        token = intent?.getStringExtra(EXTRA_TOKEN)
        // Non-destructive read so the presentation survives Activity recreation.
        val p = token?.let { RewardedHandoff.get(it) }
        if (p == null) {
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
                // On close, fetch + show a fallback ad before finishing (minigame parity). CLOSE is
                // reported when the minigame closes; the Activity finishes after the fallback.
                FallbackAdHost(
                    impressionId = p.impressionId,
                    onFullyClosed = ::completeReward,
                    autoStoreRedirect = p.adBehavior?.autoStoreRedirect,
                    // END_SCREEN_N opens the primary ad's store (the same path as a CTA / PLAYABLE_END).
                    onAutoStoreRedirect = {
                        CreativeCtaRouter.open(
                            applicationContext,
                            p.trackingUrl,
                            p.destination,
                            p.adBehavior?.storeOpen,
                        )
                    },
                ) { onClose ->
                    RewardedMinigame(
                        presentation = p,
                        onFinish = { earned ->
                            reportClosed(earned)
                            onClose()
                        },
                    )
                }
            }
        }
    }

    /** Fire CLOSE (with reward state + measured play time) exactly once when the minigame closes.
     * Does NOT finish — the fallback-ad host finishes via [finishAd] once any fallback ad is done. */
    private fun reportClosed(earned: Boolean) {
        if (closed) return
        closed = true
        presentation?.let { p -> p.callbacks.onClose(earned, elapsedSeconds(p)) }
    }

    /**
     * The user has dismissed every screen (playable + all fallback ad screens) — the unit is fully
     * complete. Fire reward completion (the earned-reward signal + server verification) exactly once,
     * then tear the Activity down. Deferred to here so closing the playable alone doesn't verify the
     * reward; with no fallback screens, [FallbackAdHost] calls this immediately on close.
     */
    private fun completeReward() {
        if (!completed) {
            completed = true
            presentation?.let { p -> p.callbacks.onRewardCompleted(p.rewardEarned, elapsedSeconds(p)) }
        }
        finishAd()
    }

    /** Tear the Activity down (after the optional fallback ad). */
    private fun finishAd() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only act when finishing for good; on a config-change recreation we keep the
        // handoff so the new instance can read it and must NOT report CLOSE.
        if (isFinishing) {
            token?.let { RewardedHandoff.remove(it) }
            if (!closed) {
                closed = true
                presentation?.let { p -> p.callbacks.onClose(p.rewardEarned, elapsedSeconds(p)) }
            }
        }
    }

    private fun elapsedSeconds(p: RewardedPresentation): Double {
        return p.accumulatedPlayTimeMs / 1000.0
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

@Composable
private fun RewardedMinigame(
    presentation: RewardedPresentation,
    onFinish: (earned: Boolean) -> Unit,
) {
    // Play-to-earn gate length, in seconds — sourced from `ad_behavior.close.delay_seconds` (the
    // same value that ungates the close button). No `ad_behavior` → 0 → instantly earned.
    val gateSeconds = presentation.adBehavior?.close?.delaySeconds ?: 0

    // Earned immediately when there is no gate; otherwise resolved by the timer below.
    // A gate that already elapsed in a prior Activity instance (config-change recreation)
    // also starts earned — accumulated play time survives on the presentation.
    var rewardEarned by remember {
        mutableStateOf(
            gateSeconds <= 0 ||
                presentation.rewardEarned ||
                RewardGate.isEarned(presentation.accumulatedPlayTimeMs, gateSeconds),
        )
    }
    var secondsLeft by remember {
        // Resume from already-accrued play time (config-change recovery), not the full gate.
        mutableStateOf(RewardGate.secondsLeft(presentation.accumulatedPlayTimeMs, gateSeconds))
    }
    // 0→1 fill for the close treatment (progress bar / countdown ring), from play-to-earn progress.
    var closeProgress by remember {
        mutableStateOf(rewardCloseProgress(presentation.accumulatedPlayTimeMs, gateSeconds))
    }

    // Mid-ad store prompt — shown from half the play-to-earn gate until the reward unlocks.
    // Initialized true on a config-change recreation that resumes past the halfway mark.
    val storePrompt = presentation.adBehavior?.storePrompt
    var storePromptVisible by remember {
        mutableStateOf(
            storePrompt != null && storePrompt.enabled &&
                gateSeconds > 0 &&
                presentation.accumulatedPlayTimeMs >= gateSeconds * 1000L / 2,
        )
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // WebView ↔ SDK bridge (PRD §3). AD_EARLY_COMPLETE (e.g. survey finished) grants the reward and
    // reveals the close button immediately, bypassing the play timer.
    val autoRedirect = presentation.adBehavior?.autoStoreRedirect
    var autoRedirectFired by remember { mutableStateOf(false) }
    // auto_store_redirect: open the advertiser store once (no user tap). A disabled/missing config no-ops.
    fun fireAutoStoreRedirect() {
        if (!autoRedirectFired) {
            autoRedirectFired = true
            CreativeCtaRouter.open(
                context.applicationContext,
                presentation.trackingUrl,
                presentation.destination,
                presentation.adBehavior?.storeOpen,
            )
        }
    }
    val bridge = remember {
        androidCreativeBridge(
            appContext = context.applicationContext,
            activityProvider = { context as? Activity },
            onEarlyComplete = {
                presentation.rewardEarned = true
                rewardEarned = true
            },
        )
    }

    // PLAYABLE_END — open the store the moment the close button appears (here, when the reward is
    // earned and the reward/close pill becomes a close button). SDK-native, no bridge.
    if (autoRedirect?.enabled == true && autoRedirect.trigger == AutoStoreRedirectTrigger.PLAYABLE_END) {
        LaunchedEffect(rewardEarned) {
            if (rewardEarned) fireAutoStoreRedirect()
        }
    }

    // DISPLAYED + impression fire once the creative first composes. Guarded so an
    // Activity recreation doesn't double-report either.
    LaunchedEffect(Unit) {
        if (!presentation.displayedReported) {
            presentation.displayedReported = true
            presentation.callbacks.onDisplayed()
            if (presentation.impressionId.isNotBlank()) {
                SimulaScope.launch { SimulaApiClient.trackImpression(presentation.impressionId, presentation.apiKey) }
            }
        }
    }

    // Foreground-only play gate. Time accrues only while the Activity is RESUMED:
    // repeatOnLifecycle cancels the ticking loop when the app is backgrounded and
    // restarts it on return, so the gate can't be satisfied by simply backgrounding the
    // app for the required duration. The accumulated time lives on the presentation, so
    // a config change (rotation) resumes the remaining time instead of restarting it.
    LaunchedEffect(Unit) {
        if (gateSeconds <= 0) {
            presentation.rewardEarned = true
            rewardEarned = true
            return@LaunchedEffect
        }
        if (presentation.rewardEarned) {
            rewardEarned = true
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // A re-run after the reward was already earned (background → resume) must not
            // keep accruing time.
            if (presentation.rewardEarned) return@repeatOnLifecycle
            // Re-anchor on each resume so the backgrounded interval is never counted.
            var lastTickMs = SystemClock.elapsedRealtime()
            while (true) {
                delay(250L)
                val now = SystemClock.elapsedRealtime()
                presentation.accumulatedPlayTimeMs += now - lastTickMs
                lastTickMs = now
                secondsLeft = RewardGate.secondsLeft(presentation.accumulatedPlayTimeMs, gateSeconds)
                closeProgress = rewardCloseProgress(presentation.accumulatedPlayTimeMs, gateSeconds)
                // Reveal the store prompt at the halfway point to the reward (mid play-to-earn).
                if (presentation.accumulatedPlayTimeMs >= gateSeconds * 1000L / 2) {
                    storePromptVisible = true
                }
                if (RewardGate.isEarned(presentation.accumulatedPlayTimeMs, gateSeconds)) {
                    presentation.rewardEarned = true
                    rewardEarned = true
                    break
                }
            }
        }
    }

    // No early exit: Back does nothing until the reward is earned, then it closes (earned).
    BackHandler(enabled = true) { if (rewardEarned) onFinish(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                val url = presentation.iframeUrl
                val html = presentation.renderedHtml
                WebViewPool.acquire(
                    context = ctx,
                    client = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                            // Bridge relay fallback when document-start injection is unavailable.
                            if (!BridgeWebViewInstaller.documentStartSupported() && pageUrl != "about:blank") {
                                view?.evaluateJavascript(BridgeWebViewInstaller.relayScript, null)
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val requestUrl = request?.url?.toString() ?: return false
                            if (requestUrl == url) return false
                            // Allow same-origin navigation; open external links externally.
                            if (Uri.parse(url).host == Uri.parse(requestUrl).host) return false
                            return try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(requestUrl)))
                                true
                            } catch (e: Exception) {
                                false
                            }
                        }
                    },
                ).apply {
                    BridgeWebViewInstaller.install(this, bridge)
                    // Prefer the server-rendered HTML when present (parity with the interstitial,
                    // which fills the surface); fall back to the iframe URL.
                    if (html.isNotBlank()) {
                        loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    } else {
                        loadUrl(url)
                    }
                }
            },
            // The game canvas fills edge-to-edge: inset only vertically (status / nav / top
            // notch) and let it draw under any horizontal display-cutout. In landscape on a
            // device with a side cutout, padding the cutout in would expose the transparent
            // WebView's black backing as left/right "black bars" around the game. The overlay
            // controls below keep the full safeDrawing inset so they never sit under a cutout.
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
            onRelease = { webView ->
                BridgeWebViewInstaller.uninstall(webView)
                WebViewPool.release(webView)
            },
        )

        // Close button — honors the server `ad_behavior.close` treatment (hidden / countdown ring /
        // progress bar / reward-or-close label) exactly like the interstitial, but gated on the
        // play-to-earn progress: the ✕ unlocks only once the reward is earned.
        val close = presentation.adBehavior?.close ?: CloseBehavior()
        AdCloseButton(
            treatment = close.treatment,
            position = close.position,
            progressBarColor = close.progressBarColor,
            isRewardCopy = true,
            enabled = rewardEarned,
            remaining = secondsLeft,
            progress = closeProgress,
            onClose = { onFinish(true) },
        )

        // Mid-ad store prompt — appears at half the play-to-earn gate and is removed the instant the
        // reward unlocks (the reward/close pill takes over). Pinned to the corner opposite the
        // reward/close pill (the SDK mirrors the close position); a tap routes to the advertised store.
        if (storePrompt != null && storePrompt.enabled && storePromptVisible && !rewardEarned) {
            StorePromptBadge(
                prompt = storePrompt,
                closePosition = close.position,
                // Match the reward/close pill's 8dp inset and center the badge in the same 48dp
                // touch-target band so the two share one centerline (parity with the interstitial).
                edgePadding = 8.dp,
                rowHeight = MIN_TOUCH_TARGET_DP.dp,
                onTap = {
                    // Mid-store-prompt click beacon — only on a real user tap (not auto_store_redirect).
                    if (presentation.impressionId.isNotBlank()) {
                        SimulaScope.launch { SimulaApiClient.trackClick(presentation.impressionId, presentation.apiKey) }
                    }
                    CreativeCtaRouter.open(
                        context.applicationContext,
                        presentation.trackingUrl,
                        presentation.destination,
                        presentation.adBehavior?.storeOpen,
                    )
                },
            )
        }

        // Persistent ad-info "i" + report sheet (required disclosure). Last so its sheet overlays.
        AdInfoReportOverlay(
            adId = presentation.impressionId,
            apiKey = presentation.apiKey,
            // A genuine bottom-left ✕ shares the bottom-left corner with the "i" (shrink its hit area);
            // a progress_bar bottom ✕ relocates to top-right, leaving the "i" its full hit area.
            closeAtBottomLeft = close.position == ClosePosition.BOTTOM_LEFT && !closeBarAtBottom(close.treatment, close.position),
        )
    }
}

/** 0→1 close-treatment fill from play-to-earn progress (foreground play time / required duration). */
private fun rewardCloseProgress(playMs: Long, durationSeconds: Int): Float =
    if (durationSeconds > 0) (playMs.toFloat() / (durationSeconds * 1000f)).coerceIn(0f, 1f) else 1f
