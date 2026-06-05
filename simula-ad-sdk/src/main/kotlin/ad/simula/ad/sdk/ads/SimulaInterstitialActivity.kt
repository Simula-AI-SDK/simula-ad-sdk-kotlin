package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.CloseButton
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.provider.ProvideSimulaContext
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch

/**
 * Transparent, full-screen host for the imperative interstitial. Reads its
 * [InterstitialPresentation] from [InterstitialHandoff] by token, injects the
 * shared (warmed) session via [ProvideSimulaContext], and renders the
 * server-rendered HTML creative (which owns its own CTA).
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
                )
            }
        }
    }

    /** Fire CLOSED exactly once, then finish. */
    private fun closeOnce() {
        if (closed) return
        closed = true
        presentation?.callbacks?.onClosed()
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

@Composable
private fun CreativeInterstitial(
    presentation: InterstitialPresentation,
    onFinish: () -> Unit,
) {
    val ad = presentation.ad
    // The server-rendered HTML creative is the sole creative. load() only readies an
    // ad once `rendered_html` is non-blank, so this is effectively always present.
    val html = remember(ad) { ad.renderedHtml?.takeIf { it.isNotBlank() } }

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
        }

        // Close button — always available; tapping it dismisses the ad.
        CloseButton(
            onClick = onFinish,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp),
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
