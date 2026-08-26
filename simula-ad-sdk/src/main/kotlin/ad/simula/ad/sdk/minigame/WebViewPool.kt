package ad.simula.ad.sdk.minigame

import ad.simula.ad.sdk.bridge.recordRenderProcessGone
import ad.simula.ad.sdk.core.FullscreenPresentationRegistry
import ad.simula.ad.sdk.telemetry.Telemetry
import ad.simula.ad.sdk.telemetry.ProcessSdkEntryOrigin
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

/** Shared limit for all SDK-owned WebView retention, backed by production renderer-OOM data. */
private const val CONSTRAINED_HEAP_BYTES = 256L * 1024 * 1024
private const val RESET_TIMEOUT_MS = 2_000L
private const val RESET_URL_PREFIX = "https://sdk.simula.invalid/webview-reset"
private const val RESET_HTML = "<html><body></body></html>"

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

    private val poolState = WebViewResetPoolState<WebView>()
    private val resetTimeouts = mutableMapOf<WebView, Runnable>()
    private val resetCompletions = mutableMapOf<WebView, (Boolean) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var resetGeneration = 0L
    @Volatile private var retainedCountSnapshot = 0

    /** Count of retained idle/resetting WebViews — for telemetry diagnostics. */
    val pooledCount: Int get() = retainedCountSnapshot

    @Volatile private var callbacksRegistered = false
    @Volatile private var maxIdle = MAX_IDLE
    // Remain conservative until either entry path observes a resumed Activity. Required WebViews
    // still acquire cold; only speculative/idle retention is suppressed before that signal.
    private val retentionState = WebViewRetentionState(initiallyActive = false)
    private val prewarmSkipGate = WebViewPrewarmSkipGate()

    /**
     * Create and warm an idle WebView if there's room. An actual creation attempt emits the existing
     * sampled operation pipeline's `webview_prewarm` event with a bounded trigger/result breadcrumb.
     * Skips emit at most once per canonical reason for the process. Creation failures fail open.
     */
    @MainThread
    fun prewarm(context: Context, trigger: String = "unspecified") {
        val startNanos = System.nanoTime()
        registerTrimCallbacks(context)
        val decision = webViewPrewarmDecision(
            maxIdle = maxIdle,
            idleCount = poolState.retainedCount,
            nowMs = SystemClock.elapsedRealtime(),
            blockedUntilMs = retentionState.blockedUntilMs,
            applicationActive = retentionState.applicationActive,
            readyPresentationActive = isReadyFullscreenPrewarmTrigger(trigger) &&
                FullscreenPresentationRegistry.hasActivePresentation(),
        )
        if (decision != WebViewPrewarmDecision.WARM) {
            if (prewarmSkipGate.shouldRecord(decision)) {
                recordPrewarm(
                    startNanos = startNanos,
                    trigger = trigger,
                    success = true,
                    result = decision.wireValue,
                )
            }
            return
        }
        val created = runCatching { create(context) }
        val webView = created.getOrNull()
        if (webView != null) {
            val started = beginReset(webView) { retained ->
                recordPrewarm(
                    startNanos,
                    trigger,
                    success = retained,
                    result = if (retained) "warmed" else "failed",
                    failureClass = if (retained) null else "ResetFailed",
                )
            }
            if (!started) runCatching { webView.destroy() }
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
     * Hand out a warm WebView wired to [client] and re-homed to [context] (the host
     * Activity). Acquiring never refills speculatively; prewarm is requested only by explicit UI
     * demand or a still-ready successful fullscreen load.
     */
    @MainThread
    fun acquire(context: Context, client: WebViewClient, surface: String = "unspecified"): WebView {
        val startNanos = System.nanoTime()
        registerTrimCallbacks(context)
        val pooled = poolState.acquire().also { publishRetainedCount() }
        val webView = pooled ?: create(context)
        // Defensive detach: a consumer's Compose AndroidView inserts this view into its holder via
        // addView, which throws "child already has a parent" if the view is still attached. A pooled
        // view should already be detached (release() removes it), but guarantee it here so a stale
        // parent — from any release/acquire ordering across a branch/key swap — can never crash the
        // host. Mirrors NativeAdWebViewStore.attach().
        (webView.parent as? ViewGroup)?.removeView(webView)
        (webView.context as? MutableContextWrapper)?.baseContext = context
        // Cancel the pool reset before handing callbacks to the real consumer.
        webView.stopLoading()
        webView.webViewClient = client
        // Drop any WebChromeClient left by a prior consumer so it can't outlive its surface (e.g. a
        // creative's telemetry chrome client mislabeling a later minigame/fallback iframe's JS errors).
        webView.webChromeClient = null
        // Warm (pool hit) vs cold (had to create) — surfaces prewarm effectiveness + cold cost.
        Telemetry.recordOperation(
            name = if (pooled != null) "webview_acquire_warm" else "webview_acquire_cold",
            durationMs = (System.nanoTime() - startNanos) / 1_000_000,
            success = true,
            breadcrumb = "surface=${canonicalWebViewAcquireSurface(surface)}",
            timeSinceInitMs = ProcessSdkEntryOrigin.elapsedMs(),
        )
        return webView
    }

    /** Reset a finished WebView and return it to the pool (or destroy if full). */
    @MainThread
    fun release(webView: WebView) {
        // Guard against a double release: enqueuing the same instance twice would let two acquire()
        // calls hand out one live WebView, and the second addView would crash with "child already has
        // a parent". If it's already idle it was reset on the first release, so this is a safe no-op.
        if (poolState.contains(webView)) return
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        // Drop the Activity reference so a pooled WebView can't leak it.
        (webView.context as? MutableContextWrapper)?.let { it.baseContext = it.applicationContext }
        if (canRetainPooledWebView(
                maxIdle,
                poolState.retainedCount,
                SystemClock.elapsedRealtime(),
                retentionState.blockedUntilMs,
                retentionState.applicationActive,
            )
        ) {
            // Keep the detached view quarantined until its reset document fully finishes. Adding it
            // to the acquirable pool earlier can forward a queued reset callback into the next client.
            // (No clearCache — that flushes the app-global RAM cache and would undercut prewarming
            // without adding isolation.)
            // Restore the scroll chrome + viewport tweaks a consumer applied (the native-ad path
            // disables scrollbars/overscroll and widens the viewport) so the next consumer of this
            // shared instance (minigame, interstitial, rewarded, fallback) starts from the same
            // platform defaults a freshly created view would have.
            webView.isVerticalScrollBarEnabled = true
            webView.isHorizontalScrollBarEnabled = true
            webView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            webView.settings.useWideViewPort = false
            webView.settings.loadWithOverviewMode = false
            if (!beginReset(webView)) runCatching { webView.destroy() }
        } else {
            // Discarding: destroy WITHOUT first kicking off an about:blank load. That load completes
            // asynchronously, and its loadingStateChanged callback would fire into the just-destroyed
            // WebView ("Application attempted to call on a destroyed WebView"). stopLoading() above
            // already cancelled the in-flight creative load, so go straight to destroy.
            webView.destroy()
        }
    }

    /** Destroy warm idle/resetting WebViews under memory pressure. */
    @MainThread
    internal fun trimIdle() {
        poolState.evictAll().forEach { webView ->
            resetTimeouts.remove(webView)?.let(mainHandler::removeCallbacks)
            resetCompletions.remove(webView)?.invoke(false)
            runCatching { webView.destroy() }
        }
        publishRetainedCount()
    }

    /**
     * Reset a detached view under an SDK-only URL and expose it to [acquire] only after
     * [WebViewClient.onPageFinished]. Waiting through finish prevents both commit and finish events
     * from the reset navigation being delivered to the next consumer.
     */
    @MainThread
    private fun beginReset(webView: WebView, onComplete: ((Boolean) -> Unit)? = null): Boolean {
        if (!poolState.beginReset(webView, maxIdle)) {
            onComplete?.invoke(false)
            return false
        }
        publishRetainedCount()
        if (onComplete != null) resetCompletions[webView] = onComplete

        val timeout = Runnable { failReset(webView) }
        resetTimeouts[webView] = timeout
        mainHandler.postDelayed(timeout, RESET_TIMEOUT_MS)
        val resetUrl = "$RESET_URL_PREFIX/${++resetGeneration}"
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view === webView && url == resetUrl) finishReset(webView)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val absorbed = recordRenderProcessGone("pool_idle", detail)
                if (view != null && poolState.contains(view)) {
                    suspendPooling()
                    trimIdle()
                }
                return absorbed
            }
        }
        return runCatching {
            webView.loadDataWithBaseURL(resetUrl, RESET_HTML, "text/html", "UTF-8", resetUrl)
        }.fold(
            onSuccess = { true },
            onFailure = {
                resetTimeouts.remove(webView)?.let(mainHandler::removeCallbacks)
                poolState.failReset(webView)
                publishRetainedCount()
                resetCompletions.remove(webView)?.invoke(false)
                false
            },
        )
    }

    @MainThread
    private fun finishReset(webView: WebView) {
        if (!poolState.contains(webView)) return
        resetTimeouts.remove(webView)?.let(mainHandler::removeCallbacks)
        val retain = canRetainPooledWebView(
            maxIdle = maxIdle,
            idleCount = poolState.readyCount,
            nowMs = SystemClock.elapsedRealtime(),
            blockedUntilMs = retentionState.blockedUntilMs,
            applicationActive = retentionState.applicationActive,
        )
        val retained = poolState.completeReset(webView, retain)
        publishRetainedCount()
        resetCompletions.remove(webView)?.invoke(retained)
        if (retained) {
            webView.clearHistory()
        } else if (!poolState.contains(webView)) {
            runCatching { webView.destroy() }
        }
    }

    @MainThread
    private fun failReset(webView: WebView) {
        resetTimeouts.remove(webView)?.let(mainHandler::removeCallbacks)
        if (poolState.failReset(webView)) {
            publishRetainedCount()
            resetCompletions.remove(webView)?.invoke(false)
            runCatching { webView.destroy() }
        }
    }

    private fun publishRetainedCount() {
        retainedCountSnapshot = poolState.retainedCount
    }

    private fun suspendPooling() {
        val blockedUntil = SystemClock.elapsedRealtime() + POOL_COOLDOWN_MS
        retentionState.suspendUntil(blockedUntil)
    }

    /** Share the pool's real-memory-pressure cooldown with other SDK-owned WebView stores. */
    @MainThread
    internal fun suspendRetention() {
        suspendPooling()
        trimIdle()
    }

    /** Native-ad views use the same five-minute eligibility window as idle pooled views. */
    @MainThread
    internal fun isRetentionEligible(): Boolean =
        isWebViewRetentionEligible(
            SystemClock.elapsedRealtime(),
            retentionState.blockedUntilMs,
            retentionState.applicationActive,
        )

    /** Marks the process foreground-eligible without refilling the pool or changing cooldown state. */
    @MainThread
    internal fun markApplicationActive(context: Context) {
        registerTrimCallbacks(context)
        retentionState.markActive()
    }

    private fun recordPrewarm(
        startNanos: Long,
        trigger: String,
        success: Boolean,
        result: String,
        failureClass: String? = null,
    ) {
        runCatching {
            Telemetry.recordOperation(
                name = "webview_prewarm",
                durationMs = (System.nanoTime() - startNanos) / 1_000_000,
                success = success,
                failureClass = failureClass?.take(40),
                breadcrumb = "trigger=${canonicalWebViewPrewarmTrigger(trigger)};result=$result",
                timeSinceInitMs = ProcessSdkEntryOrigin.elapsedMs(),
            )
        }
    }

    // The check-and-set below is intentionally NOT synchronized: like the unsynchronized [idle]
    // deque, it relies on this object's whole-class @MainThread contract (only `prewarm`, `acquire`,
    // and `markApplicationActive` reach here), so callers can never run concurrently. `callbacksRegistered`
    // stays @Volatile only for safe visibility of the diagnostic read. Do NOT call from a background
    // thread — that would both double-register here and corrupt the deque.
    @MainThread
    private fun registerTrimCallbacks(context: Context) {
        if (callbacksRegistered) return
        val appContext = context.applicationContext
        resolveMaxIdle(appContext)
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                when (webViewTrimAction(level)) {
                    WebViewTrimAction.EVICT_IDLE -> {
                        retentionState.markInactive()
                        trimIdle()
                    }
                    WebViewTrimAction.EVICT_IDLE_AND_COOLDOWN -> {
                        suspendPooling()
                        trimIdle()
                    }
                    WebViewTrimAction.NONE -> Unit
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
        val capabilities = runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@runCatching null
            am.isLowRamDevice to Runtime.getRuntime().maxMemory()
        }.getOrNull()
        maxIdle = resolveWebViewRetentionCapacity(
            isLowRamDevice = capabilities?.first,
            maxHeapBytes = capabilities?.second,
            normalCapacity = MAX_IDLE,
            constrainedCapacity = 0,
        )
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
        }
    }
}

