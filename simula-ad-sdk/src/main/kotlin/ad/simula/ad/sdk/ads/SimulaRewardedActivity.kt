package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.bridge.BridgeWebViewInstaller
import ad.simula.ad.sdk.bridge.androidCreativeBridge
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
                FallbackAdHost(impressionId = p.impressionId, onFullyClosed = ::finishAd) { onClose ->
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
    // Earned immediately when there is no gate; otherwise resolved by the timer below.
    // A gate that already elapsed in a prior Activity instance (config-change recreation)
    // also starts earned — accumulated play time survives on the presentation.
    var rewardEarned by remember {
        mutableStateOf(
            presentation.durationSeconds <= 0 ||
                presentation.rewardEarned ||
                RewardGate.isEarned(presentation.accumulatedPlayTimeMs, presentation.durationSeconds),
        )
    }
    var secondsLeft by remember {
        // Resume from already-accrued play time (config-change recovery), not full duration.
        mutableStateOf(RewardGate.secondsLeft(presentation.accumulatedPlayTimeMs, presentation.durationSeconds))
    }

    // Mid-ad store prompt — shown from half the play-to-earn duration until the reward unlocks.
    // Initialized true on a config-change recreation that resumes past the halfway mark.
    val storePrompt = presentation.adBehavior?.storePrompt
    var storePromptVisible by remember {
        mutableStateOf(
            storePrompt != null && storePrompt.enabled &&
                presentation.durationSeconds > 0 &&
                presentation.accumulatedPlayTimeMs >= presentation.durationSeconds * 1000L / 2,
        )
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // WebView ↔ SDK bridge (PRD §3). AD_EARLY_COMPLETE (e.g. survey finished) grants the reward and
    // reveals the close button immediately, bypassing the play timer.
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
        if (presentation.durationSeconds <= 0) {
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
                secondsLeft = RewardGate.secondsLeft(presentation.accumulatedPlayTimeMs, presentation.durationSeconds)
                // Reveal the store prompt at the halfway point to the reward (mid play-to-earn).
                if (presentation.accumulatedPlayTimeMs >= presentation.durationSeconds * 1000L / 2) {
                    storePromptVisible = true
                }
                if (RewardGate.isEarned(presentation.accumulatedPlayTimeMs, presentation.durationSeconds)) {
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
                    loadUrl(url)
                }
            },
            // Sits below the safe area (the black Box fills the cutout / nav-bar region).
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            onRelease = { webView ->
                BridgeWebViewInstaller.uninstall(webView)
                WebViewPool.release(webView)
            },
        )

        // Top-right reward/close pill: a "Play to earn" countdown while earning (display-only —
        // no early exit), which becomes the close button ("✕ Reward unlocked") once earned.
        RewardClosePill(
            rewardEarned = rewardEarned,
            secondsLeft = secondsLeft,
            onClose = { onFinish(true) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp),
        )

        // Mid-ad store prompt — appears at half the play-to-earn duration and is removed the instant
        // the reward unlocks (the reward/close pill takes over). Rendered at the server-resolved
        // corner (verbatim); a tap routes to the advertised store via the shared CTA router.
        if (storePrompt != null && storePrompt.enabled && storePromptVisible && !rewardEarned) {
            StorePromptBadge(
                prompt = storePrompt,
                // Match the reward/close pill's 8dp inset so both share the same top baseline.
                edgePadding = 8.dp,
                onTap = {
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
        AdInfoReportOverlay(adId = presentation.impressionId, apiKey = presentation.apiKey)
    }
}

@Composable
private fun RewardClosePill(
    rewardEarned: Boolean,
    secondsLeft: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rewardEarned) {
        // Earned: a compact circular X close button (AppLovin-style); tapping it dismisses.
        Box(
            modifier = modifier
                .size(44.dp)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    } else {
        // Still earning: a small display-only status — no close affordance yet.
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x99000000))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = "Play to earn: ${secondsLeft}s",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
