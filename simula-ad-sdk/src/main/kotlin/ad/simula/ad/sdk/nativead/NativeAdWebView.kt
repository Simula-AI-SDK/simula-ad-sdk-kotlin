package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.ads.CreativeCtaRouter
import ad.simula.ad.sdk.bridge.CreativeTelemetryWebChromeClient
import ad.simula.ad.sdk.bridge.CreativeTelemetryWebViewClient
import ad.simula.ad.sdk.telemetry.Telemetry
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.minigame.repaintOnNextFrame
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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
 * `rendered_html` (the inline `<iframe srcdoc>`, preferred) and falls back to `iframe_url`. The container
 * grows to the height the creative reports over the JS bridge; nothing is drawn while loading
 * (transparent, no skeleton).
 *
 * Bridge (reuses the relay pattern of [ad.simula.ad.sdk.bridge.BridgeWebViewInstaller], scoped to the
 * native-ad message set):
 * - `SIMULA_AD_HEIGHT` / `AD_RESIZE` → resize the container ([onHeightPx]).
 * - `AD_FEEDBACK` `{value}` from the creative's AD badge menu → `interested`/`not_interested`/`report`
 *   POST to `reportAd`; `about` opens https://simula.ad in the external browser.
 * - A user tap that navigates the main frame is intercepted and opened in the **external** system
 *   browser via [CreativeCtaRouter] (PRD), firing [onAdClick]. The server-provided [trackingUrl] (an
 *   MMP click tracker) is preferred over the in-creative URL when present, so the click is attributed
 *   the same way the imperative interstitial/rewarded CTAs are; [destination] rides along for parity.
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
    trackingUrl: String? = null,
    destination: String = "appstore",
    storeUrl: String? = null,
    modifier: Modifier = Modifier,
    visibilityRelay: VisibilityRelay? = null,
) {
    val context = LocalContext.current
    // Per-creative retained session (WebView + bridge wiring) keyed by impression id. Stable across
    // remounts via the store, so a recycled feed row reattaches the same loaded view (no reload).
    val session = remember(impressionId, apiKey) {
        NativeAdWebViewStore.obtain(context.applicationContext, impressionId, apiKey)
    }
    // Bumped to force a remount of the AndroidView (below) when the creative's render process dies: the
    // dead WebView is torn down by onRelease and the creative is rebuilt by the factory. See
    // [NativeAdWiring.renderGone].
    var generation by remember(session) { mutableIntStateOf(0) }
    // Point the wiring at the latest callbacks + server CTA routing on each recomposition (cheap;
    // @Volatile fields). The routing is stable for a given impression but re-set here so a retained
    // session that outlives a recompose always reflects the current creative's tracking link.
    session.wiring.onHeightPx = onHeightPx
    session.wiring.onAdClick = onAdClick
    session.wiring.onLoadError = onLoadError
    session.wiring.onRenderGone = { generation++ }
    session.wiring.onPageReady = { visibilityRelay?.flush() }
    session.wiring.trackingUrl = trackingUrl
    session.wiring.destination = destination
    session.wiring.storeUrl = storeUrl

    // Route the live visible fraction (from the viewability tracker) into this slot's WebView while it
    // is mounted; unbind on dispose so a retained, off-screen creative receives no further onVisibility.
    DisposableEffect(session, visibilityRelay) {
        visibilityRelay?.bind { ratio -> session.wiring.pushVisibility(ratio) }
        onDispose { visibilityRelay?.bind(null) }
    }

    // App background → foreground: a hardware-accelerated WebView drops its draw functor when the window
    // loses visibility (ON_STOP), so an attached, on-screen native creative can return black/blank. Force
    // the repaint on foreground return — only for the live attached view (a retained, scrolled-out session
    // is already managed INVISIBLE by reattach; don't toggle it out from under the feed). Also: the
    // creative itself (character_ad.html) can freeze mid-video/mid-typing when its WebView's JS timers
    // were suspended while backgrounded, and the viewability relay de-dupes `onVisibility` pushes when
    // the on-screen geometry hasn't changed — so the creative would otherwise never learn it's live
    // again. Resume timers, re-arm the relay so the next sample is forwarded even if unchanged, and
    // deterministically wake the creative via the `onAppForeground` bridge (see character_ad.html).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, session, visibilityRelay) {
        var wasStopped = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> wasStopped = true
                Lifecycle.Event.ON_RESUME -> {
                    if (wasStopped) {
                        wasStopped = false
                        if (session.attached) {
                            val webView = session.webView
                            webView?.repaintOnNextFrame()
                            webView?.onResume()
                            webView?.resumeTimers() // defensive; pauseTimers() is process-global
                            session.wiring.pushForeground()
                            visibilityRelay?.resetDedupe()
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // key(generation): a render-process death bumps [generation], disposing this AndroidView (its
    // onRelease destroys the dead WebView) and recreating it, whose factory rebuilds the creative.
    // generation is stable across scroll/recompose, so normal reattach still reuses the retained view.
    key(generation) {
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
        // Reuse the retained view only if it is alive (render process intact), actually holds this
        // creative (its load completed — not the about:blank a failed load left behind), and isn't
        // already on screen elsewhere.
        if (retained != null && session.loadedKey == creativeKey && !session.attached &&
            !session.wiring.renderGone && !session.wiring.loadFailed
        ) {
            (retained.context as? MutableContextWrapper)?.baseContext = hostContext // re-home for theming
            (retained.parent as? ViewGroup)?.removeView(retained)                   // clear any stale parent
            retained.onResume()
            session.attached = true
            session.wiring.webView = retained // visibility pushes target the live view
            retained.repaintOnNextFrame() // repaint the stale hardware layer (avoid a black/blank frame)
            return retained
        }
        // A retained view whose render process died (e.g. killed while this slot was scrolled off) is
        // unusable — destroy it before rebuilding the creative fresh below.
        if (retained != null && session.wiring.renderGone) {
            discardDeadView(session)
        } else if (retained != null && session.wiring.loadFailed && !session.attached) {
            // A retained view whose creative load failed holds only about:blank. The view itself is
            // healthy, so recycle it to the pool and rebuild the creative fresh below — this is the
            // remount retry the still-cached fill is documented to get (see onLoadError in the slot).
            uninstallBridge(retained)
            WebViewPool.release(retained)
            session.webView = null
            session.loadedKey = null
            session.wiring.webView = null
        }
        val fresh = buildWebView(session.wiring, hostContext, iframeUrl, renderedHtml)
        // Adopt as the retained instance only if the slot isn't already showing one (don't orphan it).
        if (!session.attached) {
            session.webView?.takeIf { it !== fresh }?.let { uninstallBridge(it); WebViewPool.release(it) }
            session.webView = fresh
            session.loadedKey = creativeKey
            session.attached = true
            session.wiring.webView = fresh
        }
        return fresh
    }

    /** Scroll-out / dispose: retain (detach + pause) the loaded view, or recycle an ephemeral/orphan. */
    @MainThread
    fun release(session: Session, released: WebView) {
        // A render-dead current view must be destroyed, never recycled to the pool — a dead view in the
        // pool would hand the next consumer a permanently-blank WebView. (This fires when the slot
        // remounts after onRenderProcessGone: the dead view is disposed here, attach() rebuilds.)
        if (released === session.webView && session.wiring.renderGone) {
            session.attached = false
            discardDeadView(session)
            return
        }
        // Ephemeral (preview) / orphaned views are recycled, and so is a view whose creative load
        // FAILED (it holds only the about:blank that pre-empted the error page): retaining it would
        // reattach a blank card on remount instead of retrying the load (see NativeAdWiring.loadFailed).
        if (session.impressionId.isBlank() || released !== session.webView || session.wiring.loadFailed) {
            uninstallBridge(released)
            WebViewPool.release(released)
            if (released === session.webView) { session.webView = null; session.loadedKey = null; session.wiring.webView = null }
            session.attached = false
            return
        }
        session.attached = false
        (released.parent as? ViewGroup)?.removeView(released)
        released.onPause() // suspend the creative's JS/rendering while off-screen (per-instance; no global timers)
        // Drop the Activity reference so a retained, off-screen view can't leak it.
        (released.context as? MutableContextWrapper)?.let { it.baseContext = it.applicationContext }
    }

    /** Tear down a session's render-dead [WebView]: destroy it (a dead view must never be recycled to
     * [WebViewPool]) and clear the session so the next [attach]/[buildWebView] rebuilds the creative. */
    @MainThread
    private fun discardDeadView(session: Session) {
        session.webView?.let {
            uninstallBridge(it)
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        session.webView = null
        session.loadedKey = null
        session.wiring.webView = null
        session.wiring.renderGone = false
    }

    /** Drop the retained view for [impressionId] (e.g. the slot was invalidated for a fresh ad). */
    fun evict(impressionId: String) = onMain {
        val session = sessions[impressionId] ?: return@onMain
        // Don't tear down an on-screen view (parity with evictAll/evictIfNeeded): its AndroidView still
        // owns it, so destroying it here would blank the slot. release() reclaims it when it detaches.
        if (session.attached) return@onMain
        sessions.remove(impressionId)
        destroy(session)
    }

    /**
     * Drop every **idle** retained session — called on memory pressure and when the app is
     * backgrounded (`onTrimMemory` fires `TRIM_MEMORY_UI_HIDDEN` on every backgrounding, not just on
     * real pressure). The currently-**attached**, on-screen session is deliberately preserved: its
     * [WebView] is owned by a live `AndroidView`, so destroying it here yanks the view out of the
     * holder (leaving a blank slot on foreground return) and races chromium callbacks into a
     * destroyed WebView ("Application attempted to call on a destroyed WebView"). A mounted view is
     * only ever torn down through [release] (scroll-out / composition dispose). Mirrors the
     * `!attached` guard in [evictIfNeeded].
     */
    fun evictAll() = onMain {
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (!e.value.attached) { destroy(e.value); it.remove() }
        }
    }

    @MainThread
    private fun buildWebView(wiring: NativeAdWiring, hostContext: Context, iframeUrl: String?, renderedHtml: String?): WebView {
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        val webView = WebViewPool.acquire(hostContext, NativeAdWebViewClient(wiring, docStart))
        webView.webChromeClient = CreativeTelemetryWebChromeClient("character_ad") // capture JS console errors
        webView.setBackgroundColor(Color.TRANSPARENT)
        // A native ad sizes to content and must never scroll (parity with iOS, where the scroll
        // view is disabled): no scrollbars, no overscroll glow. The BRIDGE_SCRIPT additionally
        // locks overflow inside the page so a sub-dp rounding overflow can't pan the viewport by
        // the touch-slop a feed drag delivers before the list intercepts the gesture.
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        // device-width viewport so 1 CSS px == 1 dp → the reported height maps straight to dp.
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = false
        installBridge(webView, wiring, docStart)
        when {
            // Prefer rendered_html (the inline <iframe srcdoc> creative); fall back to iframe_url.
            !renderedHtml.isNullOrBlank() -> webView.loadDataWithBaseURL(null, renderedHtml, "text/html", "utf-8", null)
            !iframeUrl.isNullOrBlank() -> webView.loadUrl(iframeUrl)
        }
        return webView
    }

    @MainThread
    private fun destroy(session: Session) {
        session.webView?.let {
            uninstallBridge(it)
            // Never recycle a render-dead view to the pool — destroy it outright.
            if (session.wiring.renderGone) it.destroy() else WebViewPool.release(it)
        }
        session.webView = null
        session.loadedKey = null
        session.attached = false
        session.wiring.webView = null
        session.wiring.renderGone = false
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
    // Render-process-death recovery. The client flags [renderGone] when this creative's WebView loses
    // its render process (it then draws blank and is unusable); the store destroys it — never recycles
    // a dead view to the pool — and rebuilds the creative on the next attach, while [onRenderGone] asks
    // a live slot to remount immediately. [renderGoneStrikes] bounds rebuilds so a creative that
    // reliably crashes the renderer can't spin a rebuild loop; it resets on a successful page load.
    @Volatile var onRenderGone: () -> Unit = {}
    @Volatile var renderGone: Boolean = false
    @Volatile var renderGoneStrikes: Int = 0
    // The creative's main-frame load failed (e.g. offline when the slot scrolled in): the client
    // pre-empted the error page with about:blank, so the view holds nothing valid to reattach.
    // Without this flag the store would retain the blank view and — because [loadedKey] still
    // claims the creative is loaded — reattach it on remount WITHOUT reloading, silently breaking
    // the "remount retries once connectivity returns" contract of the still-cached fill. The view
    // itself is healthy (unlike [renderGone]), so it is recycled to the pool, not destroyed.
    // Cleared on a successful page load. Mirrors the Swift store's `unusable` flag.
    @Volatile var loadFailed: Boolean = false
    // Fired by the client when the creative's page finishes a real load (not about:blank) — the slot
    // replays the current visibility ratio, since pushes issued mid-load were dropped by the
    // `window.onVisibility&&…` guard yet still advanced the relay's dedupe baseline.
    @Volatile var onPageReady: () -> Unit = {}
    // Server-provided click-through routing for this creative. [trackingUrl] is the MMP click tracker
    // (preferred over the in-creative tap URL when set); [destination] is "appstore" | "web";
    // [storeUrl] is the campaign's raw `android_store_url` — the router's deterministic fallback
    // when the tracker is missing or can't be launched (parity with interstitial/rewarded CTAs).
    @Volatile var trackingUrl: String? = null
    @Volatile var destination: String = "appstore"
    @Volatile var storeUrl: String? = null

    // The WebView currently displaying this wiring's creative; set by the store on (re)attach and
    // cleared on release/destroy. Used by [pushVisibility] to reach the live view.
    @Volatile var webView: WebView? = null

    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Forward the slot's live visible fraction (0..1) to the creative via `window.onVisibility`, called
     * on every scroll frame by the viewability tracker. Guarded so it's a no-op until the creative
     * defines the function. Main-thread only — `evaluateJavascript` must run on the WebView's thread,
     * and the relay that drives this already reports from the main dispatcher.
     */
    @MainThread
    fun pushVisibility(ratio: Float) {
        val r = String.format(java.util.Locale.US, "%.2f", ratio.coerceIn(0f, 1f))
        webView?.evaluateJavascript("window.onVisibility&&window.onVisibility($r)", null)
    }

    /**
     * Deterministic foreground wake-up for the creative's freeze self-heal (see
     * `character_ad.html`'s `window.onAppForeground`). `evaluateJavascript` runs independent of the
     * WebView's own JS timers, so this reaches the page even if its internal listeners
     * (visibilitychange/pageshow/focus) never fired across the suspend.
     */
    @MainThread
    fun pushForeground() {
        webView?.evaluateJavascript("window.onAppForeground&&window.onAppForeground()", null)
    }

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

    /** Open a user-tapped creative CTA in the external system browser (PRD) and fire CLICKED. Prefers
     * the server-provided [trackingUrl] (the MMP click tracker — opened verbatim to preserve install
     * attribution, exactly as the imperative ads do) over [tappedUrl], the URL the creative itself
     * navigated to; falls back to [tappedUrl] when the serve carries no tracker. The raw [storeUrl]
     * rides along so the router can deterministically land an appstore CTA on the store when the
     * tracker can't be launched (parity with the interstitial/rewarded CTAs). */
    fun openExternal(tappedUrl: String) {
        val target = trackingUrl?.takeIf { it.isNotBlank() } ?: tappedUrl
        CreativeCtaRouter.open(appContext, target, destination, storeUrl = storeUrl)
        onAdClick()
    }
}

/**
 * Throttling channel that forwards the native slot's live visible fraction (0..1) to the creative's
 * `window.onVisibility`. Created per served slot by [NativeAdSlot], bound to that slot's WebView by
 * [NativeAdWebView] while mounted, and fed by [trackNativeAdViewability] on every scroll frame. Rounds
 * to ~1% and drops sub-1% changes so a 60 fps scroll can't flood the JS bridge. Single-threaded (the
 * viewability tracker reports from the main dispatcher); no locking.
 */
internal class VisibilityRelay {
    private var pusher: ((Float) -> Unit)? = null
    /** Last ratio actually pushed (dedupe baseline). -1 = nothing pushed yet. */
    private var last = -1f
    /** Latest ratio the tracker reported, whether or not the push reached the page. -1 = no sample yet. */
    private var latest = -1f

    /** Point the relay at the live WebView's pusher (or null to detach on dispose). */
    @MainThread
    fun bind(pusher: ((Float) -> Unit)?) {
        this.pusher = pusher
        last = -1f
    }

    /** Forward a 0..1 ratio, de-duped against the last forwarded value (~1% granularity). */
    @MainThread
    fun report(ratio: Float) {
        val r = ratio.coerceIn(0f, 1f)
        latest = r
        if (last >= 0f && kotlin.math.abs(r - last) < 0.01f) return
        last = r
        pusher?.invoke(r)
    }

    /**
     * Re-deliver the latest ratio unconditionally, bypassing the dedupe. Called when the creative
     * finishes loading: any [report] issued while the page was still loading was silently dropped
     * (by the `window.onVisibility&&…` guard, or a not-yet-attached WebView) but still advanced the
     * dedupe baseline — so a slot that mounted off-screen would never push again and the creative's
     * no-bridge fallback would animate it before it scrolls into view. With no sample yet, sends 0
     * ("bridge is live, not visible") so the creative arms its visibility gating instead of the
     * fallback timer; the first real sample follows through [report].
     */
    @MainThread
    fun flush() {
        val r = maxOf(latest, 0f)
        last = r
        pusher?.invoke(r)
    }

    /**
     * Re-arm the dedupe so the next [report] is forwarded even if the ratio is unchanged from the
     * last push. Called on app foreground return: the on-screen geometry is typically identical to
     * what it was before backgrounding, so without this the creative would never receive another
     * `onVisibility` call to tell it the app (and thus playback) is live again.
     */
    @MainThread
    fun resetDedupe() {
        last = -1f
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
) : CreativeTelemetryWebViewClient("character_ad") {
    private val main = Handler(Looper.getMainLooper())

    // Framework callback params are declared nullable to match the platform override signatures and
    // guard against a non-conformant OEM WebView passing null (which would NPE on a non-null param) —
    // mirroring the interstitial/rewarded clients.
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon) // starts the page-load timer
        view ?: return
        // Fallback for older WebViews without document-start injection: wire the relay at page start.
        if (!documentStartSupported) view.evaluateJavascript(BRIDGE_SCRIPT, null)
    }

    // A clean load means the (possibly just-rebuilt) creative is healthy again — reset the render-death
    // strike count so a later, unrelated render kill still earns a fresh rebuild.
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url) // records page-load timing
        if (url != null && url != "about:blank") {
            wiring.renderGoneStrikes = 0
            wiring.loadFailed = false // the view holds a valid creative again — retainable
            // window.onVisibility now exists — let the slot replay the current visibility ratio
            // (mid-load pushes were guard-dropped but still advanced the relay's dedupe baseline).
            wiring.onPageReady()
        }
    }

    // The creative's render process died — commonly an OS jettison while the app is backgrounded under
    // memory pressure. super records telemetry and returns true so the host process is NOT taken down;
    // the WebView is now permanently blank, so flag it for teardown+rebuild and ask the slot to remount
    // in place. Bounded by [MAX_RENDER_RECOVERIES] consecutive deaths (without a successful load in
    // between) so a creative that reliably crashes the renderer collapses the slot instead of looping.
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        val absorbed = super.onRenderProcessGone(view, detail)
        wiring.renderGone = true
        wiring.renderGoneStrikes += 1
        val recover = wiring.renderGoneStrikes <= MAX_RENDER_RECOVERIES
        main.post { if (recover) wiring.onRenderGone() else wiring.onLoadError() }
        return absorbed
    }

    // The creative's own (main-frame) load failing means there's nothing to show — e.g. no
    // connectivity when the slot scrolls into view. The creative never reports a height, so the
    // slot must collapse instead of holding the shimmer forever. Subresource failures (an image
    // inside the creative) are ignored so they can't hide an otherwise-rendered card.
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (view == null || request == null) return
        if (!request.isForMainFrame) return
        // The view now holds nothing valid — don't let the store retain/reattach it (see loadFailed).
        wiring.loadFailed = true
        // Pre-empt the WebView's built-in "Webpage not available" page so it can't flash on screen
        // before the slot collapses.
        view.loadUrl("about:blank")
        wiring.onLoadError()
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        if (view == null || request == null) return
        if (!request.isForMainFrame) return
        wiring.loadFailed = true
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

/** Max consecutive render-process deaths (with no successful load in between) the SDK rebuilds a native
 * creative through before giving up and collapsing the slot — a crash-loop backstop. */
private const val MAX_RENDER_RECOVERIES = 2

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
      // Nothing in the creative may scroll: the slot is content-sized (parity with iOS, whose
      // scroll view is disabled). Native-side scrollbars/overscroll are already off, but a sub-dp
      // rounding overflow would still let a feed drag pan the viewport by the touch slop before
      // the list intercepts. Lock overflow on this document AND inside any (same-origin, srcdoc)
      // iframe the creative renders in — WebKit/Chromium give iframes their own scrolling nodes.
      // Re-applied on iframe load (a reload wipes injected styles) and via a MutationObserver for
      // iframes attached later. Idempotent per document. Mirrors the iOS overflowLockScript.
      function lockDoc(doc) {
        try {
          if (!doc || doc.__simulaNoScroll) return;
          doc.__simulaNoScroll = true;
          var s = doc.createElement('style');
          s.textContent = 'html,body{overflow:hidden!important;overscroll-behavior:none!important;}';
          (doc.head || doc.documentElement).appendChild(s);
        } catch (e) {}
      }
      function lockFrame(frame) {
        try {
          frame.setAttribute('scrolling', 'no');
          frame.style.overflow = 'hidden';
        } catch (e) {}
        lockDoc(frame.contentDocument);
        if (!frame.__simulaNoScrollHook) {
          frame.__simulaNoScrollHook = true;
          try { frame.addEventListener('load', function () { lockDoc(frame.contentDocument); }); } catch (e) {}
        }
      }
      function lockAll() {
        try { document.querySelectorAll('iframe').forEach(lockFrame); } catch (e) {}
      }
      lockDoc(document);
      lockAll();
      try {
        if (window.MutationObserver && document.body && !document.body.__simulaNoScrollMO) {
          document.body.__simulaNoScrollMO = true;
          new MutationObserver(lockAll).observe(document.body, { childList: true, subtree: true });
        }
      } catch (e) {}

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
          // +1dp cushion so sub-pixel layout can't leave the content taller than the view (a tiny
          // scrollable overflow at the bottom). Mirrors the iOS height script.
          return (Math.ceil(max) || b.scrollHeight) + 1;
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