/** Pure reset/ready ownership used by [WebViewPool] and JVM policy tests. */
internal class WebViewResetPoolState<T> {
    private val ready = ArrayDeque<T>()
    private val resetting = linkedSetOf<T>()

    val readyCount: Int get() = ready.size
    val retainedCount: Int get() = ready.size + resetting.size

    fun contains(value: T): Boolean = value in ready || value in resetting

    fun acquire(): T? = ready.removeFirstOrNull()

    fun beginReset(value: T, capacity: Int): Boolean {
        if (capacity <= retainedCount || contains(value)) return false
        return resetting.add(value)
    }

    fun completeReset(value: T, retain: Boolean): Boolean {
        if (!resetting.remove(value) || !retain) return false
        ready.addLast(value)
        return true
    }

    fun failReset(value: T): Boolean = resetting.remove(value)

    fun evictAll(): List<T> = buildList {
        while (ready.isNotEmpty()) add(ready.removeFirst())
        addAll(resetting)
        resetting.clear()
    }
}

/** Pure retention policy kept outside Android callbacks for deterministic JVM coverage. */
internal fun canRetainPooledWebView(
    maxIdle: Int,
    idleCount: Int,
    nowMs: Long,
    blockedUntilMs: Long,
    applicationActive: Boolean = true,
): Boolean = webViewPrewarmDecision(
    maxIdle,
    idleCount,
    nowMs,
    blockedUntilMs,
    applicationActive,
) == WebViewPrewarmDecision.WARM

