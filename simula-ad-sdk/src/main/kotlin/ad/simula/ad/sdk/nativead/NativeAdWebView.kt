package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.ads.CreativeCtaRouter
import ad.simula.ad.sdk.ads.AutomaticNavigationGate
import ad.simula.ad.sdk.bridge.CreativeTelemetryWebChromeClient
import ad.simula.ad.sdk.bridge.CreativeTelemetryWebViewClient
import ad.simula.ad.sdk.bridge.BRIDGE_CAPABILITY_KEY
import ad.simula.ad.sdk.bridge.NATIVE_AD_BRIDGE_MESSAGE_TYPES
import ad.simula.ad.sdk.bridge.authenticatedBridgeMessage
import ad.simula.ad.sdk.bridge.cleanupBeforePooling
import ad.simula.ad.sdk.bridge.parseKnownCreativeBridgeMessage
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.minigame.WebViewTrimAction
import ad.simula.ad.sdk.minigame.repaintOnNextFrame
import ad.simula.ad.sdk.minigame.resolveWebViewRetentionCapacity
import ad.simula.ad.sdk.minigame.webViewTrimAction
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.network.ClickInteraction
import ad.simula.ad.sdk.network.ClickInteractionGate
import ad.simula.ad.sdk.network.ClickSources
import ad.simula.ad.sdk.network.routeClaimedClick
import ad.simula.ad.sdk.telemetry.Telemetry
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import java.util.WeakHashMap
import java.util.UUID

