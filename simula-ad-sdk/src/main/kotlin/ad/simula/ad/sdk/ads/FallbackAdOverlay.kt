package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.model.AutoStoreRedirect
import ad.simula.ad.sdk.model.endScreenTriggerForIndex
import ad.simula.ad.sdk.network.SimulaApiClient
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.ceil
import kotlinx.coroutines.delay

/**
 * Hosts an ad creative and, when it closes, fetches the serve's fallback ad screens
 * (`GET /load/fallbacks/{impressionId}`) and reveals them in order before fully closing —
 * mirroring the declarative minigame's post-game ad flow. Used by
 * [SimulaInterstitialActivity] / [SimulaRewardedActivity].
 *
 * [content] renders the primary creative and is given an `onClose` to call when the user dismisses
 * it. Each returned screen (campaign creative, then the "Get the App" end screen) is shown next, one
 * per close tap; either way [onFullyClosed] fires when everything is done (so the Activity can
 * finish).
 */
@Composable
internal fun FallbackAdHost(
    impressionId: String,
    onFullyClosed: () -> Unit,
    autoStoreRedirect: AutoStoreRedirect? = null,
    onAutoStoreRedirect: () -> Unit = {},
    content: @Composable (onClose: () -> Unit) -> Unit,
) {
    var phase by remember { mutableStateOf<FallbackPhase>(FallbackPhase.Content) }
    // auto_store_redirect END_SCREEN_N: open the primary ad's store once, when the fallback screen
    // whose index matches the configured trigger is presented (index 0 = END SCREEN 1, index 1 = 2).
    var autoRedirectFired by remember { mutableStateOf(false) }

    // Prefetch the fallback screens in the background while the primary creative is on screen, so
    // they present instantly on close instead of fetching then (which flashed the host behind).
    // `GET /load/fallbacks` is side-effect-free, so prefetching reports nothing prematurely.
    // null = still in flight; empty = none returned.
    var prefetched by remember { mutableStateOf<List<SimulaApiClient.FallbackAd>?>(null) }
    LaunchedEffect(impressionId) {
        prefetched = runCatching {
            if (impressionId.isNotBlank()) SimulaApiClient.fetchFallbacks(impressionId) else emptyList()
        }.getOrDefault(emptyList())
    }

    // Primary creative closed → present the prefetched screens immediately. If the prefetch is
    // somehow still in flight (user closed very fast), wait briefly in [FallbackPhase.Fetching].
    fun onPrimaryClosed() {
        val ads = prefetched
        phase = when {
            ads == null -> FallbackPhase.Fetching
            ads.isNotEmpty() -> FallbackPhase.Showing(ads, index = 0)
            else -> FallbackPhase.Done
        }
    }

    when (val p = phase) {
        FallbackPhase.Content -> content { onPrimaryClosed() }
        // Prefetch wasn't ready at close — hold on a black backdrop and advance the instant it lands.
        FallbackPhase.Fetching -> {
            Box(Modifier.fillMaxSize().background(Color.Black))
            // Swallow back during this brief settle window so a fast back-press can't finish the
            // Activity before the end screens are revealed (parity with the gated close).
            BackHandler(enabled = true) {}
            LaunchedEffect(prefetched) {
                val ads = prefetched ?: return@LaunchedEffect
                phase = if (ads.isNotEmpty()) FallbackPhase.Showing(ads, index = 0) else FallbackPhase.Done
            }
        }
        is FallbackPhase.Showing -> {
            val ad = p.ads[p.index]
            // Fire the auto store redirect when the END_SCREEN_N fallback screen is presented.
            LaunchedEffect(p.index) {
                if (!autoRedirectFired && autoStoreRedirect?.enabled == true &&
                    autoStoreRedirect.trigger == endScreenTriggerForIndex(p.index)
                ) {
                    autoRedirectFired = true
                    onAutoStoreRedirect()
                }
            }
            // key() so each screen gets fresh overlay state (countdown, WebView) — without it the
            // next screen would inherit the previous one's elapsed countdown and loaded page.
            key(p.index) {
                FallbackAdOverlay(
                    iframeUrl = ad.iframeUrl,
                    adId = ad.adId,
                    onClose = {
                        // Reveal the next screen on each close tap; done after the last one.
                        phase = if (p.index + 1 < p.ads.size) p.copy(index = p.index + 1) else FallbackPhase.Done
                    },
                )
            }
        }
        FallbackPhase.Done -> LaunchedEffect(Unit) { onFullyClosed() }
    }
}

private sealed interface FallbackPhase {
    data object Content : FallbackPhase
    data object Fetching : FallbackPhase
    data class Showing(val ads: List<SimulaApiClient.FallbackAd>, val index: Int) : FallbackPhase
    data object Done : FallbackPhase
}

/**
 * Full-screen fallback ad: the iframe in a pooled WebView with a 5s countdown ring that resolves to
 * a top-right close button (the same shape as the minigame menu's post-game overlay).
 */
@Composable
private fun FallbackAdOverlay(iframeUrl: String, adId: String, onClose: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var countdown by remember { mutableStateOf(5) }
    // Ring fills clockwise from the top (right to left), unfilled → filled, over the countdown.
    val ring = remember { Animatable(0f) }
    // Foreground-only 5s gate: time accrues only while the Activity is RESUMED, so leaving the app
    // pauses the countdown (parity with the interstitial / rewarded close gates). repeatOnLifecycle
    // cancels the loop when backgrounded and resumes it from the accrued time on return.
    LaunchedEffect(Unit) {
        val totalMs = 5_000L
        var accumulatedMs = 0L
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Re-anchor on each resume so the backgrounded interval is never counted.
            var lastTickMs = SystemClock.elapsedRealtime()
            while (accumulatedMs < totalMs) {
                delay(50L)
                val now = SystemClock.elapsedRealtime()
                accumulatedMs += now - lastTickMs
                lastTickMs = now
                ring.snapTo((accumulatedMs.toFloat() / totalMs).coerceIn(0f, 1f))
                countdown = ceil((totalMs - accumulatedMs).coerceAtLeast(0L) / 1000.0).toInt()
            }
        }
    }
    // Back can only close once the countdown elapses (parity with the creative's gated close).
    BackHandler(enabled = true) { if (countdown <= 0) onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                WebViewPool.acquire(
                    context = ctx,
                    client = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url?.toString() ?: return false
                            // Same-origin stays in the webview; cross-origin opens externally.
                            if (Uri.parse(iframeUrl).host == Uri.parse(target).host) return false
                            return try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                                true
                            } catch (e: Exception) {
                                false
                            }
                        }
                    },
                ).apply { loadUrl(iframeUrl) }
            },
            // The creative fills edge-to-edge: inset only vertically (status / nav / top notch),
            // matching the interstitial / rewarded creatives, and draw under any horizontal
            // display-cutout so the transparent WebView's black backing never shows as left/right
            // bars in landscape on a cutout device.
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
            onRelease = { webView -> WebViewPool.release(webView) },
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp)
                .size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (countdown <= 0) {
                // Compact close button (16dp circle) with a full 48dp tap target so it's easy to hit.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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
                // Countdown ring, a 16dp circle centered in the same footprint.
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(12.dp)) {
                        val stroke = 2.dp.toPx()
                        drawArc(
                            color = Color.White,
                            startAngle = -90f,
                            sweepAngle = 360f * ring.value,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                    Text("$countdown", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Persistent ad-info "i" + report sheet (required disclosure on the fallback ad).
        if (adId.isNotEmpty()) {
            AdInfoReportOverlay(adId = adId)
        }
    }
}
