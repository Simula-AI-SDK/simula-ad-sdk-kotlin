package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.om.OmAdSession
import ad.simula.ad.sdk.om.OmSessionRef
import ad.simula.ad.sdk.om.OpenMeasurement
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    content: @Composable (onClose: () -> Unit) -> Unit,
) {
    var phase by remember { mutableStateOf<FallbackPhase>(FallbackPhase.Content) }

    when (val p = phase) {
        FallbackPhase.Content -> content {
            // Primary creative closed → try to fetch the fallback screens before finishing.
            phase = FallbackPhase.Fetching
            SimulaScope.launch {
                val ads = runCatching {
                    if (impressionId.isNotBlank()) SimulaApiClient.fetchFallbacks(impressionId) else emptyList()
                }.getOrDefault(emptyList())
                withContext(Dispatchers.Main) {
                    phase = if (ads.isNotEmpty()) FallbackPhase.Showing(ads, index = 0) else FallbackPhase.Done
                }
            }
        }
        // Brief, on a black backdrop (the fetch is fast); avoids a flash of the host behind.
        FallbackPhase.Fetching -> Box(Modifier.fillMaxSize().background(Color.Black))
        is FallbackPhase.Showing -> {
            val ad = p.ads[p.index]
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
private fun FallbackAdOverlay(
    iframeUrl: String,
    adId: String,
    onClose: () -> Unit,
) {
    var countdown by remember { mutableStateOf(5) }
    // OMID HTML session for this fallback screen — the OM service is injected into the
    // live remote page, then the WebView is registered as the ad view. Plain holder
    // (no recompose).
    val om = remember { OmSessionRef() }
    // Ring fills clockwise from the top (right to left), unfilled → filled, over the countdown.
    val ring = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { ring.animateTo(1f, tween(5000, easing = LinearEasing)) }
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

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (view == null || url == "about:blank" || om.attempted) return
                            om.attempted = true
                            OpenMeasurement.injectIntoLiveWebView(view) {
                                om.session = OmAdSession.startHtml(view, adId)?.also {
                                    it.fireLoaded()
                                    it.fireImpression()
                                }
                            }
                        }
                    },
                ).apply { loadUrl(iframeUrl) }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { webView ->
                val session = om.session
                if (session != null) {
                    session.finish()
                    WebViewPool.releaseAfterOmFlush(webView)
                } else {
                    WebViewPool.release(webView)
                }
            },
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