/**
 * Hosts a native-ad creative in a pooled, non-scrollable [WebView] sized to its content.
 *
 * Performance: the [WebView] is owned by [NativeAdWebViewStore], which retains a small LRU of loaded
 * instances keyed by impression id. Fresh mounts are paced to one admission per display frame before
 * acquiring from [WebViewPool], loading the creative, and retaining it; scrolling the slot out
 * **detaches and pauses** that view (preserving its
 * rendered DOM) and scrolling back **reattaches the same view with no reload** — eliminating the
 * blank-then-pop re-render a recycled feed row otherwise shows. The creative is mounted from
 * `rendered_html` (the inline `<iframe srcdoc>`, preferred) and falls back to `iframe_url`. The container
 * grows to the height the creative reports over the JS bridge, with a stable shimmer while waiting.
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
    devMode: Boolean,
    impressionId: String,
    heightDp: Float,
    onHeightPx: (Float) -> Unit,
    onAdClick: (ClickInteraction) -> Unit,
    onLoadError: () -> Unit,
    trackingUrl: String? = null,
    destination: String = "appstore",
    storeUrl: String? = null,
    placeholderDark: Boolean,
    modifier: Modifier = Modifier,
    visibilityRelay: VisibilityRelay? = null,
) {
    val context = LocalContext.current
    // Composition only creates local ownership state. The process-global LRU is not touched until
    // AndroidView.factory runs, so an abandoned composition cannot leave an orphan store entry.
    val owner = remember(impressionId, apiKey) {
        NativeAdWebViewStore.createOwner(context.applicationContext, impressionId, apiKey)
    }
    // Bumped to force a remount of the AndroidView (below) when the creative's render process dies: the
    // dead WebView is torn down by onRelease and the creative is rebuilt by the factory. See
    // [NativeAdWiring.renderGone].
    var generation by remember(owner) { mutableIntStateOf(0) }
    // Point the wiring at the latest callbacks + server CTA routing on each recomposition (cheap;
    // @Volatile fields). The routing is stable for a given impression but re-set here so a retained
    // session that outlives a recompose always reflects the current creative's tracking link.
    owner.updateWiring { wiring ->
        wiring.onHeightPx = onHeightPx
        wiring.onAdClick = onAdClick
        wiring.onLoadError = onLoadError
        wiring.onRenderGone = { generation++ }
        wiring.onPageReady = { visibilityRelay?.flush() }
        wiring.creativeBaseUrl = nativeCreativeInitialPageUrl(iframeUrl, renderedHtml)
        wiring.trackingUrl = trackingUrl
        wiring.destination = destination
        wiring.storeUrl = storeUrl
    }

    // Route the live visible fraction (from the viewability tracker) into this slot's WebView while it
    // is mounted; unbind on dispose so a retained, off-screen creative receives no further onVisibility.
    DisposableEffect(owner, visibilityRelay) {
        visibilityRelay?.bind(
            pusher = { ratio -> owner.session.wiring.pushVisibility(ratio) },
            sampleObserver = { ratio -> owner.session.wiring.observeAutomaticNavigationVisibility(ratio) },
        )
        onDispose { visibilityRelay?.bind(pusher = null, sampleObserver = null) }
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
    DisposableEffect(lifecycleOwner, owner, visibilityRelay) {
        owner.session.wiring.updateAutomaticNavigationActive(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        var wasStopped = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> owner.session.wiring.updateAutomaticNavigationActive(false)
                Lifecycle.Event.ON_STOP -> {
                    owner.session.wiring.updateAutomaticNavigationActive(false)
                    wasStopped = true
                }
                Lifecycle.Event.ON_RESUME -> {
                    owner.session.wiring.updateAutomaticNavigationActive(true)
                    visibilityRelay?.resetDedupe()
                    if (wasStopped) {
                        wasStopped = false
                        val session = owner.session
                        if (session.attached) {
                            val webView = session.webView
                            webView?.repaintOnNextFrame()
                            webView?.onResume()
                            webView?.resumeTimers() // defensive; pauseTimers() is process-global
                            session.wiring.pushForeground()
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            owner.session.wiring.updateAutomaticNavigationActive(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // key(generation): a render-process death bumps [generation], disposing this AndroidView (its
    // onRelease destroys the dead WebView) and recreating it, whose factory rebuilds the creative.
    // generation is stable across scroll/recompose, so normal reattach still reuses the retained view.
    key(generation) {
        val attachment = remember(owner, generation) { NativeAdWebViewStore.createAttachment(owner) }
        var mountAdmitted by remember(owner, generation) { mutableStateOf(false) }
        val prioritizeRetained = remember(owner, generation, iframeUrl, renderedHtml) {
            NativeAdWebViewStore.hasReusableIdleSession(
                impressionId = impressionId,
                apiKey = apiKey,
                iframeUrl = iframeUrl,
                renderedHtml = renderedHtml,
            )
        }
        val sizeModifier = Modifier
            .fillMaxWidth()
            // Keep the same dimensions before and after admission so pacing never shifts the feed.
            .height(if (heightDp > 0f) heightDp.dp else NATIVE_AD_PROVISIONAL_HEIGHT_DP.dp)
        val mountModifier = modifier.then(sizeModifier)
        LaunchedEffect(owner, generation, prioritizeRetained) {
            // Healthy retained views still pass through the frame scheduler so a stale availability
            // check can never create an unpaced WebView, but they move ahead of speculative cold mounts.
            mountAdmitted = NativeAdMountScheduler.awaitPermit(prioritize = prioritizeRetained)
        }
        if (mountAdmitted) {
            AndroidView(
                modifier = mountModifier,
                // Reattaches a retained view or creates/acquires a fresh one, always on main.
                factory = { NativeAdWebViewStore.attach(attachment, context, iframeUrl, renderedHtml, devMode) },
                // Scroll-out: detach + pause + keep the loaded DOM (retained ids); recycle ephemerals/orphans.
                onRelease = { released -> NativeAdWebViewStore.release(attachment, released) },
            )
        } else if (heightDp > 0f) {
            // Do not apply the mount's viewability modifier to a placeholder: it must not earn an
            // impression before the creative is attached.
            NativeAdShimmer(sizeModifier, isDark = placeholderDark)
        } else {
            // The slot-level shimmer covers fresh fills while preserving this provisional footprint.
            Spacer(sizeModifier)
        }
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

    // Resolved once on first [registerTrimCallbacks] (where an app context is available) so the cap
    // can consult the shared low-RAM/heap policy. Defaults to the normal cap until then.
    // Volatile: written on the main thread, read in evictIfNeeded().
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
        /** Exact AndroidView attachment currently owning [webView], if any. */
        var attachment: Attachment? = null
        val attached: Boolean get() = attachment != null
    }

    /** Composition-local owner. Creating one must never mutate [sessions]. */
    class Owner internal constructor(internal val candidate: Session) {
        var session: Session = candidate

        fun updateWiring(update: (NativeAdWiring) -> Unit) {
            update(candidate.wiring)
            if (session !== candidate) update(session.wiring)
        }
    }

    /** One AndroidView instance, used to reject stale/out-of-order release callbacks. */
    class Attachment internal constructor(val owner: Owner) {
        var session: Session? = null
    }

    private val main = Handler(Looper.getMainLooper())

    // Access-ordered so iteration is least-recently-used first (for eviction). Main-thread only.
    private val sessions = LinkedHashMap<String, Session>(4, 0.75f, true)

    @Volatile private var trimRegistered = false

    fun createOwner(appContext: Context, impressionId: String, apiKey: String): Owner =
        Owner(Session(impressionId, apiKey, NativeAdWiring(appContext, apiKey, impressionId)))

    fun createAttachment(owner: Owner): Attachment = Attachment(owner)

    /** Read-only scheduling hint. A false positive is safe because attach still runs after a permit. */
    @MainThread
    fun hasReusableIdleSession(
        impressionId: String,
        apiKey: String,
        iframeUrl: String?,
        renderedHtml: String?,
    ): Boolean {
        if (impressionId.isBlank()) return false
        val session = sessions[impressionId] ?: return false
        return !session.attached &&
            session.apiKey == apiKey &&
            session.webView != null &&
            session.loadedKey == creativeKey(iframeUrl, renderedHtml) &&
            !session.wiring.renderGone &&
            !session.wiring.loadFailed
    }

    /** Return the view to mount: the retained one (already loaded → no reload) or a freshly built one. */
    @MainThread
    fun attach(
        attachment: Attachment,
        hostContext: Context,
        iframeUrl: String?,
        renderedHtml: String?,
        devMode: Boolean,
    ): WebView {
        registerTrimCallbacks(hostContext.applicationContext)
        val requested = attachment.owner.session

        // A keyed AndroidView replacement can attach before the old holder releases. The same owner
        // takes over its own session; the old attachment's later release is ignored by identity.
        if (requested.attachment != null) {
            return mount(requested, attachment, hostContext, iframeUrl, renderedHtml, devMode)
        }

        val retainable = requested.impressionId.isNotBlank()
        val existing = requested.impressionId.takeIf { retainable }?.let(sessions::get)
        val claim = nativeSessionClaim(
            retainable = retainable,
            existingApiKey = existing?.apiKey,
            requestedApiKey = requested.apiKey,
            existingAttached = existing?.attached == true,
            existingIsRequested = existing === requested,
        )
        if (claim == NativeSessionClaim.REUSE) {
            val session = existing ?: requested
            if (session !== requested) session.wiring.adoptCallbacksFrom(requested.wiring)
            attachment.owner.session = session
            return mount(session, attachment, hostContext, iframeUrl, renderedHtml, devMode)
        }

        requested.wiring.loadFailed = false
        val fresh = buildWebView(requested.wiring, hostContext, iframeUrl, renderedHtml, devMode)
        requested.webView = fresh
        requested.loadedKey = creativeKey(iframeUrl, renderedHtml)
        requested.attachment = attachment
        requested.wiring.webView = fresh
        requested.wiring.onAutomaticNavigationAttached()
        attachment.session = requested
        attachment.owner.session = requested

        when (claim) {
            NativeSessionClaim.REGISTER -> sessions[requested.impressionId] = requested
            NativeSessionClaim.REPLACE_IDLE -> {
                val replaced = sessions.put(requested.impressionId, requested)
                if (replaced != null && replaced !== requested) destroy(replaced)
            }
            NativeSessionClaim.REPLACE_ATTACHED -> sessions[requested.impressionId] = requested
            NativeSessionClaim.EPHEMERAL -> Unit
            NativeSessionClaim.REUSE -> Unit
        }
        evictIfNeeded()
        return fresh
    }

    @MainThread
    private fun mount(
        session: Session,
        attachment: Attachment,
        hostContext: Context,
        iframeUrl: String?,
        renderedHtml: String?,
        devMode: Boolean,
    ): WebView {
        val creativeKey = creativeKey(iframeUrl, renderedHtml)
        val retained = session.webView
        // Reuse the retained view only if it is alive (render process intact), actually holds this
        // creative (its load completed — not the about:blank a failed load left behind). A newer
        // attachment from the same owner may take it over before the old holder releases.
        if (retained != null && session.loadedKey == creativeKey &&
            !session.wiring.renderGone && !session.wiring.loadFailed
        ) {
            (retained.context as? MutableContextWrapper)?.baseContext = hostContext // re-home for theming
            (retained.parent as? ViewGroup)?.removeView(retained)                   // clear any stale parent
            retained.webChromeClient = CreativeTelemetryWebChromeClient("character_ad", devMode)
            retained.onResume()
            session.attachment = attachment
            attachment.session = session
            session.wiring.webView = retained // visibility pushes target the live view
            session.wiring.onAutomaticNavigationAttached()
            retained.repaintOnNextFrame() // repaint the stale hardware layer (avoid a black/blank frame)
            evictIfNeeded()
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
            releaseNativeBridgeWebView(retained)
            session.webView = null
            session.loadedKey = null
            session.wiring.webView = null
        }
        // Clear the discarded view's verdict before building; build failures re-arm it.
        session.wiring.loadFailed = false
        val fresh = buildWebView(session.wiring, hostContext, iframeUrl, renderedHtml, devMode)
        // Adopt as the retained instance only if the slot isn't already showing one (don't orphan it).
        session.webView?.takeIf { it !== fresh }?.let(::releaseNativeBridgeWebView)
        session.webView = fresh
        session.loadedKey = creativeKey
        session.attachment = attachment
        attachment.session = session
        session.wiring.webView = fresh
        session.wiring.onAutomaticNavigationAttached()
        evictIfNeeded()
        return fresh
    }

    /** Scroll-out / dispose: retain (detach + pause) the loaded view, or recycle an ephemeral/orphan. */
    @MainThread
    fun release(attachment: Attachment, released: WebView) {
        val session = attachment.session
        if (session == null) {
            releaseNativeBridgeWebView(released)
            return
        }
        val disposition = nativeReleaseDisposition(
            isRegisteredOwner = session.impressionId.isNotBlank() && sessions[session.impressionId] === session,
            isCurrentView = released === session.webView,
            isCurrentAttachment = session.attachment === attachment,
            loadFailed = session.wiring.loadFailed,
            renderGone = session.wiring.renderGone,
            retentionEligible = WebViewPool.isRetentionEligible(),
        )
        // A newer keyed AndroidView already took over this session. Its stale predecessor's view was
        // moved, recycled, or destroyed during takeover, so an out-of-order release is a no-op.
        if (disposition == NativeReleaseDisposition.IGNORE) return
        session.wiring.onAutomaticNavigationDetached()
        // A render-dead current view must be destroyed, never recycled to the pool — a dead view in the
        // pool would hand the next consumer a permanently-blank WebView. (This fires when the slot
        // remounts after onRenderProcessGone: the dead view is disposed here, attach() rebuilds.)
        if (disposition == NativeReleaseDisposition.DESTROY) {
            session.attachment = null
            discardDeadView(session)
            evictIfNeeded()
            return
        }
        // Ephemeral (preview) / orphaned views are recycled, and so is a view whose creative load
        // FAILED (it holds only the about:blank that pre-empted the error page): retaining it would
        // reattach a blank card on remount instead of retrying the load (see NativeAdWiring.loadFailed).
        if (disposition == NativeReleaseDisposition.RECYCLE) {
            releaseNativeBridgeWebView(released)
            if (released === session.webView) {
                session.webView = null
                session.loadedKey = null
                session.wiring.webView = null
                // The failed view is gone — don't let its verdict outlive it and recycle the next
                // (healthy, mid-load) retry too. Mirrors discardDeadView clearing renderGone.
                session.wiring.loadFailed = false
            }
            if (session.attachment === attachment) session.attachment = null
            evictIfNeeded()
            return
        }
        session.attachment = null
        (released.parent as? ViewGroup)?.removeView(released)
        released.onPause() // suspend the creative's JS/rendering while off-screen (per-instance; no global timers)
        // Drop the Activity reference so a retained, off-screen view can't leak it.
        (released.context as? MutableContextWrapper)?.let { it.baseContext = it.applicationContext }
        // The store can temporarily exceed its cap while every session is attached. Enforce the cap
        // as soon as one becomes idle; attached/on-screen views remain ineligible for eviction.
        evictIfNeeded()
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
        session.attachment = null
        session.wiring.webView = null
        session.wiring.renderGone = false
        session.wiring.loadFailed = false // a failed-then-render-dead view must not leave the flag sticky
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
        val plan = retainedIdleEvictionKeys(
            sessions.entries.map { it.key to it.value.attached },
            maxRetained = 0,
        )
        for (key in plan) {
            sessions.remove(key)?.let(::destroy)
        }
    }

    @MainThread
    private fun buildWebView(
        wiring: NativeAdWiring,
        hostContext: Context,
        iframeUrl: String?,
        renderedHtml: String?,
        devMode: Boolean,
    ): WebView {
        val docStart = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)
        val bridgeCapability = UUID.randomUUID().toString()
        val client = NativeAdWebViewClient(wiring, bridgeCapability)
        val webView = WebViewPool.acquire(
            hostContext,
            client,
        )
        webView.webChromeClient = CreativeTelemetryWebChromeClient("character_ad", devMode)
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
        val injectionMode = installBridge(webView, wiring, docStart, bridgeCapability)
        client.setBridgeInjectionMode(injectionMode)
        if (injectionMode == NativeBridgeInjectionMode.UNAVAILABLE) {
            wiring.loadFailed = true
            runCatching { wiring.onLoadError() }
            return webView
        }
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
            // Never recycle a render-dead view to the pool — destroy it outright.
            if (session.wiring.renderGone) {
                uninstallBridge(it)
                it.destroy()
            } else {
                releaseNativeBridgeWebView(it)
            }
        }
        session.webView = null
        session.loadedKey = null
        session.attachment = null
        session.wiring.webView = null
        session.wiring.renderGone = false
        session.wiring.loadFailed = false
    }

    @MainThread
    private fun evictIfNeeded() {
        val plan = retainedIdleEvictionKeys(
            sessions.entries.map { it.key to it.value.attached },
            maxRetained = maxRetained,
        )
        for (key in plan) {
            sessions.remove(key)?.let(::destroy)
        }
    }

    /** Pick the retained-view cap once per process: 1 on a constrained device, else 3. */
    @MainThread
    private fun resolveMaxRetained(appContext: Context) {
        val capabilities = runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return@runCatching null
            am.isLowRamDevice to Runtime.getRuntime().maxMemory()
        }.getOrNull()
        maxRetained = resolveWebViewRetentionCapacity(
            isLowRamDevice = capabilities?.first,
            maxHeapBytes = capabilities?.second,
            normalCapacity = MAX_RETAINED,
            constrainedCapacity = MAX_RETAINED_LOW_RAM,
        )
    }

    private fun registerTrimCallbacks(appContext: Context) {
        if (trimRegistered) return
        trimRegistered = true
        resolveMaxRetained(appContext)
        appContext.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                when (webViewTrimAction(level)) {
                    WebViewTrimAction.EVICT_IDLE -> {
                        evictAll()
                        // This callback may run after the pool already handled UI-hidden. A retained
                        // session released above can refill that pool, so drain once more here.
                        WebViewPool.trimIdle()
                    }
                    WebViewTrimAction.EVICT_IDLE_AND_COOLDOWN -> {
                        WebViewPool.suspendRetention()
                        evictAll()
                    }
                    WebViewTrimAction.NONE -> Unit
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                WebViewPool.suspendRetention()
                evictAll()
            }

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

/**
 * Pure LRU eviction plan. [accessOrder] is oldest-first and the Boolean is `attached`; attached
 * views are never selected, even if they alone exceed the cap. Re-run after every release so a
 * temporary all-attached overflow is corrected the moment an idle candidate exists.
 */
internal fun retainedIdleEvictionKeys(
    accessOrder: List<Pair<String, Boolean>>,
    maxRetained: Int,
): List<String> {
    var remaining = accessOrder.size
    val keys = ArrayList<String>()
    for ((key, attached) in accessOrder) {
        if (remaining <= maxRetained.coerceAtLeast(0)) break
        if (!attached) {
            keys.add(key)
            remaining--
        }
    }
    return keys
}

internal enum class NativeSessionClaim {
    REGISTER,
    REUSE,
    REPLACE_IDLE,
    REPLACE_ATTACHED,
    EPHEMERAL,
}

/** Pure ownership plan used by attach before it commits a session to the process-global store. */
internal fun nativeSessionClaim(
    retainable: Boolean,
    existingApiKey: String?,
    requestedApiKey: String,
    existingAttached: Boolean,
    existingIsRequested: Boolean,
): NativeSessionClaim = when {
    !retainable -> NativeSessionClaim.EPHEMERAL
    existingApiKey == null -> NativeSessionClaim.REGISTER
    existingIsRequested -> NativeSessionClaim.REUSE
    existingApiKey == requestedApiKey && !existingAttached -> NativeSessionClaim.REUSE
    existingApiKey == requestedApiKey -> NativeSessionClaim.EPHEMERAL
    existingAttached -> NativeSessionClaim.REPLACE_ATTACHED
    else -> NativeSessionClaim.REPLACE_IDLE
}

internal enum class NativeReleaseDisposition { IGNORE, RETAIN, RECYCLE, DESTROY }

/** Pure release policy: only the registered current view may survive, and never during cooldown. */
internal fun nativeReleaseDisposition(
    isRegisteredOwner: Boolean,
    isCurrentView: Boolean,
    isCurrentAttachment: Boolean,
    loadFailed: Boolean,
    renderGone: Boolean,
    retentionEligible: Boolean,
): NativeReleaseDisposition = when {
    !isCurrentAttachment -> NativeReleaseDisposition.IGNORE
    isCurrentView && renderGone -> NativeReleaseDisposition.DESTROY
    isRegisteredOwner && isCurrentView && !loadFailed && retentionEligible -> NativeReleaseDisposition.RETAIN
    else -> NativeReleaseDisposition.RECYCLE
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
    @Volatile var onAdClick: (ClickInteraction) -> Unit = {}
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
    // The verdict describes one specific view, so it is cleared wherever that view is discarded and
    // the session moves on (attach's rebuild-adopt, release's recycle, discardDeadView, destroy) —
    // NOT in onPageFinished, which Android also fires for the failed URL (see the gate there).
    // Mirrors the Swift store's `unusable` flag.
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
    @Volatile var creativeBaseUrl: String? = null
    @Volatile var destination: String = "appstore"
    @Volatile var storeUrl: String? = null
    @Volatile var automaticNavigationActive: Boolean = false
    @Volatile private var automaticNavigationAttached: Boolean = false
    @Volatile private var automaticVisibilityFraction: Float = -1f
    private var automaticNavigationEligible = false
    private val clickInteractionGate = ClickInteractionGate()
    private val automaticNavigationGate = AutomaticNavigationGate()
    private val automaticNavigationVisibleRect = Rect()
    private var focusObservedView: WebView? = null
    private val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (!hasFocus) {
            automaticNavigationEligible = false
        } else {
            dispatchOnAutomaticNavigationEligibilityEdge()
        }
    }

    fun adoptCallbacksFrom(other: NativeAdWiring) {
        onHeightPx = other.onHeightPx
        onAdClick = other.onAdClick
        onLoadError = other.onLoadError
        onRenderGone = other.onRenderGone
        onPageReady = other.onPageReady
        creativeBaseUrl = other.creativeBaseUrl
        trackingUrl = other.trackingUrl
        destination = other.destination
        storeUrl = other.storeUrl
        automaticNavigationActive = other.automaticNavigationActive
    }

    // The WebView currently displaying this wiring's creative; set by the store on (re)attach and
    // cleared on release/destroy. Used by [pushVisibility] to reach the live view.
    @Volatile var webView: WebView? = null

    private val main = Handler(Looper.getMainLooper())
    /**
     * Forward the slot's live visible fraction (0..1) to the creative via `window.onVisibility`, called
     * on every scroll frame by the viewability tracker. Guarded so it's a no-op until the creative
     * defines the function. Main-thread only — `evaluateJavascript` must run on the WebView's thread,
     * and the relay that drives this already reports from the main dispatcher.
     */
    @MainThread
    fun pushVisibility(ratio: Float) {
        val clamped = ratio.coerceIn(0f, 1f)
        val r = String.format(java.util.Locale.US, "%.2f", clamped)
        webView?.evaluateJavascript("window.onVisibility&&window.onVisibility($r)", null)
    }

    fun observeAutomaticNavigationVisibility(ratio: Float) {
        automaticVisibilityFraction = ratio.coerceIn(0f, 1f)
        dispatchOnAutomaticNavigationEligibilityEdge()
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

    /** Called off-main from the JS interface. Admits known bounded messages before main dispatch. */
    fun handleMessage(raw: String) {
        val obj = parseKnownCreativeBridgeMessage(raw, NATIVE_AD_BRIDGE_MESSAGE_TYPES) ?: return
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
    fun handleNavigation(tappedUrl: String, currentPageUrl: String?): Boolean {
        val route = when (val plan = CreativeCtaRouter.primaryCtaTapPlan(
            tappedUrl = tappedUrl,
            creativeBaseUrl = CreativeCtaRouter.admittedHttpUrl(currentPageUrl) ?: creativeBaseUrl,
            trackingUrl = trackingUrl,
            destination = destination,
        )) {
            CreativeCtaRouter.PrimaryCtaTapPlan.AllowInWebView -> return false
            CreativeCtaRouter.PrimaryCtaTapPlan.ConsumeWithoutClick -> return true
            is CreativeCtaRouter.PrimaryCtaTapPlan.Route -> plan.route
        }
        routeClaimedClick(
            claim = clickInteractionGate.claim(ClickSources.PRIMARY_CTA),
            open = {
                CreativeCtaRouter.openPrimaryCta(
                    appContext,
                    route,
                    destination,
                    storeUrl = storeUrl,
                )
            },
            onOpened = { interaction ->
                automaticNavigationGate.suppressPending()
                onAdClick(interaction)
            },
        )
        return true
    }

    fun handleAutomaticNavigation(source: WebView, targetUrl: String, currentPageUrl: String?): Boolean {
        return when (val plan = CreativeCtaRouter.automaticNavigationPlan(
            value = targetUrl,
            destination = destination,
            trackingUrl = trackingUrl,
        )) {
            CreativeCtaRouter.AutomaticNavigationPlan.AllowInWebView -> false
            CreativeCtaRouter.AutomaticNavigationPlan.Consume -> true
            is CreativeCtaRouter.AutomaticNavigationPlan.RouteExact -> {
                if (source !== webView) return true
                automaticNavigationGate.retain(
                    plan.targetUrl,
                    automaticNavigationGate.wasTrackerRequestedInWebView() ||
                        CreativeCtaRouter.matchesKnownTrackingUrl(currentPageUrl, trackingUrl),
                )
                if (currentAutomaticNavigationEligible()) dispatchPendingAutomaticNavigation()
                true
            }
        }
    }

    private fun dispatchPendingAutomaticNavigation() {
        if (!currentAutomaticNavigationEligible()) return
        automaticNavigationGate.attemptPending { route ->
            CreativeCtaRouter.openAutomaticNavigation(
                appContext,
                route.targetUrl,
                destination,
                trackingUrl,
                route.trackerAlreadyRequested,
            )
        }
    }

    private fun dispatchOnAutomaticNavigationEligibilityEdge() {
        val eligible = currentAutomaticNavigationEligible()
        val shouldDispatch = eligible && !automaticNavigationEligible
        automaticNavigationEligible = eligible
        if (shouldDispatch) dispatchPendingAutomaticNavigation()
    }

    private fun currentAutomaticNavigationEligible(): Boolean {
        val currentView = webView ?: return false
        automaticNavigationVisibleRect.setEmpty()
        val globallyVisibleFraction = runCatching {
            currentView.getGlobalVisibleRect(automaticNavigationVisibleRect) &&
                !automaticNavigationVisibleRect.isEmpty && currentView.width > 0 && currentView.height > 0
        }.getOrDefault(false).let { visible ->
            if (!visible) 0f else {
                val visibleArea = automaticNavigationVisibleRect.width().toLong() *
                    automaticNavigationVisibleRect.height().toLong()
                val totalArea = currentView.width.toLong() * currentView.height.toLong()
                (visibleArea.toDouble() / totalArea.toDouble()).toFloat().coerceIn(0f, 1f)
            }
        }
        return nativeAutomaticNavigationEligible(
            lifecycleActive = automaticNavigationActive,
            currentOwner = currentView === webView,
            logicallyAttached = automaticNavigationAttached,
            attachedToWindow = currentView.isAttachedToWindow,
            shown = currentView.isShown,
            windowFocused = currentView.hasWindowFocus(),
            globallyVisibleFraction = globallyVisibleFraction,
            visibleFraction = automaticVisibilityFraction,
        )
    }

    fun updateAutomaticNavigationActive(active: Boolean) {
        automaticNavigationActive = active
        automaticVisibilityFraction = -1f
        automaticNavigationEligible = false
    }

    fun onAutomaticNavigationAttached() {
        automaticNavigationAttached = true
        automaticVisibilityFraction = -1f
        automaticNavigationEligible = false
        val currentView = webView
        if (focusObservedView !== currentView) {
            focusObservedView?.let { observed ->
                runCatching { observed.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocusListener) }
            }
            focusObservedView = currentView
            currentView?.let { observed ->
                runCatching { observed.viewTreeObserver.addOnWindowFocusChangeListener(windowFocusListener) }
            }
        }
    }

    fun onAutomaticNavigationDetached() {
        automaticNavigationAttached = false
        automaticVisibilityFraction = -1f
        automaticNavigationEligible = false
        focusObservedView?.let { observed ->
            runCatching { observed.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocusListener) }
        }
        focusObservedView = null
    }

    fun observeNavigationStarted(url: String?) {
        if (CreativeCtaRouter.matchesKnownTrackingUrl(url, trackingUrl)) {
            automaticNavigationGate.markTrackerRequestedInWebView()
        }
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
    private var sampleObserver: ((Float) -> Unit)? = null
    /** Last ratio actually pushed (dedupe baseline). -1 = nothing pushed yet. */
    private var last = -1f
    /** Latest ratio the tracker reported, whether or not the push reached the page. -1 = no sample yet. */
    private var latest = -1f

    /** Point the relay at the live WebView's pusher (or null to detach on dispose). */
    @MainThread
    fun bind(pusher: ((Float) -> Unit)?, sampleObserver: ((Float) -> Unit)? = null) {
        this.pusher = pusher
        this.sampleObserver = sampleObserver
        last = -1f
    }

    /** Forward a 0..1 ratio, de-duped against the last forwarded value (~1% granularity). */
    @MainThread
    fun report(ratio: Float) {
        val r = ratio.coerceIn(0f, 1f)
        latest = r
        sampleObserver?.invoke(r)
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

private class NativeAdJsInterface(
    private val wiring: NativeAdWiring,
    private val bridgeCapability: String,
) {
    @JavascriptInterface
    fun postMessage(json: String?) {
        // Runs on the WebView's JS thread. Declared nullable + no-op on null so a malformed JS
        // bridge invocation passing null can't NPE on entry before reaching handleMessage.
        json ?: return
        val authenticated = authenticatedBridgeMessage(json, bridgeCapability) ?: return
        wiring.handleMessage(authenticated)
    }
}

private class NativeAdWebViewClient(
    private val wiring: NativeAdWiring,
    private val bridgeCapability: String,
) : CreativeTelemetryWebViewClient("character_ad") {
    private val main = Handler(Looper.getMainLooper())
    private var bridgeInjectionMode = NativeBridgeInjectionMode.UNAVAILABLE

    fun setBridgeInjectionMode(mode: NativeBridgeInjectionMode) {
        bridgeInjectionMode = mode
    }

    // Framework callback params are declared nullable to match the platform override signatures and
    // guard against a non-conformant OEM WebView passing null (which would NPE on a non-null param) —
    // mirroring the interstitial/rewarded clients.
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon) // starts the page-load timer
        wiring.observeNavigationStarted(url)
        view ?: return
        if (bridgeInjectionMode == NativeBridgeInjectionMode.PAGE_START_FALLBACK) {
            runCatching { view.evaluateJavascript(nativeBridgeScript(bridgeCapability), null) }
                .onFailure {
                    Telemetry.recordError(
                        signature = "native_bridge:page_start_fallback_failed",
                        errorCode = it::class.java.simpleName,
                    )
                }
        }
    }

    // A clean load means the (possibly just-rebuilt) creative is healthy again — reset the render-death
    // strike count so a later, unrelated render kill still earns a fresh rebuild.
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url) // records page-load timing
        // Android delivers onPageFinished for the FAILED URL too (right after onReceivedError /
        // onReceivedHttpError, before the about:blank pre-emption commits), so the success path must
        // be gated on the failure verdict — otherwise it would immediately wipe [loadFailed] and the
        // store would retain and silently reattach the blank view instead of retrying on remount.
        // The flag is reliably false on a real success: every rebuild clears it when the fresh view
        // is adopted (see attach), so it can only be true here when the load actually failed.
        // Mirrors the Swift coordinator's mainFrameHTTPFailed gate on didFinish.
        if (url != null && url != "about:blank" && !wiring.loadFailed) {
            wiring.renderGoneStrikes = 0
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
            return wiring.handleNavigation(url, view.url)
        }
        return wiring.handleAutomaticNavigation(view, url, view.url)
    }
}

