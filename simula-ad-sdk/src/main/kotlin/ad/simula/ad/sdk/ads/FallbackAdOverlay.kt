package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.network.SimulaApiClient
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts an ad creative and, when it closes, fetches a fallback ad (`/minigames/fallback_ad/{adId}`)
 * and shows it before fully closing — mirroring the declarative minigame's post-game ad flow. Used
 * by [SimulaInterstitialActivity] / [SimulaRewardedActivity].
 *
 * [content] renders the primary creative and is given an `onClose` to call when the user dismisses
 * it. If a fallback ad is returned it's shown next; either way [onFullyClosed] fires when everything
 * is done (so the Activity can finish).
 */
@Composable
internal fun FallbackAdHost(
    adId: String,
    onFullyClosed: () -> Unit,
    content: @Composable (onClose: () -> Unit) -> Unit,
) {
    var phase by remember { mutableStateOf<FallbackPhase>(FallbackPhase.Content) }

    when (val p = phase) {
        FallbackPhase.Content -> content {
            // Primary creative closed → try to fetch a fallback ad before finishing.
            phase = FallbackPhase.Fetching
            SimulaScope.launch {
                val url = runCatching {
                    if (adId.isNotBlank()) SimulaApiClient.fetchAdForMinigame(adId) else null
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    phase = if (!url.isNullOrBlank()) FallbackPhase.Showing(url) else FallbackPhase.Done
                }
            }
        }
        // Brief, on a black backdrop (the fetch is fast); avoids a flash of the host behind.
        FallbackPhase.Fetching -> Box(Modifier.fillMaxSize().background(Color.Black))
        is FallbackPhase.Showing -> FallbackAdOverlay(iframeUrl = p.url, onClose = { phase = FallbackPhase.Done })
        FallbackPhase.Done -> LaunchedEffect(Unit) { onFullyClosed() }
    }
}

private sealed interface FallbackPhase {
    data object Content : FallbackPhase
    data object Fetching : FallbackPhase
    data class Showing(val url: String) : FallbackPhase
    data object Done : FallbackPhase
}

/**
 * Full-screen fallback ad: the iframe in a pooled WebView with a 5s countdown ring that resolves to
 * a top-right close button (the same shape as the minigame menu's post-game overlay).
 */
@Composable
private fun FallbackAdOverlay(iframeUrl: String, onClose: () -> Unit) {
    var countdown by remember { mutableStateOf(5) }
    val ring = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        launch { ring.animateTo(0f, tween(5000, easing = LinearEasing)) }
        repeat(5) { delay(1000); countdown-- }
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
            modifier = Modifier.fillMaxSize(),
            onRelease = { webView -> WebViewPool.release(webView) },
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
                .size(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (countdown <= 0) {
                // Compact close button, matching the interstitial/rewarded default.
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", color = Color.White, fontSize = 12.sp)
                }
            } else {
                // Countdown ring, sized to the same compact footprint.
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val stroke = 2.dp.toPx()
                        drawArc(
                            color = Color.White,
                            startAngle = -90f,
                            sweepAngle = 360f * ring.value,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                    Text("$countdown", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
