package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.ads.CreativeCtaRouter
import ad.simula.ad.sdk.telemetry.Telemetry
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.network.SimulaApiClient
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.annotation.MainThread
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import java.util.WeakHashMap

/**
 * Hosts a native-ad creative in a pooled, non-scrollable [WebView] sized to its content.
 *
 * Performance: the [WebView] is owned by [NativeAdWebViewStore], which retains a small LRU of loaded
 * instances keyed by impression id. A first mount acquires a prewarmed view from [WebViewPool], loads
 * the creative, and keeps it; scrolling the slot out **detaches and pauses** that view (preserving its
 * rendered DOM) and scrolling back **reattaches the same view with no reload** — eliminating the
 * blank-then-pop re-render a recycled feed row otherwise shows. The creative is mounted from
 * `iframe_url` (the URL form, preferred for security) and falls back to `rendered_html`. The container
 * grows to the height the creative reports over the JS bridge; nothing is drawn while loading
 * (transparent, no skeleton).
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
    onLoadError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Per-creative retained session (WebView + bridge wiring) keyed by impression id. Stable across
    // remounts via the store, so a recycled feed row reattaches the same loaded view (no reload).
    val session = remember(impressionId, apiKey) {
        NativeAdWebViewStore.obtain(context.applicationContext, impressionId, apiKey)
    }
    // Point the wiring at the latest callbacks on each recomposition (cheap; @Volatile fields).
    session.wiring.onHeightPx = onHeightPx
    session.wiring.onAdClick = onAdClick
    session.wiring.onLoadError = onLoadError

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            // Hold a provisional height (not ~0) while the creative measures, so the slot never
            // collapses to a sliver — the slot keeps a shimmer over this until the height arrives.
            .height(if (heightDp > 0f) heightDp.dp else NATIVE_AD_PROVISIONAL_HEIGHT_DP.dp),
        // Reattaches the retained WebView (no reload) or builds + loads a fresh one on first mount.
        factory = { NativeAdWebViewStore.attach(session, context, iframeUrl, renderedHtml) },
        // Scroll-out: detach + pause + keep the loaded DOM (retained ids); recycle ephemerals/orphans.
        onRelease = { released -> NativeAdWebViewStore.release(session, released) },
    )
}

/**
 * Retains a small LRU of loaded native-ad [WebView]s keyed by impression id, so a slot that scrolls
 * out of a feed and back reattaches the **same, already-rendered** view instead of re-acquiring a
 * blank one from [WebViewPool] and reloading the creative (the blank-then-pop "re-render on scroll").
 *
 * A [Session] bundles the view with the [NativeAdWiring] its bridge/client point at, so reattach only
 * has to re-home the context and resume — the JS interface and document-start script survive untouched.
 * Off-screen sessions are detached from their parent, [WebView.onPause]d, and have their context reset
 * to the application context so a retained view can't leak the host Activity. The LRU is bounded
 * ([MAX_RETAINED]); the eldest idle session is evicted (returned to [WebViewPool]) when the cap is
 * exceeded, and everything idle is dropped under memory pressure.
 *
 * Blank impression ids (previews) are never retained — they get an ephemeral session that is recycled
 * to the pool on release, preserving the old behavior for one-shot QA creatives.
 *
 * All methods touch [WebView], so all run on the main thread; [evict]/[evictAll] hop to main since
 * cache invalidation can be called from any thread.
 */
internal object NativeAdWebViewStore {
    /** Retained-view cap on a normal device. Three retained WebViews pin ~60-90 MB. */
    private const val MAX_RETAINED = 3

    /** Retained-view cap on a low-RAM / small-heap device, where 3 retained views are too costly. */
    private const val MAX_RETAINED_LOW_RAM = 1

    /** Heaps below this are treated as low-end (small Dalvik heap ⇒ retain fewer views). */
    private const val LOW_HEAP_THRESHOLD_BYTES = 128L * 1024 * 1024

    // Resolved once on first [registerTrimCallbacks] (where an app context is available) so the cap
    // can consult ActivityManager.isLowRamDevice(); a `const` can't read runtime state. Defaults to
    // the normal cap until then. Volatile: written on the main thread, read in evictIfNeeded().
    @Volatile private var maxRetained = MAX_RETAINED

    /** One retained creative: its [webView] + the [wiring] its bridge points at + what's loaded. */
    class Session(
        val impressionId: String,
        val apiKey: String,
        val wiring: NativeAdWiring,
    ) {
        var webView: WebView? = null
        /** Identity of the creative currently loaded into [webView] (so a changed creative rebuilds). */
        var loadedKey: String? = null
        /** True while mounted in a live `AndroidView` — guards against stealing a view shown elsewhere. */
        var attached: Boolean = false
    }