internal fun nativeAutomaticNavigationEligible(
    lifecycleActive: Boolean,
    currentOwner: Boolean,
    logicallyAttached: Boolean,
    attachedToWindow: Boolean,
    shown: Boolean,
    windowFocused: Boolean,
    globallyVisibleFraction: Float,
    visibleFraction: Float,
): Boolean = lifecycleActive && currentOwner && logicallyAttached && attachedToWindow && shown &&
    windowFocused && globallyVisibleFraction >= NATIVE_AUTOMATIC_NAVIGATION_MIN_VISIBLE &&
    visibleFraction >= NATIVE_AUTOMATIC_NAVIGATION_MIN_VISIBLE

private const val NATIVE_AUTOMATIC_NAVIGATION_MIN_VISIBLE = 0.5f

private const val NATIVE_BRIDGE_OBJECT = "SimulaNativeBridge"

/** Max consecutive render-process deaths (with no successful load in between) the SDK rebuilds a native
 * creative through before giving up and collapsing the slot — a crash-loop backstop. */
private const val MAX_RENDER_RECOVERIES = 2

/** Document-start scripts per WebView, removed on release so a pooled view never accumulates them. */
private val scriptHandlers = WeakHashMap<WebView, ScriptHandler>()

internal enum class NativeBridgeInjectionMode { DOCUMENT_START, PAGE_START_FALLBACK, UNAVAILABLE }

