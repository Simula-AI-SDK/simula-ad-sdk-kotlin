package ad.simula.ad.sdk.minigame

import ad.simula.ad.sdk.bridge.recordRenderProcessGone
import ad.simula.ad.sdk.telemetry.Telemetry
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread

/** Shared limits for all SDK-owned WebView retention, backed by production renderer-OOM data. */
private const val CONSTRAINED_DEVICE_RAM_BYTES = 4L * 1024 * 1024 * 1024
private const val CONSTRAINED_HEAP_BYTES = 256L * 1024 * 1024

/**
 * Prewarms and recycles [WebView] instances so the game / post-game ad iframe
 * opens without paying the cold chromium renderer-process startup on the
 * critical path.
 *
 * Android-specific correctness: WebViews are created against a
 * [MutableContextWrapper] wrapping the application context (prewarming with a
 * raw app context causes theming/dark-mode bugs and OEM class-cast crashes). At
 * [acquire] the wrapper's base is hot-swapped to the host Activity context for
 * correct theming; at [release] it is reset to the application context so a
 * pooled WebView never retains the Activity.
 *
 * All methods must run on the main thread — Android WebView is main-thread-only,
 * so the [idle] deque needs no synchronization.
 */
internal object WebViewPool {

    /** One spare is enough to hide Chromium startup without pinning a second idle renderer client. */
    private const val MAX_IDLE = 1

    /** Avoid recreating an idle renderer immediately after Android killed it or signalled pressure. */
    private const val POOL_COOLDOWN_MS = 5L * 60 * 1000

    private val mainHandler = Handler(Looper.getMainLooper())
    private val idle = ArrayDeque<WebView>()

    /** Count of idle pooled WebViews — for telemetry diagnostics. A benign cross-thread int read. */
    val pooledCount: Int get() = idle.size

    @Volatile private var callbacksRegistered = false
    @Volatile private var maxIdle = MAX_IDLE
    @Volatile private var poolingBlockedUntilMs = 0L

