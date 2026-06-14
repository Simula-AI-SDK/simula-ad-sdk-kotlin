package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.ads.CreativeCtaRouter
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.network.SimulaApiClient
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.WeakHashMap

/**
 * Hosts a native-ad creative in a pooled, non-scrollable [WebView] sized to its content.
 *
 * Performance: the [WebView] comes from [WebViewPool] (prewarmed, recycled — never allocated per
 * slot) and is returned to the pool on dispose. The creative is mounted from `iframe_url` (the URL
 * form, preferred for security) and falls back to `rendered_html`. The container grows to the height
 * the creative reports over the JS bridge; nothing is drawn while loading (transparent, no skeleton).
 *
 * Bridge (reuses the relay pattern of [ad.simula.ad.sdk.bridge.BridgeWebViewInstaller], scoped to the
 * native-ad message set):
 * - `SIMULA_AD_HEIGHT` / `AD_RESIZE` → resize the container ([onHeightPx]).
 * - `AD_FEEDBACK` `{value}` from the creative's AD badge menu → `interested`/`not_interested`/`report`
 *   POST to `reportAd`; `about` opens https://simula.ad in the external browser.
 * - A user tap that navigates the main frame is intercepted and opened in the **external** system
 *   browser via [CreativeCtaRouter] (PRD), firing [onAdClick].
 */
@Composable
internal fun NativeAdWebView(
    iframeUrl: String?,
    renderedHtml: String?,
    apiKey: String,
    impressionId: String,
    heightDp: Float,
    onHeightPx: (Float) -> Unit,
    onAdClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val wiring = remember(impressionId, apiKey) {
        NativeAdWiring(appContext = context.applicationContext, apiKey = apiKey, impressionId = impressionId)
    }
    // Point the wiring at the latest callbacks on each recomposition (cheap; @Volatile fields).
    wiring.onHeightPx = onHeightPx
    wiring.onAdClick = onAdClick

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            // Hold a provisional height (not ~0) while the creative measures, so the slot never
            // collapses to a sliver — the slot keeps a shimmer over this until the height arrives.
            .height(if (heightDp > 0f) heightDp.dp else NATIVE_AD_PROVISIONAL_HEIGHT_DP.dp),
        factory = {
            val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            val webView = WebViewPool.acquire(context, NativeAdWebViewClient(wiring, docStart))
            webView.setBackgroundColor(Color.TRANSPARENT)
            // device-width viewport so 1 CSS px == 1 dp → the reported height maps straight to dp.
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = false
            installBridge(webView, wiring, docStart)
            when {
                !iframeUrl.isNullOrBlank() -> webView.loadUrl(iframeUrl)
                !renderedHtml.isNullOrBlank() ->
                    webView.loadDataWithBaseURL(null, renderedHtml, "text/html", "utf-8", null)
            }
            webView
        },
        onRelease = { webView ->
            uninstallBridge(webView)
            WebViewPool.release(webView)
        },
    )
}

// ── Bridge wiring ──────────────────────────────────────────────────────────────

/** Per-WebView routing of bridge messages + CTA taps to native actions. Callbacks are hot-swapped
 * each recomposition; JS-thread entry points hop to main before touching them. */
private class NativeAdWiring(
    private val appContext: Context,
    private val apiKey: String,
    private val impressionId: String,
) {
    @Volatile var onHeightPx: (Float) -> Unit = {}
    @Volatile var onAdClick: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }

    /** Called off-main from the JS interface. Parses and dispatches on the main thread. */
    fun handleMessage(raw: String) {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "SIMULA_AD_HEIGHT", "AD_RESIZE" -> {
                val h = obj["height"]?.jsonPrimitive?.floatOrNull ?: return
                if (h > 0f) main.post { onHeightPx(h) }
            }
            "AD_FEEDBACK" -> {
                val value = obj["value"]?.jsonPrimitive?.contentOrNull ?: return
                main.post { handleFeedback(value) }
            }
            else -> Unit
        }
    }

    private fun handleFeedback(value: String) {
        when (value) {
            "about" -> CreativeCtaRouter.open(appContext, "https://www.simula.ad/privacy-policy", destination = "web")
            "interested" ->
                SimulaScope.launch { SimulaApiClient.recordInterest(impressionId = impressionId, interest = 1, apiKey = apiKey) }
            "not_interested" ->
                SimulaScope.launch { SimulaApiClient.recordInterest(impressionId = impressionId, interest = -1, apiKey = apiKey) }
            "report" ->
                SimulaScope.launch { SimulaApiClient.reportAd(adId = impressionId, flag = value, apiKey = apiKey) }
            else -> Unit
        }
    }

    /** Open a user-tapped creative URL in the external system browser (PRD) and fire CLICKED. */
    fun openExternal(url: String) {
        CreativeCtaRouter.open(appContext, url, destination = "web")
        onAdClick()
    }
}

