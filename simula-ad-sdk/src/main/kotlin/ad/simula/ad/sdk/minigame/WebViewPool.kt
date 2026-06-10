package ad.simula.ad.sdk.minigame

import ad.simula.ad.sdk.telemetry.Telemetry
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread

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

    private const val MAX_IDLE = 2
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idle = ArrayDeque<WebView>()

    @Volatile private var callbacksRegistered = false

    /** Swallows the prewarm `about:blank` navigation so consumers never see it. */
    private val blankIgnoringClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) { /* ignore about:blank */ }
    }

    /** Create and warm an idle WebView if there's room. Cheap no-op when full. */
    @MainThread
    fun prewarm(context: Context) {
        registerTrimCallbacks(context)
        if (idle.size >= MAX_IDLE) return
        idle.addLast(create(context))
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
        (webView.context as? MutableContextWrapper)?.baseContext = context
        webView.webViewClient = client
        val appContext = context.applicationContext
        mainHandler.post { prewarm(appContext) }
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
        webView.stopLoading()
        webView.webViewClient = blankIgnoringClient
        // about:blank tears down the page's DOM/JS context; clearHistory drops
        // back/forward state. (No clearCache — that flushes the app-global RAM
        // cache and would undercut prewarming without adding isolation.)
        webView.loadUrl("about:blank")
        webView.clearHistory()
        (webView.parent as? ViewGroup)?.removeView(webView)
        // Drop the Activity reference so a pooled WebView can't leak it.
        (webView.context as? MutableContextWrapper)?.let { it.baseContext = it.applicationContext }
        if (idle.size < MAX_IDLE) {
            idle.addLast(webView)
        } else {
            webView.destroy()
        }
    }

    /** Destroy warm idle WebViews under memory pressure (callbacks arrive on the main thread). */
    private fun trimIdle() {
        while (idle.isNotEmpty()) idle.removeFirst().destroy()
    }

    private fun registerTrimCallbacks(context: Context) {
        if (callbacksRegistered) return
        context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trimIdle()
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                trimIdle()
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}
        })
        callbacksRegistered = true
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