    /** Swallows the prewarm `about:blank` navigation so consumers never see it. */
    private val blankIgnoringClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) { /* ignore about:blank */ }
        // A pooled view can sit idle on about:blank for a while; absorb a renderer death here too so
        // it can't kill the host process before the view is handed to a consumer. Every WebView
        // attached to the dead renderer is unusable, so destroy the whole idle pool and pause
        // prewarming; retaining the callback's dead view would later hand out a permanently blank ad.
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            val absorbed = recordRenderProcessGone("pool_idle", detail)
            if (view != null && view in idle) {
                suspendPooling()
                trimIdle()
            }
            return absorbed
        }
    }

    /**
     * Create and warm an idle WebView if there's room. An actual creation attempt emits the existing
     * sampled operation pipeline's `webview_prewarm` event with a bounded trigger/result breadcrumb;
     * skipped speculative work stays silent. Creation failures fail open.
     */
    @MainThread
    fun prewarm(context: Context, trigger: String = "unspecified") {
        val startNanos = System.nanoTime()
        registerTrimCallbacks(context)
        val decision = webViewPrewarmDecision(
            maxIdle = maxIdle,
            idleCount = idle.size,
            nowMs = SystemClock.elapsedRealtime(),
            blockedUntilMs = poolingBlockedUntilMs,
        )
        if (decision != WebViewPrewarmDecision.WARM) {
            return
        }
        val created = runCatching { create(context) }
        val webView = created.getOrNull()
        if (webView != null) {
            idle.addLast(webView)
            recordPrewarm(startNanos, trigger, success = true, result = "warmed")
        } else {
            recordPrewarm(
                startNanos,
                trigger,
                success = false,
                result = "failed",
                failureClass = created.exceptionOrNull()?.javaClass?.simpleName,
            )
        }
    }

    /**
     * Hand out a warm WebView wired to [client], re-homed to [context] (the host
     * Activity), and schedule a refill on the next main-loop tick.
     */
    @MainThread
    fun acquire(context: Context, client: WebViewClient): WebView {
        val startNanos = System.nanoTime()
        registerTrimCallbacks(context)
        val pooled = idle.removeFirstOrNull()
        val webView = pooled ?: create(context)
        // Defensive detach: a consumer's Compose AndroidView inserts this view into its holder via
        // addView, which throws "child already has a parent" if the view is still attached. A pooled
        // view should already be detached (release() removes it), but guarantee it here so a stale
        // parent — from any release/acquire ordering across a branch/key swap — can never crash the
        // host. Mirrors NativeAdWebViewStore.attach().
        (webView.parent as? ViewGroup)?.removeView(webView)
        (webView.context as? MutableContextWrapper)?.baseContext = context
        webView.webViewClient = client
        // Drop any WebChromeClient left by a prior consumer so it can't outlive its surface (e.g. a
        // creative's telemetry chrome client mislabeling a later minigame/fallback iframe's JS errors).
        webView.webChromeClient = null
        val appContext = context.applicationContext
        mainHandler.post { prewarm(appContext, trigger = "acquire_refill") }
        // Warm (pool hit) vs cold (had to create) — surfaces prewarm effectiveness + cold cost.
        Telemetry.recordOperation(
            name = if (pooled != null) "webview_acquire_warm" else "webview_acquire_cold",
            durationMs = (System.nanoTime() - startNanos) / 1_000_000,
            success = true,
        )
        return webView
    }

    /** Reset a finished WebView and return it to the pool (or destroy if full). */
    @MainThread
    fun release(webView: WebView) {
        // Guard against a double release: enqueuing the same instance twice would let two acquire()
        // calls hand out one live WebView, and the second addView would crash with "child already has
        // a parent". If it's already idle it was reset on the first release, so this is a safe no-op.
        if (webView in idle) return
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        // Drop the Activity reference so a pooled WebView can't leak it.
        (webView.context as? MutableContextWrapper)?.let { it.baseContext = it.applicationContext }
        if (canRetainPooledWebView(maxIdle, idle.size, SystemClock.elapsedRealtime(), poolingBlockedUntilMs)) {
            // Re-pooling: reset to about:blank so a recycled view never flashes the prior creative.
            // about:blank tears down the page's DOM/JS context; clearHistory drops back/forward state.
            // (No clearCache — that flushes the app-global RAM cache and would undercut prewarming
            // without adding isolation.)
            webView.webViewClient = blankIgnoringClient
            // Restore the scroll chrome + viewport tweaks a consumer applied (the native-ad path
            // disables scrollbars/overscroll and widens the viewport) so the next consumer of this
            // shared instance (minigame, interstitial, rewarded, fallback) starts from the same
            // platform defaults a freshly created view would have.
            webView.isVerticalScrollBarEnabled = true
            webView.isHorizontalScrollBarEnabled = true
            webView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            webView.settings.useWideViewPort = false
            webView.settings.loadWithOverviewMode = false
            webView.loadUrl("about:blank")
            webView.clearHistory()
            idle.addLast(webView)
        } else {
            // Discarding: destroy WITHOUT first kicking off an about:blank load. That load completes
            // asynchronously, and its loadingStateChanged callback would fire into the just-destroyed
            // WebView ("Application attempted to call on a destroyed WebView"). stopLoading() above
            // already cancelled the in-flight creative load, so go straight to destroy.
            webView.destroy()
        }
    }

    /** Destroy warm idle WebViews under memory pressure (callbacks arrive on the main thread). */
    private fun trimIdle() {
        while (idle.isNotEmpty()) idle.removeFirst().destroy()
    }

    private fun suspendPooling() {
        val blockedUntil = SystemClock.elapsedRealtime() + POOL_COOLDOWN_MS
        if (blockedUntil > poolingBlockedUntilMs) poolingBlockedUntilMs = blockedUntil
    }

    private fun recordPrewarm(
        startNanos: Long,
        trigger: String,
        success: Boolean,
        result: String,
        failureClass: String? = null,
    ) {
        Telemetry.recordOperation(
            name = "webview_prewarm",
            durationMs = (System.nanoTime() - startNanos) / 1_000_000,
            success = success,
            failureClass = failureClass?.take(40),
            breadcrumb = "trigger=${canonicalWebViewPrewarmTrigger(trigger)};result=$result",
        )
    }

    // The check-and-set below is intentionally NOT synchronized: like the unsynchronized [idle]
    // deque, it relies on this object's whole-class @MainThread contract (only `prewarm`/`acquire`,
    // both @MainThread, reach here), so the two callers can never run concurrently. `callbacksRegistered`
    // stays @Volatile only for safe visibility of the diagnostic read. Do NOT call from a background
    // thread — that would both double-register here and corrupt the deque.
    @MainThread
    private fun registerTrimCallbacks(context: Context) {
        if (callbacksRegistered) return
        val appContext = context.applicationContext
        resolveMaxIdle(appContext)
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    suspendPooling()
                    trimIdle()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                suspendPooling()
                trimIdle()
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}
        })
        callbacksRegistered = true
    }

    /** Constrained devices skip idle pooling entirely; active WebViews still work normally. */
    @MainThread
    private fun resolveMaxIdle(appContext: Context) {
        val constrained = runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@runCatching true
            val memory = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memory)
            isWebViewMemoryConstrained(
                isLowRamDevice = am.isLowRamDevice,
                totalRamBytes = memory.totalMem,
                maxHeapBytes = Runtime.getRuntime().maxMemory(),
            )
        }.getOrDefault(true)
        maxIdle = if (constrained) 0 else MAX_IDLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    @MainThread
    private fun create(context: Context): WebView {
        val wrapper = MutableContextWrapper(context.applicationContext)
        return WebView(wrapper).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = blankIgnoringClient
            loadUrl("about:blank")
        }
    }
}

