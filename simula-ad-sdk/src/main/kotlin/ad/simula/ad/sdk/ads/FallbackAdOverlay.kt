package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.bridge.recordRenderProcessGone
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.minigame.repaintOnNextFrame
import ad.simula.ad.sdk.model.AutoStoreRedirect
import ad.simula.ad.sdk.model.endScreenTriggerForIndex
import ad.simula.ad.sdk.network.AutoRedirectCoordinator
import ad.simula.ad.sdk.network.AdBeaconManager
import ad.simula.ad.sdk.network.BeaconPersistenceOutcome
import ad.simula.ad.sdk.network.ClickRouteStart
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.network.ClickInteraction
import ad.simula.ad.sdk.network.ClickInteractionClaim
import ad.simula.ad.sdk.network.ClickInteractionGate
import ad.simula.ad.sdk.network.ClickPersistenceHandoff
import ad.simula.ad.sdk.network.ClickSources
import ad.simula.ad.sdk.network.PrimaryCtaDocumentAdmission
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlinx.coroutines.delay

internal enum class FallbackStage { CONTENT, FETCHING, SHOWING, DONE }
private const val FALLBACK_FETCH_ATTEMPTS = 2
private const val FALLBACK_FETCH_RETRY_MS = 250L
internal const val FALLBACK_POST_CLOSE_WAIT_MS = 2_000L

internal fun fallbackClickBeaconImpressionId(adId: String, serverEnabled: Boolean): String? =
    adId.takeIf { serverEnabled && it.isNotBlank() }

internal fun enqueueOwnedFallbackClickBeacon(
    adId: String,
    serverEnabled: Boolean,
    completion: (BeaconPersistenceOutcome) -> Unit,
    enqueue: (String) -> Unit,
) {
    val beaconId = fallbackClickBeaconImpressionId(adId, serverEnabled)
    if (beaconId == null) completion(BeaconPersistenceOutcome.Persisted) else enqueue(beaconId)
}

internal fun fallbackNavigationOverride(
    clickHandoffPending: Boolean,
    documentAdmissionEnabled: Boolean,
    fallbackNavigationStarted: Boolean,
): Boolean? = when {
    clickHandoffPending -> true
    !documentAdmissionEnabled && !fallbackNavigationStarted -> true
    !documentAdmissionEnabled -> false
    else -> null
}