internal fun nativeCreativeInitialPageUrl(iframeUrl: String?, renderedHtml: String?): String? =
    iframeUrl.takeIf { renderedHtml.isNullOrBlank() }

internal fun nativeBridgeInjectionMode(
    cleanupConfirmed: Boolean,
    interfaceInstalled: Boolean,
    documentStartSupported: Boolean,
    documentStartInstalled: Boolean,
): NativeBridgeInjectionMode = when {
    !cleanupConfirmed || !interfaceInstalled -> NativeBridgeInjectionMode.UNAVAILABLE
    documentStartSupported && documentStartInstalled -> NativeBridgeInjectionMode.DOCUMENT_START
    else -> NativeBridgeInjectionMode.PAGE_START_FALLBACK
}

private fun installBridge(
    webView: WebView,
    wiring: NativeAdWiring,
    documentStartSupported: Boolean,
    bridgeCapability: String,
): NativeBridgeInjectionMode {
    val cleanupConfirmed = uninstallBridge(webView)
    if (!cleanupConfirmed) return NativeBridgeInjectionMode.UNAVAILABLE
    val interfaceInstalled = runCatching {
        webView.addJavascriptInterface(
            NativeAdJsInterface(wiring, bridgeCapability),
            NATIVE_BRIDGE_OBJECT,
        )
    }.onFailure {
        Telemetry.recordError(
            signature = "native_bridge:javascript_interface_failed",
            errorCode = it::class.java.simpleName,
        )
    }.isSuccess
    if (!interfaceInstalled) return NativeBridgeInjectionMode.UNAVAILABLE
    val documentStartInstalled = if (documentStartSupported) {
        val handler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                nativeBridgeScript(bridgeCapability),
                setOf("*"),
            )
        }.onFailure {
            Telemetry.recordError(
                signature = "native_bridge:document_start_failed",
                errorCode = it::class.java.simpleName,
            )
        }.getOrNull()
        if (handler != null) scriptHandlers[webView] = handler
        handler != null
    } else {
        Telemetry.recordError(signature = "native_bridge:document_start_unavailable")
        false
    }
    return nativeBridgeInjectionMode(
        cleanupConfirmed = cleanupConfirmed,
        interfaceInstalled = interfaceInstalled,
        documentStartSupported = documentStartSupported,
        documentStartInstalled = documentStartInstalled,
    )
}