private class NativeAdJsInterface(private val wiring: NativeAdWiring) {
    @JavascriptInterface
    fun postMessage(json: String) = wiring.handleMessage(json)
}

private class NativeAdWebViewClient(
    private val wiring: NativeAdWiring,
    private val documentStartSupported: Boolean,
) : WebViewClient() {
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        // Fallback for older WebViews without document-start injection: wire the relay at page start.
        if (!documentStartSupported) view.evaluateJavascript(BRIDGE_SCRIPT, null)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val url = request.url?.toString() ?: return false
        if (url.startsWith("about:")) return false
        // A user-gesture main-frame navigation is a CTA tap → external browser, never inside the slot.
        if (request.hasGesture()) {
            wiring.openExternal(url)
            return true
        }
        return false
    }
}

private const val NATIVE_BRIDGE_OBJECT = "SimulaNativeBridge"

/** Document-start scripts per WebView, removed on release so a pooled view never accumulates them. */
private val scriptHandlers = WeakHashMap<WebView, ScriptHandler>()

private fun installBridge(webView: WebView, wiring: NativeAdWiring, documentStartSupported: Boolean) {
    uninstallBridge(webView) // clear any wiring left from this pooled view's prior use
    webView.addJavascriptInterface(NativeAdJsInterface(wiring), NATIVE_BRIDGE_OBJECT)
    if (documentStartSupported) {
        scriptHandlers[webView] = WebViewCompat.addDocumentStartJavaScript(webView, BRIDGE_SCRIPT, setOf("*"))
    }
}

private fun uninstallBridge(webView: WebView) {
    runCatching { webView.removeJavascriptInterface(NATIVE_BRIDGE_OBJECT) }
    scriptHandlers.remove(webView)?.let { runCatching { it.remove() } }
}

/**
 * Injected into the creative: relays `window.postMessage` envelopes (the AD badge menu's
 * `AD_FEEDBACK`) to native, and — top frame only — reports content height so the SDK can size its
 * container. Mirrors the iOS injected script.
 */
private val BRIDGE_SCRIPT = """
    (function () {
      function bridge() { return window.$NATIVE_BRIDGE_OBJECT; }

      // Relay the creative's window.postMessage (e.g. AD_FEEDBACK) to native.
      window.addEventListener('message', function (e) {
        var d = e && e.data;
        if (!d) return;
        try {
          if (typeof d === 'string') { if (bridge()) bridge().postMessage(d); }
          else if (typeof d === 'object') { if (bridge()) bridge().postMessage(JSON.stringify(d)); }
        } catch (err) {}
      });

      // Report content height (top frame only) so the SDK resizes the slot to fit. Debounced so a
      // creative that animates / settles its layout posts a stable height instead of streaming
      // intermediate values that would thrash the host feed's layout.
      if (window.top === window.self) {
        var lastH = 0, timer = null;
        var measure = function () {
          var b = document.body;
          if (!b) { var de = document.documentElement; return de ? de.scrollHeight : 0; }
          // The bottom of the lowest in-flow child = the creative's content height, independent of the
          // height the SDK gave the WebView. A full-height creative (html,body{height:100%}) otherwise
          // reports back the size we set (Android WebView returns it even via scrollHeight/height:auto),
          // which feeds back and grows the slot on every resize. The card's content is top-packed in a
          // flex column, so the lowest child's bottom is the true height and never tracks our resize.
          var max = 0, kids = b.children;
          for (var i = 0; i < kids.length; i++) {
            var bottom = kids[i].getBoundingClientRect().bottom;
            if (bottom > max) max = bottom;
          }
          max += (window.scrollY || window.pageYOffset || 0);
          return Math.ceil(max) || b.scrollHeight;
        };
        var send = function () {
          try {
            var h = measure();
            if (h > 0 && Math.abs(h - lastH) >= 1 && bridge()) {
              lastH = h;
              bridge().postMessage(JSON.stringify({ type: 'SIMULA_AD_HEIGHT', height: h }));
            }
          } catch (err) {}
        };
        var post = function () {
          if (timer) clearTimeout(timer);
          timer = setTimeout(send, 80);
        };
        send();                                  // size as soon as possible
        window.addEventListener('load', post);
        window.addEventListener('resize', post);
        try {
          if (window.ResizeObserver) {
            var ro = new ResizeObserver(function () { post(); });
            if (document.documentElement) ro.observe(document.documentElement);
            if (document.body) ro.observe(document.body);
          }
        } catch (err) {}
      }
    })();
""".trimIndent()
