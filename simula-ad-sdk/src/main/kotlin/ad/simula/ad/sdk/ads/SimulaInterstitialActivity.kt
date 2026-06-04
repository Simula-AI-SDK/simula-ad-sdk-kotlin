package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.image.CachedAsyncImage
import ad.simula.ad.sdk.minigame.CloseButton
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.provider.ProvideSimulaContext
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
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Transparent, full-screen host for the imperative interstitial. Reads its
 * [InterstitialPresentation] from [InterstitialHandoff] by token, injects the
 * shared (warmed) session via [ProvideSimulaContext], and renders a native
 * carousel of the rendered creative assets with a call-to-action.
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
                CreativeInterstitial(
                    presentation = p,
                    onFinish = ::closeOnce,
                    openDestination = { ad ->
                        // applicationContext so the open survives auto-dismiss.
                        CreativeCtaRouter.open(applicationContext, ad.trackingUrl, ad.destination)
                    },
                )
            }
        }
    }

    /** Fire (reward then) CLOSED exactly once, then finish. */
    private fun closeOnce() {
        if (closed) return
        closed = true
        presentation?.let { p ->
            if (p.rewarded && p.rewardEarned) p.callbacks.onEarnedReward()
            p.callbacks.onClosed()
        }
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
                presentation?.let { p ->
                    if (p.rewarded && p.rewardEarned) p.callbacks.onEarnedReward()
                    p.callbacks.onClosed()
                }
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

/** True if a rewarded dwell was already started and its wall-clock window has elapsed. */
private fun gateElapsed(p: InterstitialPresentation): Boolean =
    p.gateStartedAtMs != 0L &&
        (SystemClock.elapsedRealtime() - p.gateStartedAtMs).milliseconds >= p.minPlayThreshold

@Composable
private fun CreativeInterstitial(
    presentation: InterstitialPresentation,
    onFinish: () -> Unit,
    openDestination: (SimulaApiClient.AdLoadResult) -> Unit,
) {
    val ad = presentation.ad
    // A non-blank `rendered_html` renders full-screen and takes precedence over the
    // image carousel.
    val html = remember(ad) { ad.renderedHtml?.takeIf { it.isNotBlank() } }
    // FIX M2: defensively filter blank assets for the carousel.
    val assets = remember(ad) { ad.renderedAssets.filter { it.isNotBlank() } }

    // FIX C2: close is enabled immediately UNLESS this is a rewarded ad with a
    // positive play threshold. A non-rewarded ad (or threshold <= 0) is closable now.
    // Round-2 fix: a rewarded gate that already elapsed in a prior Activity instance
    // (config-change recreation) also starts closable — anchored to wall-clock so a
    // rotation can't reset the dwell or strand the user with close blocked.
    var closeEnabled by remember {
        mutableStateOf(
            !presentation.rewarded ||
                presentation.minPlayThreshold <= Duration.ZERO ||
                gateElapsed(presentation),
        )
    }

    // FIX C2: the reward gate runs ONLY for rewarded ads. Without this guard the
    // default minPlayThreshold == ZERO would mark every ad as reward-earned.
    if (presentation.rewarded) {
        LaunchedEffect(Unit) {
            // Anchor the dwell to wall-clock on first run so a config-change
            // recreation resumes the remaining time instead of restarting it.
            if (presentation.gateStartedAtMs == 0L) {
                presentation.gateStartedAtMs = SystemClock.elapsedRealtime()
            }
            val remaining = presentation.minPlayThreshold -
                (SystemClock.elapsedRealtime() - presentation.gateStartedAtMs).milliseconds
            if (remaining.isPositive()) delay(remaining)
            closeEnabled = true
            presentation.rewardEarned = true
        }
    }

    // DISPLAYED + impression fire once the creative first composes. Guarded so an
    // Activity recreation (config change) doesn't double-report either.
    LaunchedEffect(Unit) {
        if (!presentation.displayedReported) {
            presentation.displayedReported = true
            presentation.callbacks.onDisplayed()
            // FIX M2: skip impression when there is no ad id.
            if (ad.adId.isNotBlank()) {
                SimulaScope.launch { SimulaApiClient.trackImpression(ad.adId, presentation.apiKey) }
            }
        }
    }

    // Block system back while the reward gate is active.
    BackHandler(enabled = !closeEnabled) {}

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (html != null) {
            CreativeHtml(
                html = html,
                destination = ad.destination,
                onAdClick = { presentation.callbacks.onClicked() },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CreativeCarousel(
                assets = assets,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (closeEnabled) {
            CloseButton(
                onClick = onFinish,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
            )
        }

        // Always-visible bottom CTA — carousel (asset) path only. An HTML creative
        // owns its own CTA, so the SDK button is suppressed and the in-creative link
        // drives CLICKED instead.
        if (html == null) {
            Button(
                onClick = {
                    presentation.callbacks.onClicked()
                    openDestination(ad)
                    // For a rewarded ad the user must remain until the gate elapses.
                    if (!presentation.rewarded) onFinish()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            ) {
                Text(
                    text = presentation.ctaText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * Full-screen HTML creative. The server-rendered `rendered_html` owns its own CTA:
 * a user-initiated link navigation (gesture) is intercepted, reported as CLICKED via
 * [onAdClick], and routed to the advertiser destination through [CreativeCtaRouter]
 * (the same redirect-resolution the carousel CTA uses). Non-gesture navigations
 * (impression pixels, JS/meta auto-redirects) load normally so they can't fake a
 * click. The interstitial is NOT dismissed on click — the close button (gated for
 * rewarded creatives) drives dismissal.
 */
@Composable
private fun CreativeHtml(
    html: String,
    destination: String,
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
                // Self-contained creative: asset URLs are absolute (baseURL = null).
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        modifier = modifier,
        onRelease = { webView -> WebViewPool.release(webView) },
    )
}

/**
 * Native creative carousel. A single asset renders static (no swipe / no dots).
 * Multiple assets use a STABLE swipe driven by a single [Animatable] scroll
 * position (mirrors `GameGrid.MobileCarousel`) — no experimental HorizontalPager.
 * Position is clamped to [0, n-1] (non-wrapping).
 */
@Composable
private fun CreativeCarousel(
    assets: List<String>,
    modifier: Modifier = Modifier,
) {
    val n = assets.size
    if (n == 0) return

    if (n == 1) {
        CachedAsyncImage(
            model = assets[0],
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
        return
    }

    val scrollPosition = remember { Animatable(0f) }
    val velocityTracker = remember { VelocityTracker() }
    val coroutineScope = rememberCoroutineScope()

    // The current settled page (for the dot indicator).
    val page by remember {
        derivedStateOf { kotlin.math.round(scrollPosition.value).toInt().coerceIn(0, n - 1) }
    }

    Box(modifier = modifier) {
        var widthPx by remember { mutableStateOf(1f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(n) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            coroutineScope.launch { scrollPosition.stop() }
                            velocityTracker.resetTracking()
                        },
                        onDragEnd = {
                            val velocityPx = velocityTracker.calculateVelocity().x
                            val velocityPages = velocityPx / widthPx
                            val decayFactor = 0.4f
                            val projected = scrollPosition.value - velocityPages * decayFactor
                            val snapTarget = kotlin.math.round(projected)
                                .coerceIn(0f, (n - 1).toFloat())
                            coroutineScope.launch {
                                scrollPosition.animateTo(
                                    snapTarget,
                                    spring(dampingRatio = 0.85f, stiffness = 220f),
                                )
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            val scrollDelta = dragAmount / widthPx
                            coroutineScope.launch {
                                val next = (scrollPosition.value - scrollDelta)
                                    .coerceIn(0f, (n - 1).toFloat())
                                scrollPosition.snapTo(next)
                            }
                        },
                    )
                },
        ) {
            val currentPos = scrollPosition.value
            for (i in 0 until n) {
                val offset = i.toFloat() - currentPos
                // Only render on-screen and immediately-adjacent assets.
                if (kotlin.math.abs(offset) > 1.05f) continue
                CachedAsyncImage(
                    model = assets[i],
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = offset * widthPx },
                    contentScale = ContentScale.Crop,
                )
            }
        }

        // Dot indicator — only when there is more than one asset.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0 until n) {
                val active = i == page
                Box(
                    modifier = Modifier
                        .size(if (active) 9.dp else 7.dp)
                        .clip(CircleShape)
                        .background(if (active) Color.White else Color(0x66FFFFFF)),
                )
            }
        }
    }
}