private fun uninstallBridge(webView: WebView): Boolean {
    val interfaceRemoved = runCatching { webView.removeJavascriptInterface(NATIVE_BRIDGE_OBJECT) }.isSuccess
    val handler = scriptHandlers[webView]
    val scriptRemoved = handler?.let { runCatching { it.remove() }.isSuccess } ?: true
    if (scriptRemoved) scriptHandlers.remove(webView)
    return interfaceRemoved && scriptRemoved
}

private fun releaseNativeBridgeWebView(webView: WebView) {
    cleanupBeforePooling(
        cleanup = { uninstallBridge(webView) },
        release = { WebViewPool.release(webView) },
        discard = {
            scriptHandlers.remove(webView)
            WebViewPool.discard(webView)
        },
    )
}

/**
 * Injected into the creative: relays `window.postMessage` envelopes (the AD badge menu's
 * `AD_FEEDBACK`) to native, and — top frame only — reports content height so the SDK can size its
 * container. Mirrors the iOS injected script.
 */
internal fun nativeBridgeScript(bridgeCapability: String): String = """
    (function () {
      'use strict';
      var bridgeCapability = ${JsonPrimitive(bridgeCapability)};
      var nativeStringify = JSON.stringify.bind(JSON);
      var nativeParse = JSON.parse.bind(JSON);
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
        // Hook the observer on documentElement, not body: at document-start injection (Android)
        // body is still null and no iframes exist yet, so a body-gated observer would never be
        // installed and nothing would re-run the lock for the srcdoc iframe parsed later.
        // documentElement exists from the first script tick; subtree:true covers body + iframes.
        var root = document.documentElement;
        if (window.MutationObserver && root && !root.__simulaNoScrollMO) {
          root.__simulaNoScrollMO = true;
          new MutationObserver(lockAll).observe(root, { childList: true, subtree: true });
        }
      } catch (e) {}
      // Belt-and-braces: sweep once more when parsing completes.
      try { document.addEventListener('DOMContentLoaded', lockAll); } catch (e) {}

      function bridge() { return window.$NATIVE_BRIDGE_OBJECT; }

      // Relay the creative's window.postMessage (e.g. AD_FEEDBACK) to native.
      window.addEventListener('message', function (e) {
        if (window.top !== window.self || !e || e.isTrusted !== true || e.source !== window) return;
        var d = e && e.data;
        if (!d) return;
        try {
          var envelope = typeof d === 'string' ? nativeParse(d) : d;
          if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope)) return;
          var serialized = nativeStringify(envelope);
          if (!serialized || serialized.charAt(0) !== '{') return;
          if (bridge()) bridge().postMessage(
            '{"$BRIDGE_CAPABILITY_KEY":' + nativeStringify(bridgeCapability) +
            ',' + serialized.substring(1)
          );
        } catch (err) {}
      });

      // Report content height (top frame only) so the SDK resizes the slot to fit. Debounced so a
      // creative that animates / settles its layout posts a stable height instead of streaming
      // intermediate values that would thrash the host feed's layout.
      if (window.top === window.self) {
        var lastH = 0, timer = null;
        var measure = function () {
          // Report nothing until the page has FULLY loaded (readyState 'complete', i.e. the window
          // load event — which the srcdoc iframe's own content, images included, delays). Mid-load
          // the DOM measures its scaffolding, not the creative: the creative <iframe> sits at the
          // 150 CSS px iframe default until its inner content loads and resizes it, so an early
          // report dismisses the shimmer at ~150dp and the card then visibly expands when the real
          // height lands ("renders half, then the rest"). This is also what keeps Android's first
          // measurement at the same point as iOS, whose script is only injected after didFinish —
          // WebKit fires that at the same all-subresources-loaded moment gated on here.
          if (document.readyState !== 'complete') return 0;
          var b = document.body;
          if (!b) return 0;
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
          // Child-less body → scrollHeight fallback (a text-only creative). An empty page (e.g. the
          // about:blank a failed load leaves behind) reports 0 through it (empty body → scrollHeight
          // 0), so it can't pass the h > 0 guard and cache a bogus slot height.
          var raw = Math.ceil(max) || b.scrollHeight;
          if (!(raw > 0)) return 0;
          // Viewport-echo guard (parity with the iOS script): a 100%/100vh child (or the child-less
          // scrollHeight fallback above) doesn't measure content — it reflects whatever height the
          // SDK just gave the WebView. Report it verbatim — identical to lastH after a resize, so
          // the +1 cushion can't ratchet the slot taller on every resize→measure cycle.
          var vh = window.innerHeight || 0;
          if (vh > 0 && Math.abs(raw - vh) <= 2) return vh;
          // +1dp cushion so sub-pixel layout can't leave the content taller than the view (a tiny
          // scrollable overflow at the bottom). Mirrors the iOS height script.
          return raw + 1;
        };
        var send = function () {
          try {
            var h = measure();
            if (h > 0 && Math.abs(h - lastH) >= 1 && bridge()) {
              lastH = h;
              bridge().postMessage(
                '{"type":"SIMULA_AD_HEIGHT","height":' + nativeStringify(h) +
                ',"$BRIDGE_CAPABILITY_KEY":' + nativeStringify(bridgeCapability) + '}'
              );
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