internal fun isWebViewRetentionEligible(
    nowMs: Long,
    blockedUntilMs: Long,
    applicationActive: Boolean = true,
): Boolean = applicationActive && nowMs >= blockedUntilMs

/** Mutable state is isolated from Android objects so foreground/cooldown transitions are testable. */
internal class WebViewRetentionState(initiallyActive: Boolean = true) {
    var applicationActive: Boolean = initiallyActive
        private set
    var blockedUntilMs: Long = 0L
        private set

    fun markActive() {
        applicationActive = true
    }

    fun markInactive() {
        applicationActive = false
    }

    fun suspendUntil(untilMs: Long) {
        if (untilMs > blockedUntilMs) blockedUntilMs = untilMs
    }
}

internal enum class WebViewPrewarmDecision(val wireValue: String) {
    WARM("warmed"),
    CONSTRAINED("constrained"),
    FULL("full"),
    COOLDOWN("cooldown"),
    INACTIVE("inactive"),
    PRESENTATION_ACTIVE("presentation_active"),
}

internal class WebViewPrewarmSkipGate {
    private val reported = HashSet<WebViewPrewarmDecision>()

    fun shouldRecord(decision: WebViewPrewarmDecision): Boolean =
        decision != WebViewPrewarmDecision.WARM && reported.add(decision)
}