internal class FallbackPresentationState(
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
) {
    var stage: FallbackStage = FallbackStage.CONTENT
        private set
    var index: Int = 0
        private set
    var clickHandoffPending by mutableStateOf(false)
        private set
    var fetchedAds: List<SimulaApiClient.FallbackAd>? = null
        private set
    private val clickAdmissions = LinkedHashMap<Int, PrimaryCtaDocumentAdmission>()
    private var navigationOwner: Any? = null
    private var navigateInWebView: ((String) -> Boolean)? = null
    private var pendingNavigationUrl: String? = null
    private var fallbackNavigationStarted = false
    private var deliveryInProgress = false
    private var cleared = false
    private var fetchWaitGeneration = 0L
    private var fetchWaitDeadlineMs = 0L

    fun showing(index: Int) {
        val nextIndex = index.coerceAtLeast(0)
        if (stage == FallbackStage.SHOWING && this.index != nextIndex) {
            pendingNavigationUrl = null
            fallbackNavigationStarted = false
            deliveryInProgress = false
        }
        stage = FallbackStage.SHOWING
        this.index = nextIndex
    }
    fun done() { stage = FallbackStage.DONE }
    fun setClickPending(pending: Boolean) {
        synchronized(this) {
            if (cleared) return
            clickHandoffPending = pending
            if (!pending && pendingNavigationUrl == null) fallbackNavigationStarted = true
        }
        if (!pending) dispatchReadyNavigation()
    }
    fun retainFetchedAds(ads: List<SimulaApiClient.FallbackAd>) { fetchedAds = ads }
    fun fetchFailed() = Unit
    fun terminalizeInitialFetchFailure(): List<SimulaApiClient.FallbackAd> {
        val retained = fetchedAds
        if (retained != null) return retained
        return emptyList<SimulaApiClient.FallbackAd>().also(::retainFetchedAds)
    }
    fun clickAdmission(index: Int): PrimaryCtaDocumentAdmission =
        clickAdmissions.getOrPut(index.coerceAtLeast(0)) { PrimaryCtaDocumentAdmission() }

    fun startPostCloseFetchWait(): Long {
        if (stage != FallbackStage.FETCHING) {
            fetchWaitGeneration++
            fetchWaitDeadlineMs = clockMs() + FALLBACK_POST_CLOSE_WAIT_MS
            stage = FallbackStage.FETCHING
        }
        return fetchWaitGeneration
    }

    fun retainedPostCloseFetchWait(): Long =
        if (stage == FallbackStage.FETCHING) fetchWaitGeneration else startPostCloseFetchWait()

    fun postCloseFetchWaitRemainingMs(generation: Long): Long? {
        if (stage != FallbackStage.FETCHING || generation != fetchWaitGeneration) return null
        return (fetchWaitDeadlineMs - clockMs()).coerceAtLeast(0L)
    }

    fun resolvePostCloseFetchWait(
        generation: Long,
        ads: List<SimulaApiClient.FallbackAd>,
    ): Boolean {
        if (stage != FallbackStage.FETCHING || generation != fetchWaitGeneration) return false
        if (ads.isNotEmpty()) showing(0) else done()
        return true
    }

    fun timeoutPostCloseFetchWait(generation: Long): Boolean {
        if (stage != FallbackStage.FETCHING || generation != fetchWaitGeneration) return false
        done()
        return true
    }

    fun bindNavigation(owner: Any, navigate: (String) -> Boolean) {
        synchronized(this) {
            if (cleared) return
            navigationOwner = owner
            navigateInWebView = navigate
        }
        dispatchReadyNavigation()
    }

    @Synchronized
    fun unbindNavigation(owner: Any) {
        if (navigationOwner === owner) {
            navigationOwner = null
            navigateInWebView = null
        }
    }

    fun retainNavigation(url: String): Boolean {
        synchronized(this) {
            if (cleared || url.isBlank()) return false
            if (pendingNavigationUrl == null) pendingNavigationUrl = url
        }
        dispatchReadyNavigation()
        return true
    }

    @Synchronized
    fun hasRetainedNavigation(): Boolean = pendingNavigationUrl != null

    @Synchronized
    fun clear() {
        cleared = true
        clickHandoffPending = false
        pendingNavigationUrl = null
        fallbackNavigationStarted = false
        deliveryInProgress = false
        navigationOwner = null
        navigateInWebView = null
        clickAdmissions.clear()
    }

    private fun dispatchReadyNavigation() {
        val delivery = synchronized(this) {
            if (cleared || clickHandoffPending || deliveryInProgress) return
            val navigate = navigateInWebView ?: return
            val url = pendingNavigationUrl ?: return
            deliveryInProgress = true
            navigate to url
        }
        val delivered = runCatching { delivery.first(delivery.second) }.getOrDefault(false)
        synchronized(this) {
            deliveryInProgress = false
            if (delivered && pendingNavigationUrl == delivery.second) {
                pendingNavigationUrl = null
                fallbackNavigationStarted = true
            }
        }
    }

    @Synchronized
    fun navigationOverride(documentAdmissionEnabled: Boolean): Boolean? = fallbackNavigationOverride(
        clickHandoffPending = clickHandoffPending,
        documentAdmissionEnabled = documentAdmissionEnabled,
        fallbackNavigationStarted = fallbackNavigationStarted,
    )

    @Synchronized
    fun advance(total: Int): Boolean {
        if (clickHandoffPending || pendingNavigationUrl != null || deliveryInProgress ||
            stage != FallbackStage.SHOWING
        ) return false
        if (index + 1 < total) showing(index + 1) else done()
        return true
    }
}

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
    adFormat: String = "interstitial",
    presentationState: FallbackPresentationState = remember { FallbackPresentationState() },
    onFullyClosed: () -> Unit,
    autoStoreRedirect: AutoStoreRedirect? = null,
    onAutoStoreRedirect: () -> Boolean = { false },
    onAdClick: (ClickInteraction) -> Unit = {},
    onStoreOpen: (ClickInteraction) -> Unit = {},
    persistClick: (ClickInteraction, () -> Unit) -> Unit = { _, complete -> complete() },
    claimClick: ((String) -> ClickInteractionClaim?)? = null,
    routeClick: (((Context) -> Boolean, (Boolean) -> Unit) -> ClickRouteStart)? = null,
    onClickHandoffCreated: (ClickPersistenceHandoff) -> Unit = {},
    onClickHandoffFinished: (ClickPersistenceHandoff) -> Unit = {},
    autoRedirectCoordinator: AutoRedirectCoordinator? = null,
    pendingClickHandoff: () -> ClickPersistenceHandoff? = { null },
    // The primary serve's CTA routing context, threaded into each end screen so its CTA opens
    // through the shared router (tracker verbatim, raw store link as the deterministic fallback).
    // Defaults preserve today's behavior when no context is available.
    ctaTrackingUrl: String? = null,
    ctaDestination: String = "appstore",
    ctaStoreUrl: String? = null,
    content: @Composable (onClose: () -> Unit) -> Unit,
) {
    var phase by remember(presentationState) {
        mutableStateOf<FallbackPhase>(
            when (presentationState.stage) {
                FallbackStage.CONTENT -> FallbackPhase.Content
                FallbackStage.FETCHING -> FallbackPhase.Fetching(
                    presentationState.retainedPostCloseFetchWait(),
                )
                FallbackStage.SHOWING -> presentationState.fetchedAds
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { ads ->
                        val index = presentationState.index.coerceIn(0, ads.lastIndex)
                        FallbackPhase.Showing(ads, index)
                    }
                    ?: FallbackPhase.Done.also { presentationState.done() }
                FallbackStage.DONE -> FallbackPhase.Done
            },
        )
    }
    val fallbackClickGate = remember(impressionId) { ClickInteractionGate() }
    val clickClaim = claimClick ?: fallbackClickGate::claim
    // Fullscreen presentations pass their shared coordinator; the local instance is only for the
    // standalone/default host and must not replace presentation state across end-screen indices.
    val localAutoRedirectCoordinator = remember(impressionId) { AutoRedirectCoordinator() }
    val redirects = autoRedirectCoordinator ?: localAutoRedirectCoordinator
    DisposableEffect(localAutoRedirectCoordinator, autoRedirectCoordinator) {
        onDispose {
            if (autoRedirectCoordinator == null) localAutoRedirectCoordinator.dispose()
        }
    }
    // auto_store_redirect END_SCREEN_N: open the primary ad's store once, when the fallback screen
    // whose index matches the configured trigger is presented (index 0 = END SCREEN 1, index 1 = 2).
    // Scope replacement drops deferred callbacks from an end screen that has already closed.

    // Prefetch the fallback screens in the background while the primary creative is on screen, so
    // they present instantly on close instead of fetching then (which flashed the host behind).
    // `GET /load/fallbacks` is side-effect-free, so prefetching reports nothing prematurely.
    // null = still in flight; empty = none returned.
    var prefetched by remember(presentationState) { mutableStateOf(presentationState.fetchedAds) }
    LaunchedEffect(impressionId) {
        if (prefetched == null) {
            var fetched: List<SimulaApiClient.FallbackAd>? = null
            for (attempt in 0 until FALLBACK_FETCH_ATTEMPTS) {
                val result = runCatching {
                    if (impressionId.isNotBlank()) SimulaApiClient.fetchFallbacksStrict(impressionId) else emptyList()
                }
                if (result.isSuccess) {
                    fetched = result.getOrThrow()
                    break
                }
                presentationState.fetchFailed()
                if (attempt + 1 < FALLBACK_FETCH_ATTEMPTS) delay(FALLBACK_FETCH_RETRY_MS)
            }
            if (presentationState.stage == FallbackStage.DONE) return@LaunchedEffect
            val resolved = fetched ?: presentationState.terminalizeInitialFetchFailure()
            presentationState.retainFetchedAds(resolved)
            prefetched = resolved
        }
    }

    // Primary creative closed → present the prefetched screens immediately. If the prefetch is
    // somehow still in flight (user closed very fast), wait briefly in [FallbackPhase.Fetching].
    fun onPrimaryClosed() {
        val ads = prefetched
        phase = when {
            ads == null -> FallbackPhase.Fetching(presentationState.startPostCloseFetchWait())
            ads.isNotEmpty() -> FallbackPhase.Showing(ads, index = 0).also { presentationState.showing(0) }
            else -> FallbackPhase.Done.also { presentationState.done() }
        }
    }

    // This root survives every phase, including Done's final callback frame, so no transition can
    // expose the Activity window or host beneath it.
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val p = phase) {
            FallbackPhase.Content -> content { onPrimaryClosed() }
            // Prefetch wasn't ready at close — hold on the black backdrop and advance when it lands.
            is FallbackPhase.Fetching -> {
                // Swallow back during this brief settle window so a fast back-press can't finish the
                // Activity before the end screens are revealed (parity with the gated close).
                BackHandler(enabled = true) {}
                LaunchedEffect(p.generation) {
                    val remainingMs = presentationState.postCloseFetchWaitRemainingMs(p.generation)
                        ?: return@LaunchedEffect
                    if (remainingMs > 0L) delay(remainingMs)
                    if (presentationState.timeoutPostCloseFetchWait(p.generation)) {
                        phase = FallbackPhase.Done
                    }
                }
                LaunchedEffect(prefetched, p.generation) {
                    val ads = prefetched ?: return@LaunchedEffect
                    if (!presentationState.resolvePostCloseFetchWait(p.generation, ads)) {
                        return@LaunchedEffect
                    }
                    phase = if (presentationState.stage == FallbackStage.SHOWING) {
                        FallbackPhase.Showing(ads, presentationState.index)
                    } else {
                        FallbackPhase.Done
                    }
                }
            }
            is FallbackPhase.Showing -> {
                val ad = p.ads[p.index]
                val autoRedirectScope = remember(impressionId, p.index) { Any() }
                DisposableEffect(redirects, autoRedirectScope) {
                    redirects.activate(autoRedirectScope)
                    onDispose { redirects.deactivate(autoRedirectScope) }
                }
                // Fire the auto store redirect when the END_SCREEN_N fallback screen is presented.
                LaunchedEffect(p.index) {
                    if (autoStoreRedirect?.enabled == true &&
                        autoStoreRedirect.trigger == endScreenTriggerForIndex(p.index)
                    ) {
                        redirects.request(
                            scope = autoRedirectScope,
                            pendingHandoff = pendingClickHandoff(),
                            route = onAutoStoreRedirect,
                        )
                    }
                }
                // key() so each screen gets fresh overlay state (countdown, WebView) — without it the
                // next screen would inherit the previous one's elapsed countdown and loaded page.
                key(p.index) {
                    FallbackAdOverlay(
                        iframeUrl = ad.iframeUrl,
                        html = ad.html,
                        adId = ad.adId,
                        nativeClickBeaconV1Enabled = ad.nativeClickBeaconV1Enabled,
                        onAdClick = onAdClick,
                        onStoreOpen = { interaction ->
                            redirects.recordUserRouteOpened()
                            onStoreOpen(interaction)
                        },
                        persistClick = persistClick,
                        claimClick = clickClaim,
                        impressionId = impressionId,
                        adFormat = adFormat,
                        routeClick = routeClick,
                        presentationState = presentationState,
                        fallbackIndex = p.index,
                        onClickHandoffCreated = { handoff ->
                            presentationState.setClickPending(true)
                            onClickHandoffCreated(handoff)
                        },
                        onClickHandoffFinished = { handoff ->
                            presentationState.setClickPending(false)
                            onClickHandoffFinished(handoff)
                        },
                        ctaTrackingUrl = ctaTrackingUrl,
                        ctaDestination = ctaDestination,
                        ctaStoreUrl = ctaStoreUrl,
                        onClose = {
                            if (!presentationState.advance(p.ads.size)) return@FallbackAdOverlay
                            phase = if (presentationState.stage == FallbackStage.SHOWING) {
                                p.copy(index = presentationState.index)
                            } else {
                                FallbackPhase.Done
                            }
                        },
                    )
                }
            }
            FallbackPhase.Done -> LaunchedEffect(Unit) { onFullyClosed() }
        }
    }
}

