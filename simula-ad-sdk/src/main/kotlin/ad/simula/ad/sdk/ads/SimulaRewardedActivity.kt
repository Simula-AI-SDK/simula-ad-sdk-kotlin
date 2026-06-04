package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.CloseButton
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.provider.ProvideSimulaContext
import android.content.Intent
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
                hasPrivacyConsent = SimulaAds.hasPrivacyConsent,
            ) {
                RewardedMinigame(presentation = p, onFinish = ::closeOnce)
            }
        }
    }

    /** Fire CLOSE (with reward state + measured play time) exactly once, then finish. */
    private fun closeOnce(earned: Boolean) {
        if (closed) return
        closed = true
        presentation?.let { p -> p.callbacks.onClose(earned, elapsedSeconds(p)) }
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
        if (p.gateStartedAtMs == 0L) return 0.0
        return (SystemClock.elapsedRealtime() - p.gateStartedAtMs) / 1000.0
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

/** True if play tracking already started and the required duration has elapsed. */
private fun gateElapsed(p: RewardedPresentation): Boolean =
    p.gateStartedAtMs != 0L &&
        (SystemClock.elapsedRealtime() - p.gateStartedAtMs) / 1000.0 >= p.durationSeconds

@Composable
private fun RewardedMinigame(
    presentation: RewardedPresentation,
    onFinish: (earned: Boolean) -> Unit,
) {
    // Earned immediately when there is no gate; otherwise resolved by the timer below.
    // A gate that already elapsed in a prior Activity instance (config-change
    // recreation) also starts earned — anchored to wall-clock so a rotation can't reset it.
    var rewardEarned by remember {
        mutableStateOf(presentation.durationSeconds <= 0 || presentation.rewardEarned || gateElapsed(presentation))
    }
    var secondsLeft by remember {
        mutableStateOf(maxOf(0, presentation.durationSeconds))
    }
    var showExitDialog by remember { mutableStateOf(false) }

    // DISPLAYED + impression fire once the creative first composes. Guarded so an
    // Activity recreation doesn't double-report either.
    LaunchedEffect(Unit) {
        if (!presentation.displayedReported) {
            presentation.displayedReported = true
            presentation.callbacks.onDisplayed()
            if (presentation.adId.isNotBlank()) {
                SimulaScope.launch { SimulaApiClient.trackImpression(presentation.adId, presentation.apiKey) }
            }
        }
    }

    // Wall-clock play gate, anchored on the presentation so a config change resumes the
    // remaining time instead of restarting it.
    LaunchedEffect(Unit) {
        if (presentation.durationSeconds <= 0) {
            presentation.rewardEarned = true
            rewardEarned = true
            return@LaunchedEffect
        }
        if (presentation.gateStartedAtMs == 0L) {
            presentation.gateStartedAtMs = SystemClock.elapsedRealtime()
        }
        while (true) {
            val elapsed = (SystemClock.elapsedRealtime() - presentation.gateStartedAtMs) / 1000.0
            secondsLeft = maxOf(0, presentation.durationSeconds - elapsed.toInt())
            if (elapsed >= presentation.durationSeconds) {
                presentation.rewardEarned = true
                rewardEarned = true
                break
            }
            delay(250L)
        }
    }

    val handleCloseAttempt = {
        if (rewardEarned) onFinish(true) else showExitDialog = true
    }

    // The user may leave at any time; back triggers the same close attempt.
    BackHandler(enabled = true) { handleCloseAttempt() }

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
                ).apply { loadUrl(url) }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { webView -> WebViewPool.release(webView) },
        )

        // Top bar: close (start) + reward status pill (end).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            CloseButton(onClick = handleCloseAttempt, size = 36)
            RewardStatusPill(rewardEarned = rewardEarned, secondsLeft = secondsLeft)
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit game?") },
            text = { Text("You haven't played long enough to earn your reward. If you exit now, you'll lose it.") },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    onFinish(false)
                }) { Text("Exit anyway", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("Keep playing") }
            },
        )
    }
}

@Composable
private fun RewardStatusPill(rewardEarned: Boolean, secondsLeft: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xC0000000))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (rewardEarned) {
            Text(
                text = "🎁 Reward unlocked!",
                color = Color(0xFF4ADE80),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Text(
                text = "🎮 Play to earn: ${secondsLeft}s",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