internal fun webViewPrewarmDecision(
    maxIdle: Int,
    idleCount: Int,
    nowMs: Long,
    blockedUntilMs: Long,
    applicationActive: Boolean = true,
    readyPresentationActive: Boolean = false,
): WebViewPrewarmDecision = when {
    maxIdle <= 0 -> WebViewPrewarmDecision.CONSTRAINED
    !applicationActive -> WebViewPrewarmDecision.INACTIVE
    readyPresentationActive -> WebViewPrewarmDecision.PRESENTATION_ACTIVE
    idleCount >= maxIdle -> WebViewPrewarmDecision.FULL
    nowMs < blockedUntilMs -> WebViewPrewarmDecision.COOLDOWN
    else -> WebViewPrewarmDecision.WARM
}

/** Keep operation cardinality bounded even if a future caller forwards host-controlled text. */
internal fun canonicalWebViewPrewarmTrigger(trigger: String): String = when (trigger) {
    "minigame_menu", "minigame_game", "interstitial_ready", "rewarded_ready" -> trigger
    else -> "unspecified"
}

internal fun isReadyFullscreenPrewarmTrigger(trigger: String): Boolean =
    trigger == "interstitial_ready" || trigger == "rewarded_ready"

/** Keep acquire diagnostics bounded even if an internal caller forwards dynamic text. */
internal fun canonicalWebViewAcquireSurface(surface: String): String = when (surface) {
    "interstitial", "rewarded" -> surface
    else -> "unspecified"
}

internal enum class WebViewTrimAction { NONE, EVICT_IDLE, EVICT_IDLE_AND_COOLDOWN }

/** UI-hidden is lifecycle cleanup, not pressure; deprecated background levels still signal pressure. */
internal fun webViewTrimAction(level: Int): WebViewTrimAction = when {
    level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> WebViewTrimAction.EVICT_IDLE
    level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW until ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
        WebViewTrimAction.EVICT_IDLE_AND_COOLDOWN
    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> WebViewTrimAction.EVICT_IDLE_AND_COOLDOWN
    else -> WebViewTrimAction.NONE
}

/** Shared pure memory policy for the idle pool and retained native-ad WebViews. */
internal fun isWebViewMemoryConstrained(
    isLowRamDevice: Boolean,
    maxHeapBytes: Long,
): Boolean =
    isLowRamDevice ||
        maxHeapBytes <= CONSTRAINED_HEAP_BYTES

/** Missing capability data fails constrained so speculative retention cannot destabilize the host. */
internal fun resolveWebViewRetentionCapacity(
    isLowRamDevice: Boolean?,
    maxHeapBytes: Long?,
    normalCapacity: Int,
    constrainedCapacity: Int,
): Int = if (
    isLowRamDevice == null ||
    maxHeapBytes == null ||
    isWebViewMemoryConstrained(isLowRamDevice, maxHeapBytes)
) constrainedCapacity else normalCapacity