    private val main = Handler(Looper.getMainLooper())

    // Access-ordered so iteration is least-recently-used first (for eviction). Main-thread only.
    private val sessions = LinkedHashMap<String, Session>(4, 0.75f, true)

    @Volatile private var trimRegistered = false

    /**
     * The stable session for [impressionId] (created + registered on first use), or a fresh ephemeral
     * session for a blank id. Reused across remounts so the retained view survives feed recycling.
     */
    @MainThread
    fun obtain(appContext: Context, impressionId: String, apiKey: String): Session {
        registerTrimCallbacks(appContext)
        if (impressionId.isBlank()) {
            return Session(impressionId, apiKey, NativeAdWiring(appContext, apiKey, impressionId))
        }
        sessions[impressionId]?.let {
            if (it.apiKey == apiKey) return it
            destroy(it); sessions.remove(impressionId) // api key changed → rebuild
        }
        val session = Session(impressionId, apiKey, NativeAdWiring(appContext, apiKey, impressionId))
        sessions[impressionId] = session
        evictIfNeeded()
        return session
    }

    /** Return the view to mount: the retained one (already loaded → no reload) or a freshly built one. */
    @MainThread
    fun attach(session: Session, hostContext: Context, iframeUrl: String?, renderedHtml: String?): WebView {
        val creativeKey = creativeKey(iframeUrl, renderedHtml)
        val retained = session.webView
        if (retained != null && session.loadedKey == creativeKey && !session.attached) {
            (retained.context as? MutableContextWrapper)?.baseContext = hostContext // re-home for theming
            (retained.parent as? ViewGroup)?.removeView(retained)                   // clear any stale parent
            retained.onResume()
            session.attached = true
            return retained
        }
        val fresh = buildWebView(session.wiring, hostContext, iframeUrl, renderedHtml)
        // Adopt as the retained instance only if the slot isn't already showing one (don't orphan it).
        if (!session.attached) {
            session.webView?.takeIf { it !== fresh }?.let { uninstallBridge(it); WebViewPool.release(it) }
            session.webView = fresh
            session.loadedKey = creativeKey
            session.attached = true
        }
        return fresh
    }

    /** Scroll-out / dispose: retain (detach + pause) the loaded view, or recycle an ephemeral/orphan. */
    @MainThread
    fun release(session: Session, released: WebView) {
        if (session.impressionId.isBlank() || released !== session.webView) {
            uninstallBridge(released)
            WebViewPool.release(released)
            if (released === session.webView) { session.webView = null; session.loadedKey = null }
            session.attached = false
            return
        }
        session.attached = false
        (released.parent as? ViewGroup)?.removeView(released)
        released.onPause() // suspend the creative's JS/rendering while off-screen (per-instance; no global timers)
        // Drop the Activity reference so a retained, off-screen view can't leak it.
        (released.context as? MutableContextWrapper)?.let { it.baseContext = it.applicationContext }
    }

    /** Drop the retained view for [impressionId] (e.g. the slot was invalidated for a fresh ad). */
    fun evict(impressionId: String) = onMain {
        sessions.remove(impressionId)?.let { destroy(it) }
    }

    fun evictAll() = onMain {
        sessions.values.toList().forEach { destroy(it) }
        sessions.clear()
    }

    @MainThread
    private fun buildWebView(wiring: NativeAdWiring, hostContext: Context, iframeUrl: String?, renderedHtml: String?): WebView {
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        val webView = WebViewPool.acquire(hostContext, NativeAdWebViewClient(wiring, docStart))
        webView.setBackgroundColor(Color.TRANSPARENT)
        // device-width viewport so 1 CSS px == 1 dp → the reported height maps straight to dp.
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = false
        installBridge(webView, wiring, docStart)
        when {
            !iframeUrl.isNullOrBlank() -> webView.loadUrl(iframeUrl)
            !renderedHtml.isNullOrBlank() -> webView.loadDataWithBaseURL(null, renderedHtml, "text/html", "utf-8", null)
        }
        return webView
    }

    @MainThread
    private fun destroy(session: Session) {
        session.webView?.let { uninstallBridge(it); WebViewPool.release(it) }
        session.webView = null
        session.loadedKey = null
        session.attached = false
    }