/** Pure retention policy kept outside Android callbacks for deterministic JVM coverage. */
internal fun canRetainPooledWebView(
    maxIdle: Int,
    idleCount: Int,
    nowMs: Long,
    blockedUntilMs: Long,
): Boolean = webViewPrewarmDecision(maxIdle, idleCount, nowMs, blockedUntilMs) == WebViewPrewarmDecision.WARM

internal enum class WebViewPrewarmDecision(val wireValue: String) {
    WARM("warmed"),
    CONSTRAINED("constrained"),
    FULL("full"),
    COOLDOWN("cooldown"),
}

internal fun webViewPrewarmDecision(
    maxIdle: Int,
    idleCount: Int,
    nowMs: Long,
    blockedUntilMs: Long,
): WebViewPrewarmDecision = when {
    maxIdle <= 0 -> WebViewPrewarmDecision.CONSTRAINED
    idleCount >= maxIdle -> WebViewPrewarmDecision.FULL
    nowMs < blockedUntilMs -> WebViewPrewarmDecision.COOLDOWN
    else -> WebViewPrewarmDecision.WARM
}

/** Keep operation cardinality bounded even if a future caller forwards host-controlled text. */
internal fun canonicalWebViewPrewarmTrigger(trigger: String): String = when (trigger) {
    "startup", "minigame_menu", "minigame_game", "rewarded_ready", "acquire_refill" -> trigger
    else -> "unspecified"
}

/** Shared pure memory policy for the idle pool and retained native-ad WebViews. */
internal fun isWebViewMemoryConstrained(
    isLowRamDevice: Boolean,
    totalRamBytes: Long,
    maxHeapBytes: Long,
): Boolean =
    isLowRamDevice ||
        totalRamBytes <= CONSTRAINED_DEVICE_RAM_BYTES ||
        maxHeapBytes <= CONSTRAINED_HEAP_BYTES