private sealed interface FallbackPhase {
    data object Content : FallbackPhase
    data class Fetching(val generation: Long) : FallbackPhase
    data class Showing(val ads: List<SimulaApiClient.FallbackAd>, val index: Int) : FallbackPhase
    data object Done : FallbackPhase
}

/**
 * Full-screen fallback ad: the iframe in a pooled WebView with a 5s countdown ring that resolves to
 * a top-right close button (the same shape as the minigame menu's post-game overlay).
 */
@Composable
private fun FallbackAdOverlay(
    iframeUrl: String?,
    html: String? = null,
    adId: String,
    nativeClickBeaconV1Enabled: Boolean,
    onAdClick: (ClickInteraction) -> Unit = {},
    onStoreOpen: (ClickInteraction) -> Unit = {},
    persistClick: (ClickInteraction, () -> Unit) -> Unit,
    claimClick: (String) -> ClickInteractionClaim?,
    impressionId: String,
    adFormat: String,
    routeClick: (((Context) -> Boolean, (Boolean) -> Unit) -> ClickRouteStart)?,
    presentationState: FallbackPresentationState,
    fallbackIndex: Int,
    onClickHandoffCreated: (ClickPersistenceHandoff) -> Unit,
    onClickHandoffFinished: (ClickPersistenceHandoff) -> Unit,
    ctaTrackingUrl: String? = null,
    ctaDestination: String = "appstore",
    ctaStoreUrl: String? = null,
    onClose: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val clickHandler = remember { Handler(Looper.getMainLooper()) }
    val inlineHtml = html?.takeIf { it.isNotBlank() }
    // The pooled fallback WebView, captured from the AndroidView factory below so we can pause/resume
    // it with the host and force a repaint on foreground return — AndroidView won't pause a WebView, and
    // a hardware-accelerated WebView returns black/blank after the window loses visibility (background).
    var fallbackWebView by remember { mutableStateOf<WebView?>(null) }
    val clickAdmission = remember(presentationState, fallbackIndex) {
        presentationState.clickAdmission(fallbackIndex)
    }
    val navigationOwner = remember(presentationState, fallbackIndex) { Any() }
    DisposableEffect(presentationState, navigationOwner, fallbackWebView) {
        val webView = fallbackWebView ?: return@DisposableEffect onDispose {}
        val webViewRef = WeakReference(webView)
        presentationState.bindNavigation(navigationOwner) { url ->
            val target = webViewRef.get() ?: return@bindNavigation false
            runCatching { target.loadUrl(url) }.isSuccess
        }
        onDispose { presentationState.unbindNavigation(navigationOwner) }
    }
    DisposableEffect(lifecycleOwner, fallbackWebView) {
        val wv = fallbackWebView
        var wasStopped = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> wv?.onPause()
                Lifecycle.Event.ON_STOP -> wasStopped = true
                Lifecycle.Event.ON_RESUME -> {
                    wv?.onResume()
                    if (wasStopped) {
                        wasStopped = false
                        wv?.repaintOnNextFrame()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var countdown by remember { mutableStateOf(5) }
    // A pooled WebView is transparent and may still contain about:blank. Keep an opaque layer above
    // this one WebView until its current creative has actually committed a visible frame.
    var pageCommitted by remember { mutableStateOf(false) }
    var pageLoadFailed by remember { mutableStateOf(false) }
    // Ring fills clockwise from the top (right to left), unfilled → filled, over the countdown.
    val ring = remember { Animatable(0f) }
    // Foreground-only 5s gate: time accrues only while the Activity is RESUMED, so leaving the app
    // pauses the countdown (parity with the interstitial / rewarded close gates). repeatOnLifecycle
    // cancels the loop when backgrounded and resumes it from the accrued time on return.
    LaunchedEffect(Unit) {
        val totalMs = 5_000L
        var accumulatedMs = 0L
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Re-anchor on each resume so the backgrounded interval is never counted.
            var lastTickMs = SystemClock.elapsedRealtime()
            while (accumulatedMs < totalMs) {
                delay(50L)
                val now = SystemClock.elapsedRealtime()
                accumulatedMs += now - lastTickMs
                lastTickMs = now
                ring.snapTo((accumulatedMs.toFloat() / totalMs).coerceIn(0f, 1f))
                countdown = ceil((totalMs - accumulatedMs).coerceAtLeast(0L) / 1000.0).toInt()
            }
        }
    }
    // Back can only close once the countdown elapses (parity with the creative's gated close).
    BackHandler(enabled = true) {
        if (countdown <= 0 && !presentationState.clickHandoffPending) onClose()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                var realLoadStarted = false
                WebViewPool.acquire(
                    context = ctx,
                    client = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (!realLoadStarted) return
                            pageCommitted = false
                            if (!url.isNullOrBlank() && (url != "about:blank" || inlineHtml != null)) {
                                pageLoadFailed = false
                            }
                        }
                        override fun onPageCommitVisible(view: WebView?, url: String?) {
                            if (!realLoadStarted) return
                            if (!url.isNullOrBlank() &&
                                (url != "about:blank" || inlineHtml != null) &&
                                !pageLoadFailed
                            ) {
                                pageCommitted = true
                            }
                        }
                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (!realLoadStarted) return
                            if (request?.isForMainFrame == true) {
                                pageLoadFailed = true
                                pageCommitted = false
                            }
                        }
                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?,
                        ) {
                            if (!realLoadStarted) return
                            if (request?.isForMainFrame == true) {
                                pageLoadFailed = true
                                pageCommitted = false
                            }
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url?.toString() ?: return false
                            presentationState.navigationOverride(clickAdmission.isEnabled())?.let { return it }
                            val targetUri = runCatching { Uri.parse(target) }.getOrNull() ?: return true
                            if (targetUri.scheme?.lowercase() in setOf("about", "data", "blob")) return false
                            // Subframes stay inside the creative and automatic cross-origin redirects
                            // are blocked rather than opening external UI.
                            if (request.isForMainFrame != true) return false
                            val originUri = iframeUrl?.let { runCatching { Uri.parse(it) }.getOrNull() }
                            val originPort = originUri?.port?.takeIf { it >= 0 } ?: when (originUri?.scheme?.lowercase()) {
                                "http" -> 80
                                "https" -> 443
                                else -> -1
                            }
                            val targetPort = targetUri.port.takeIf { it >= 0 } ?: when (targetUri.scheme?.lowercase()) {
                                "http" -> 80
                                "https" -> 443
                                else -> -1
                            }
                            val sameOrigin = originUri?.host != null &&
                                originUri.scheme.equals(targetUri.scheme, ignoreCase = true) &&
                                originUri.host.equals(targetUri.host, ignoreCase = true) &&
                                originPort == targetPort
                            if (sameOrigin) return false
                            if (!request.hasGesture()) return true
                            // Route through the shared CTA router: the tapped tracker opens
                            // verbatim (referrer-preserving); the serve's raw store link is the
                            // deterministic fallback when it can't be launched. A failed launch
                            // returns false so the WebView navigates in place (the pre-router
                            // failure behavior).
                            // A genuine user tap uses one native fallback_cta interaction id for durable
                            // beacon + lifecycle attribution. Programmatic redirects remain non-clicks.
                            val claim = notifyPublisherClickForClaim(
                                claimClick(ClickSources.FALLBACK_CTA),
                                onAdClick,
                            ) ?: return true
                            clickAdmission.disable()
                            val interaction = claim.interaction
                            coordinateDeferredClickPersistence(
                                mainHandler = clickHandler,
                                claim = claim,
                                enqueueBeacon = { completion ->
                                    enqueueOwnedFallbackClickBeacon(
                                        adId = adId,
                                        serverEnabled = nativeClickBeaconV1Enabled,
                                        completion = completion,
                                    ) { beaconId ->
                                        AdBeaconManager.enqueue(
                                            beaconId,
                                            "click",
                                            adFormat = adFormat,
                                            telemetryServeId = impressionId,
                                            interactionId = interaction.id,
                                            clickSource = interaction.source,
                                            onPersistenceComplete = completion,
                                        )
                                    }
                                },
                                recordTelemetry = { completion -> persistClick(interaction, completion) },
                                onHandoff = { committedInteraction, completion ->
                                    val tappedDestination = CreativeCtaRouter.normalizeTappedDestination(target)
                                    val clickTarget = CreativeCtaRouter.admittedHttpUrl(ctaTrackingUrl)
                                        ?: tappedDestination
                                    val route = { appContext: Context ->
                                        val opened = CreativeCtaRouter.open(
                                            appContext,
                                            clickTarget,
                                            ctaDestination,
                                            null,
                                            ctaStoreUrl,
                                        )
                                        if (opened) runCatching { onStoreOpen(committedInteraction) }
                                        if (!opened) {
                                            tappedDestination?.let { fallbackUrl ->
                                                presentationState.retainNavigation(fallbackUrl)
                                            }
                                        }
                                        opened
                                    }
                                    routeClick?.invoke(route, completion) ?: run {
                                        completion(route(ctx.applicationContext))
                                        ClickRouteStart.STARTED
                                    }
                                },
                                onCreated = onClickHandoffCreated,
                                onFinished = onClickHandoffFinished,
                            )
                            return true
                        }
                        // Absorb a renderer-process death so a crashing end-screen creative can't take
                        // the host app process down with it (parity with the minigame/interstitial
                        // clients; surfaced as telemetry).
                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean =
                            recordRenderProcessGone("fallback_ad", detail)
                    },
                ).apply {
                    if (inlineHtml != null) {
                        // Inline html (preferred). baseUrl = the iframe origin so the end screen's own
                        // click beacon (fetch to the API) stays same-origin, exactly as loadUrl did.
                        realLoadStarted = true
                        loadDataWithBaseURL(iframeUrl, inlineHtml, "text/html", "UTF-8", null)
                    } else if (!iframeUrl.isNullOrBlank()) {
                        realLoadStarted = true
                        loadUrl(iframeUrl)
                    }
                    fallbackWebView = this
                }
            },
            // The creative fills edge-to-edge: inset only vertically (status / nav / top notch),
            // matching the interstitial / rewarded creatives, and draw under any horizontal
            // display-cutout so the transparent WebView's black backing never shows as left/right
            // bars in landscape on a cutout device.
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
            onRelease = { webView ->
                if (fallbackWebView === webView) fallbackWebView = null
                presentationState.unbindNavigation(navigationOwner)
                WebViewPool.release(webView)
            },
        )

        if (!pageCommitted) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    },
            )
        }

        if (presentationState.clickHandoffPending) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    },
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp)
                .size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (countdown <= 0 && !presentationState.clickHandoffPending) {
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