    @MainThread
    private fun evictIfNeeded() {
        val cap = maxRetained
        if (sessions.size <= cap) return
        val it = sessions.entries.iterator()
        while (it.hasNext() && sessions.size > cap) {
            val e = it.next()
            if (!e.value.attached) { destroy(e.value); it.remove() } // LRU-first (access-ordered map)
        }
    }

    /** Pick the retained-view cap once per process: 1 on a low-RAM / small-heap device, else 3. */
    @MainThread
    private fun resolveMaxRetained(appContext: Context) {
        val lowRam = runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.isLowRamDevice == true
        }.getOrDefault(false)
        val smallHeap = Runtime.getRuntime().maxMemory() < LOW_HEAP_THRESHOLD_BYTES
        maxRetained = if (lowRam || smallHeap) MAX_RETAINED_LOW_RAM else MAX_RETAINED
    }

    private fun registerTrimCallbacks(appContext: Context) {
        if (trimRegistered) return
        trimRegistered = true
        resolveMaxRetained(appContext)
        appContext.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) evictAll() }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() { evictAll() }

            override fun onConfigurationChanged(newConfig: Configuration) {}
        })
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Iframe URL identifies a creative directly; a rendered-HTML creative is keyed by its content. */
    private fun creativeKey(iframeUrl: String?, renderedHtml: String?): String =
        iframeUrl?.takeIf { it.isNotBlank() } ?: "html:${renderedHtml?.hashCode() ?: 0}"
}

// ── Bridge wiring ──────────────────────────────────────────────────────────────

/** Per-WebView routing of bridge messages + CTA taps to native actions. Callbacks are hot-swapped
 * each recomposition; JS-thread entry points hop to main before touching them. Held by a
 * [NativeAdWebViewStore.Session] across remounts so a retained WebView's bridge keeps working. */
internal class NativeAdWiring(
    private val appContext: Context,
    private val apiKey: String,
    private val impressionId: String,
) {
    @Volatile var onHeightPx: (Float) -> Unit = {}
    @Volatile var onAdClick: () -> Unit = {}
    @Volatile var onLoadError: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }

    /** Called off-main from the JS interface. Parses and dispatches on the main thread. */
    fun handleMessage(raw: String) {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: run {
            // Malformed JSON / non-object from the creative bridge — dropped, but counted so a broken
            // or hostile creative is visible rather than silent. Aggregated by signature (bounded).
            Telemetry.recordError(signature = "native:bridge_parse_failed", breadcrumb = "NativeAdWebView.handleMessage")
            return
        }
        // `as? JsonPrimitive` (not `.jsonPrimitive`, which throws IllegalArgumentException on a
        // non-primitive) so a creative sending an object/array for type/height/value can't crash the
        // WebView's JS thread with an uncaught exception.
        when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
            "SIMULA_AD_HEIGHT", "AD_RESIZE" -> {
                val h = (obj["height"] as? JsonPrimitive)?.floatOrNull ?: return
                if (h > 0f) main.post { onHeightPx(h) }
            }
            "AD_FEEDBACK" -> {
                val value = (obj["value"] as? JsonPrimitive)?.contentOrNull ?: return
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
    fun postMessage(json: String?) {
        // Runs on the WebView's JS thread. Declared nullable + no-op on null so a malformed JS
        // bridge invocation passing null can't NPE on entry before reaching handleMessage.
        json ?: return
        wiring.handleMessage(json)
    }
}

private class NativeAdWebViewClient(
    private val wiring: NativeAdWiring,
    private val documentStartSupported: Boolean,
) : WebViewClient() {
    // Framework callback params are declared nullable to match the platform override signatures and
    // guard against a non-conformant OEM WebView passing null (which would NPE on a non-null param) —
    // mirroring the interstitial/rewarded clients.
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        view ?: return
        // Fallback for older WebViews without document-start injection: wire the relay at page start.
        if (!documentStartSupported) view.evaluateJavascript(BRIDGE_SCRIPT, null)
    }

    // The creative's own (main-frame) load failing means there's nothing to show — e.g. no
    // connectivity when the slot scrolls into view. The creative never reports a height, so the
    // slot must collapse instead of holding the shimmer forever. Subresource failures (an image
    // inside the creative) are ignored so they can't hide an otherwise-rendered card.
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (view == null || request == null) return
        if (!request.isForMainFrame) return
        // Pre-empt the WebView's built-in "Webpage not available" page so it can't flash on screen
        // before the slot collapses.
        view.loadUrl("about:blank")
        wiring.onLoadError()
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        if (view == null || request == null) return
        if (!request.isForMainFrame) return
        view.loadUrl("about:blank")
        wiring.onLoadError()
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (view == null || request == null) return false
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
