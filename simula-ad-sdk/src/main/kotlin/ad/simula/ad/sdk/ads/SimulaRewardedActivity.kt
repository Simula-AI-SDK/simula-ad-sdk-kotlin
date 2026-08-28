package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.bridge.BridgeWebViewInstaller
import ad.simula.ad.sdk.bridge.BridgeInjectionMode
import ad.simula.ad.sdk.bridge.CreativeTelemetryWebChromeClient
import ad.simula.ad.sdk.bridge.CreativeTelemetryWebViewClient
import ad.simula.ad.sdk.telemetry.Telemetry
import ad.simula.ad.sdk.bridge.androidCreativeBridge
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.minigame.repaintOnNextFrame
import ad.simula.ad.sdk.model.AutoStoreRedirectTrigger
import ad.simula.ad.sdk.model.CloseBehavior
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.CloseTreatment
import ad.simula.ad.sdk.network.AdBeaconManager
import ad.simula.ad.sdk.network.AutoRedirectResult
import ad.simula.ad.sdk.network.ClickRouteStart
import ad.simula.ad.sdk.network.ClickSources
import ad.simula.ad.sdk.network.PresentationRouteResult
import ad.simula.ad.sdk.network.PrimaryCtaRoute
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.provider.ProvideSimulaContext
import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import java.lang.ref.WeakReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Transparent, full-screen host for the imperative rewarded minigame. Reads its
 * [RewardedPresentation] from [RewardedHandoff] by token and renders the playable
 * iframe in a pooled WebView with a play-to-earn status pill and an always-available
 * close button. Mirrors [SimulaInterstitialActivity].
 */
internal class SimulaRewardedActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TOKEN = "ad.simula.ad.sdk.REWARDED_TOKEN"
    }

    private var presentation: RewardedPresentation? = null
    private var token: String? = null
    private var closed = false
    private var completed = false
    // Store-exit funnel tracker for this presentation (store_opened/returned/abandoned). Main-thread only.
    private var storeExit: StoreExitTracker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        token = intent?.getStringExtra(EXTRA_TOKEN)
        // Non-destructive read so the presentation survives Activity recreation.
        val p = token?.let { RewardedHandoff.get(it) }
        if (p == null) {
            finish()
            return
        }
        presentation = p
        p.attachActivity(this)
        token?.let { RewardedHandoff.markPresented(it) }
        storeExit = p.storeExit

        configureWindow()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        setContent {
            ProvideSimulaContext(
                store = SimulaAds.store,
                apiKey = SimulaAds.apiKey,
                devMode = SimulaAds.devMode,
            ) {
                // On close, fetch + show a fallback ad before finishing (minigame parity). CLOSE is
                // reported when the minigame closes; the Activity finishes after the fallback.
                FallbackAdHost(
                    impressionId = p.impressionId,
                    adFormat = "rewarded",
                    presentationState = p.fallbackState,
                    onFullyClosed = ::completeReward,
                    autoStoreRedirect = p.adBehavior?.autoStoreRedirect,
                    onAdClick = { p.callbacks.notifyClicked() },
                    onStoreOpen = { interaction -> p.storeExit.recordStoreOpen(interaction.source) },
                    persistClick = { fallbackAdId, interaction, completion ->
                        p.callbacks.persistFallbackClick(
                            fallbackAdId,
                            p.impressionId.takeIf { it.isNotBlank() },
                            interaction,
                            completion,
                        )
                    },
                    claimClick = p::claimClick,
                    routeClick = { route, completion ->
                        val result = p.routeClick(
                            route = { activity ->
                                if (!canRouteFromCurrentFullscreenActivity(
                                        activity.isFinishing,
                                        activity.isDestroyed,
                                    )) false else route(activity.applicationContext)
                            },
                            completion = completion,
                        )
                        if (result == PresentationRouteResult.REJECTED) {
                            ClickRouteStart.REJECTED
                        } else {
                            ClickRouteStart.STARTED
                        }
                    },
                    onClickHandoffCreated = p::trackClickHandoff,
                    onClickHandoffFinished = p::clearClickHandoff,
                    autoRedirectCoordinator = p.autoRedirectCoordinator,
                    pendingClickHandoff = p::pendingClickHandoff,
                    storeVisitPending = storeExit?.hasPendingStoreVisit() == true,
                    // END_SCREEN_N opens the primary ad's store (the same path as a CTA / PLAYABLE_END).
                    onAutoStoreRedirect = {
                        val opened = CreativeCtaRouter.open(
                            applicationContext,
                            p.trackingUrl,
                            p.destination,
                            p.adBehavior?.storeOpen,
                            p.androidStoreUrl,
                        )
                        if (opened) storeExit?.recordStoreOpen(ClickSources.AUTO_REDIRECT)
                        opened
                    },
                    openAutomaticNavigation = { targetUrl, trackerAlreadyRequested ->
                        val outcome = if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            CreativeCtaRouter.openAutomaticNavigation(
                                applicationContext,
                                targetUrl,
                                p.destination,
                                p.trackingUrl,
                                trackerAlreadyRequested,
                            )
                        } else AutomaticNavigationOutcome.FAILED
                        if (outcome == AutomaticNavigationOutcome.STORE_OPENED) {
                            storeExit?.recordStoreOpen(ClickSources.AUTO_REDIRECT)
                        }
                        outcome
                    },
                    // End-screen CTA routing context (deterministic store fallback).
                    ctaTrackingUrl = p.trackingUrl,
                    ctaDestination = p.destination,
                    ctaStoreUrl = p.androidStoreUrl,
                ) { onClose ->
                    RewardedMinigame(
                        presentation = p,
                        storeVisitPending = storeExit?.hasPendingStoreVisit() == true,
                        recordStoreOpen = { trigger -> storeExit?.recordStoreOpen(trigger) },
                        onFinish = { earned ->
                            p.rewardEarned = monotonicRewardEarned(earned, p.rewardEarned)
                            // CLOSE is deferred to completeReward (after the last fallback screen), so
                            // closing the playable alone doesn't fire the publisher close callback.
                            onClose()
                        },
                    )
                }
            }
        }
    }

    /** Fire CLOSE (with reward state + measured play time) exactly once when the WHOLE unit is done —
     * the minigame AND every post-close fallback ad screen. Driven from [completeReward] (the fallback
     * host's fully-closed callback), with [onDestroy] as a teardown safety net. */
    private fun reportClosed() {
        if (closed) return
        closed = true
        runCatching { storeExit?.onAdClosed() } // resolve any outstanding store visit as an abandon
        presentation?.let { p -> runCatching { p.callbacks.onClose(p.rewardEarned, elapsedSeconds(p)) } }
    }

    override fun onResume() {
        super.onResume()
        storeExit?.onResume()
        presentation?.resumeActivity(this)
    }

    override fun onPause() {
        presentation?.pauseActivity(this)
        super.onPause()
        storeExit?.onPause()
    }

    /**
     * The user has dismissed every screen (playable + all fallback ad screens) — the unit is fully
     * complete. Fire reward completion (the earned-reward signal + server verification) exactly once,
     * then tear the Activity down. Deferred to here so closing the playable alone doesn't verify the
     * reward; with no fallback screens, [FallbackAdHost] calls this immediately on close.
     */
    private fun completeReward() {
        reportClosed() // CLOSE fires once the whole unit (playable + all fallback screens) is done
        if (!completed) {
            completed = true
            presentation?.let { p ->
                runCatching { p.callbacks.onRewardCompleted(p.rewardEarned, elapsedSeconds(p)) }
            }
        }
        finishAd()
    }

    /** Tear the Activity down (after the optional fallback ad). */
    private fun finishAd() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        presentation?.detachActivity(this)
        super.onDestroy()
        // Only act when finishing for good; on a config-change recreation we keep the
        // handoff so the new instance can read it and must NOT report CLOSE.
        if (isFinishing) {
            reportClosed() // safety net: torn down (back-out / swipe-away) before completeReward ran
            // Safety net: the unit is being torn down (back-out / swipe-away / finish) after the reward
            // was earned but before completeReward() ran during the fallback phase. Fire completion now
            // so the server-side verification is still enqueued (RewardVerificationManager) — otherwise
            // the SSV postback is permanently lost and the durable queue has nothing to retry. Fired
            // after onClose to preserve the normal close→complete callback order. (A truly abrupt
            // process kill won't call onDestroy at all; this closes the common finish/teardown window.)
            val currentPresentation = presentation
            if (!completed && currentPresentation?.rewardEarned == true) {
                completed = true
                runCatching {
                    currentPresentation.callbacks.onRewardCompleted(
                        currentPresentation.rewardEarned,
                        elapsedSeconds(currentPresentation),
                    )
                }
                val serveId = currentPresentation.impressionId.takeIf { it.isNotBlank() }
                Telemetry.recordLifecycle(
                    stage = "reward_salvaged_on_teardown",
                    adFormat = "rewarded",
                    adId = serveId,
                    serveId = serveId,
                )
            }
            token?.let { RewardedHandoff.remove(it) }
        }
    }

    private fun elapsedSeconds(p: RewardedPresentation): Double {
        return p.accumulatedPlayTimeMs / 1000.0
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

    internal fun recordClickStoreOpen(source: String) {
        storeExit?.recordStoreOpen(source)
    }
}

internal sealed interface RewardedNavigationAction {
    data object AllowInWebView : RewardedNavigationAction
    data object Consume : RewardedNavigationAction
    data object RouteUserCta : RewardedNavigationAction
    data class RouteAutomatic(val targetUrl: String) : RewardedNavigationAction
}

internal fun initialRewardEarned(
    persistedRewardEarned: Boolean,
    accumulatedPlayTimeMs: Long,
    gateSeconds: Int,
): Boolean = persistedRewardEarned ||
    (gateSeconds > 0 && RewardGate.isEarned(accumulatedPlayTimeMs, gateSeconds))

internal fun monotonicRewardEarned(candidate: Boolean, retained: Boolean): Boolean = candidate || retained

internal fun rewardEarnedAfterRendererGone(
    creativeCommitted: Boolean,
    candidate: Boolean,
    retained: Boolean,
): Boolean = creativeCommitted || candidate || retained

private const val REWARDED_CREATIVE_COMMIT_TIMEOUT_MS = 10_000L

internal fun rewardedNavigationAction(
    isMainFrame: Boolean,
    hasGesture: Boolean,
    targetUrl: String,
    currentPageUrl: String?,
    initialPageUrl: String?,
    destination: String = "appstore",
    trackingUrl: String? = null,
): RewardedNavigationAction {
    if (!isMainFrame) return RewardedNavigationAction.AllowInWebView
    if (!hasGesture) {
        return when (val plan = CreativeCtaRouter.automaticNavigationPlan(
            targetUrl,
            destination,
            trackingUrl,
        )) {
            CreativeCtaRouter.AutomaticNavigationPlan.AllowInWebView ->
                RewardedNavigationAction.AllowInWebView
            CreativeCtaRouter.AutomaticNavigationPlan.Consume -> RewardedNavigationAction.Consume
            is CreativeCtaRouter.AutomaticNavigationPlan.RouteExact ->
                RewardedNavigationAction.RouteAutomatic(plan.targetUrl)
        }
    }
    val currentOrigin = CreativeCtaRouter.admittedHttpUrl(currentPageUrl)
        ?: CreativeCtaRouter.admittedHttpUrl(initialPageUrl)
    return if (CreativeCtaRouter.hasSameHttpOrigin(currentOrigin, targetUrl)) {
        RewardedNavigationAction.AllowInWebView
    } else {
        RewardedNavigationAction.RouteUserCta
    }
}

@Composable
private fun RewardedMinigame(
    presentation: RewardedPresentation,
    storeVisitPending: Boolean,
    recordStoreOpen: (String) -> Unit,
    onFinish: (earned: Boolean) -> Unit,
) {
    val creativeSource = remember(presentation) {
        rewardedCreativeSource(presentation.renderedHtml, presentation.iframeUrl)
    }
    // Only a loaded iframe has an HTTP origin. Rendered HTML stays opaque and must not inherit the
    // unused iframe metadata's origin for CTA classification.
    val initialPageUrl = (creativeSource as? RewardedCreativeSource.Iframe)?.url
        ?.let(CreativeCtaRouter::admittedHttpUrl)
    // Play-to-earn gate length, in seconds — sourced from `ad_behavior.close.delay_seconds` (the
    // same value that ungates the close button). No `ad_behavior` → 0 → instantly earned.
    val gateSeconds = presentation.adBehavior?.close?.delaySeconds ?: 0

    // Earned immediately when there is no gate; otherwise resolved by the timer below.
    // A gate that already elapsed in a prior Activity instance (config-change recreation)
    // also starts earned — accumulated play time survives on the presentation.
    var rewardEarned by remember {
        mutableStateOf(initialRewardEarned(presentation.rewardEarned, presentation.accumulatedPlayTimeMs, gateSeconds))
    }
    var secondsLeft by remember {
        // Resume from already-accrued play time (config-change recovery), not the full gate.
        mutableStateOf(RewardGate.secondsLeft(presentation.accumulatedPlayTimeMs, gateSeconds))
    }
    // 0→1 fill for the close treatment (progress bar / countdown ring), from play-to-earn progress.
    // An Animatable driven by one continuous animation (below) rather than a value stepped each tick,
    // so the bar/ring advances every frame and stays smooth on slower devices.
    val closeProgress = remember {
        Animatable(rewardCloseProgress(presentation.accumulatedPlayTimeMs, gateSeconds))
    }
    val showsCloseBar = presentation.adBehavior?.close?.treatment.let {
        it == CloseTreatment.COUNTDOWN_CIRCLE || it == CloseTreatment.PROGRESS_BAR
    }

    // Mid-ad store prompt — shown from half the play-to-earn gate until the reward unlocks.
    // Initialized true on a config-change recreation that resumes past the halfway mark.
    val storePrompt = presentation.adBehavior?.storePrompt
    var storePromptVisible by remember {
        mutableStateOf(
            storePrompt != null && storePrompt.enabled &&
                gateSeconds > 0 &&
                presentation.accumulatedPlayTimeMs >= gateSeconds * 1000L / 2,
        )
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clickHandoffHandler = remember { Handler(Looper.getMainLooper()) }
    var clickHandoffPending by remember(presentation) {
        mutableStateOf(presentation.pendingClickHandoff() != null)
    }
    DisposableEffect(presentation) {
        val subscription = presentation.pendingClickHandoff()?.addResultListener {
            clickHandoffPending = false
        }
        onDispose { subscription?.cancel() }
    }
    val autoRedirectScope = remember(presentation) { Any() }
    DisposableEffect(presentation.autoRedirectCoordinator, autoRedirectScope) {
        presentation.autoRedirectCoordinator.activate(autoRedirectScope)
        onDispose { presentation.autoRedirectCoordinator.deactivate(autoRedirectScope) }
    }
    fun routeAutomaticStoreNavigation() {
        val result = presentation.autoRedirectCoordinator.request(
            scope = autoRedirectScope,
            pendingHandoff = presentation.pendingClickHandoff(),
        ) {
            val outcome = if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                presentation.automaticNavigationGate.attemptPending { route ->
                    CreativeCtaRouter.openAutomaticNavigation(
                        context.applicationContext,
                        route.targetUrl,
                        presentation.destination,
                        presentation.trackingUrl,
                        route.trackerAlreadyRequested,
                    )
                }
            } else AutomaticNavigationOutcome.FAILED
            if (outcome == AutomaticNavigationOutcome.STORE_OPENED) {
                recordStoreOpen(ClickSources.AUTO_REDIRECT)
            }
            outcome != AutomaticNavigationOutcome.FAILED
        }
        if (result == AutoRedirectResult.SUPPRESSED) {
            presentation.automaticNavigationGate.suppressPending()
        }
    }
    LaunchedEffect(autoRedirectScope) { routeAutomaticStoreNavigation() }

    // Suspend the creative's JS/timers/video while the host is backgrounded — AndroidView won't pause
    // a WebView on its own, so a rewarded ad left open behind the home screen would keep running.
    // Resume when the host returns to the foreground. (The native-ad path pauses off-screen views too.)
    var creativeWebView by remember { mutableStateOf<WebView?>(null) }
    var creativeCommitted by remember(presentation) { mutableStateOf(false) }
    var rendererGone by remember { mutableStateOf(false) }
    var bridgeInstalled by remember(presentation) { mutableStateOf(false) }
    var creativeCommitTimeout by remember { mutableStateOf<Runnable?>(null) }
    var bridgeReady by remember(presentation) { mutableStateOf(false) }
    var displayAdmitted by remember(presentation) { mutableStateOf(presentation.displayedReported) }
    var bridgeUnavailable by remember(presentation) {
        mutableStateOf(presentation.primaryCreativeUnavailable)
    }
    fun markBridgeUnavailable() {
        presentation.automaticNavigationGate.clear()
        presentation.autoRedirectCoordinator.deactivate(autoRedirectScope)
        presentation.primaryCreativeUnavailable = true
        bridgeUnavailable = true
    }
    var unavailableExitIssued by remember(presentation) { mutableStateOf(false) }
    LaunchedEffect(bridgeUnavailable, clickHandoffPending, storeVisitPending) {
        if (!bridgeUnavailable || clickHandoffPending) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            withFrameNanos { }
            if (!shouldExitUnavailableCreative(
                    creativeUnavailable = bridgeUnavailable,
                    clickHandoffPending = clickHandoffPending,
                    storeVisitPending = storeVisitPending,
                )
            ) return@repeatOnLifecycle
            if (unavailableExitIssued) return@repeatOnLifecycle
            unavailableExitIssued = true
            val earned = monotonicRewardEarned(rewardEarned, presentation.rewardEarned)
            rewardEarned = earned
            onFinish(earned)
        }
    }
    val primaryCtaNavigation = presentation.primaryCtaNavigation
    val fallbackOwner = remember(presentation) { Any() }
    val fallbackActivity = LocalContext.current as? SimulaRewardedActivity
    DisposableEffect(presentation, fallbackOwner, fallbackActivity, creativeWebView) {
        val activity = fallbackActivity ?: return@DisposableEffect onDispose {}
        val webView = creativeWebView ?: return@DisposableEffect onDispose {}
        val webViewRef = WeakReference(webView)
        presentation.setPrimaryFallback(fallbackOwner, activity) { url ->
            val target = webViewRef.get() ?: return@setPrimaryFallback false
            runCatching { target.loadUrl(url) }.isSuccess
        }
        onDispose { presentation.clearPrimaryFallback(fallbackOwner) }
    }
    DisposableEffect(lifecycleOwner, creativeWebView) {
        val wv = creativeWebView
        // Track a real background (ON_STOP) so the repaint below fires only when the window actually
        // lost its drawing surface — not on an incidental ON_PAUSE (a dialog / permission sheet over a
        // still-visible Activity), which would cause a one-frame INVISIBLE flicker.
        var wasStopped = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    creativeCommitTimeout?.let(clickHandoffHandler::removeCallbacks)
                    if (!rendererGone) wv?.onPause()
                }
                Lifecycle.Event.ON_STOP -> wasStopped = true
                Lifecycle.Event.ON_RESUME -> {
                    if (rendererGone) return@LifecycleEventObserver
                    creativeCommitTimeout?.takeIf { !creativeCommitted && !bridgeUnavailable }?.let { timeout ->
                        clickHandoffHandler.removeCallbacks(timeout)
                        clickHandoffHandler.postDelayed(timeout, REWARDED_CREATIVE_COMMIT_TIMEOUT_MS)
                    }
                    wv?.onResume()
                    routeAutomaticStoreNavigation()
                    if (wasStopped) {
                        wasStopped = false
                        // A hardware-accelerated WebView drops its draw functor on background; force the
                        // visibility transition that recreates it, else the creative returns black/blank.
                        wv?.repaintOnNextFrame { !rendererGone }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // WebView ↔ SDK bridge (PRD §3). AD_EARLY_COMPLETE (e.g. survey finished) grants the reward and
    // reveals the close button immediately, bypassing the play timer.
    val autoRedirect = presentation.adBehavior?.autoStoreRedirect
    // auto_store_redirect: open the advertiser store once (no user tap). A disabled/missing config no-ops.
    fun fireAutoStoreRedirect() {
        presentation.autoRedirectCoordinator.request(
            scope = autoRedirectScope,
            pendingHandoff = presentation.pendingClickHandoff(),
        ) {
            val opened = CreativeCtaRouter.open(
                context.applicationContext,
                presentation.trackingUrl,
                presentation.destination,
                presentation.adBehavior?.storeOpen,
                presentation.androidStoreUrl,
            )
            if (opened) recordStoreOpen(ClickSources.AUTO_REDIRECT)
            opened
        }
    }
    val bridge = remember {
        androidCreativeBridge(
            appContext = context.applicationContext,
            activityProvider = { context as? Activity },
            onEarlyComplete = {
                presentation.rewardEarned = true
                rewardEarned = true
            },
        )
    }

    // PLAYABLE_END — open the store the moment the close button appears (here, when the reward is
    // earned and the reward/close pill becomes a close button). SDK-native, no bridge.
    if (autoRedirect?.enabled == true && autoRedirect.trigger == AutoStoreRedirectTrigger.PLAYABLE_END) {
        LaunchedEffect(rewardEarned, bridgeReady) {
            if (rewardEarned && bridgeReady) fireAutoStoreRedirect()
        }
    }

    // IMPRESSION + PAID (the billable impression + paid event) — fired together once the playable
    // has been on screen for [FULLSCREEN_IMPRESSION_DELAY_MS] of FOREGROUND time after begin-to-render,
    // independent of the play-to-earn reward gate. OMID measures viewability but does not gate us (PRD).
    // Foreground-only so a backgrounded playable can't accrue the delay; the accrued time lives on the
    // presentation so a config-change recreation resumes rather than restarts. The `/seen` beacon is the
    // billing source of truth; onPaid is local analytics only (value already on-device, no network).
    LaunchedEffect(displayAdmitted) {
        if (!displayAdmitted) return@LaunchedEffect
        if (presentation.impressionReported) return@LaunchedEffect

        fun fireImpressionAndPaid() {
            commitFullscreenImpression(
                alreadyReported = presentation.impressionReported,
                markReported = { presentation.impressionReported = true },
                notifyImpression = presentation.callbacks::onImpression,
                notifyPaid = { presentation.callbacks.onPaid(presentation.adValue) },
                enqueueSeen = {
                    // Durable billable-impression beacon (was a fire-and-forget trackImpression).
                    AdBeaconManager.enqueue(
                        presentation.impressionId,
                        "seen",
                        adFormat = "rewarded",
                        telemetryServeId = presentation.impressionId.takeIf { it.isNotBlank() },
                        metadata = presentation.metadata,
                    )
                },
            )
        }

        if (presentation.accumulatedImpressionTimeMs >= FULLSCREEN_IMPRESSION_DELAY_MS) {
            fireImpressionAndPaid()
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (presentation.impressionReported) return@repeatOnLifecycle
            var lastTickMs = SystemClock.elapsedRealtime()
            while (true) {
                delay(IMPRESSION_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                presentation.accumulatedImpressionTimeMs += now - lastTickMs
                lastTickMs = now
                if (presentation.accumulatedImpressionTimeMs >= FULLSCREEN_IMPRESSION_DELAY_MS) {
                    fireImpressionAndPaid()
                    return@repeatOnLifecycle
                }
            }
        }
    }

    // Foreground-only play gate. Time accrues only while the Activity is RESUMED:
    // repeatOnLifecycle cancels the ticking loop when the app is backgrounded and
    // restarts it on return, so the gate can't be satisfied by simply backgrounding the
    // app for the required duration. The accumulated time lives on the presentation, so
    // a config change (rotation) resumes the remaining time instead of restarting it.
    LaunchedEffect(bridgeReady) {
        if (!bridgeReady) return@LaunchedEffect
        if (gateSeconds <= 0) {
            presentation.rewardEarned = true
            rewardEarned = true
            return@LaunchedEffect
        }
        if (presentation.rewardEarned) {
            rewardEarned = true
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // A re-run after the reward was already earned (background → resume) must not
            // keep accruing time.
            if (presentation.rewardEarned) return@repeatOnLifecycle
            // Bar/ring fill: ONE continuous, frame-clock animation to full over the remaining
            // foreground play time — not a value stepped each 250 ms tick. Anchored to the already-
            // accrued fraction on every (re)resume; backgrounding cancels this child with the loop so
            // the fill freezes, and the next resume re-anchors + re-launches. Frame-clock driven so it
            // stays smooth when ticks land late under main-thread load.
            if (showsCloseBar) {
                closeProgress.snapTo(rewardCloseProgress(presentation.accumulatedPlayTimeMs, gateSeconds))
                val remainingMs = (gateSeconds * 1000L - presentation.accumulatedPlayTimeMs).coerceAtLeast(0L)
                launch {
                    closeProgress.animateTo(1f, tween(durationMillis = remainingMs.toInt(), easing = LinearEasing))
                }
            }
            // Re-anchor on each resume so the backgrounded interval is never counted. This loop now
            // owns ONLY the accounting (reward earn, countdown number, store prompt); the visual fill
            // is the continuous animation above.
            var lastTickMs = SystemClock.elapsedRealtime()
            while (true) {
                delay(250L)
                val now = SystemClock.elapsedRealtime()
                presentation.accumulatedPlayTimeMs += now - lastTickMs
                lastTickMs = now
                secondsLeft = RewardGate.secondsLeft(presentation.accumulatedPlayTimeMs, gateSeconds)
                // Reveal the store prompt at the halfway point to the reward (mid play-to-earn).
                if (presentation.accumulatedPlayTimeMs >= gateSeconds * 1000L / 2) {
                    storePromptVisible = true
                }
                if (RewardGate.isEarned(presentation.accumulatedPlayTimeMs, gateSeconds)) {
                    presentation.rewardEarned = true
                    rewardEarned = true
                    break
                }
            }
        }
    }

    // No early exit: Back does nothing until the reward is earned, then it closes (earned).
    BackHandler(enabled = true) {
        if (canDismissFullscreen(rewardEarned, clickHandoffPending, displayAdmitted, storeVisitPending)) {
            presentation.automaticNavigationGate.clear()
            onFinish(true)
        }
    }

    fun beginPrimaryCta(tappedUrl: String, currentPageUrl: String? = creativeWebView?.url): Boolean {
        val route = when (val plan = CreativeCtaRouter.primaryCtaTapPlan(
            tappedUrl = tappedUrl,
            creativeBaseUrl = CreativeCtaRouter.admittedHttpUrl(currentPageUrl) ?: initialPageUrl,
            trackingUrl = presentation.trackingUrl,
            destination = presentation.destination,
        )) {
            CreativeCtaRouter.PrimaryCtaTapPlan.AllowInWebView -> return false
            CreativeCtaRouter.PrimaryCtaTapPlan.ConsumeWithoutClick -> return true
            is CreativeCtaRouter.PrimaryCtaTapPlan.Route -> plan.route
        }
        val claim = presentation.claimClick(ClickSources.PRIMARY_CTA) ?: return true
        notifyPublisherClick { presentation.callbacks.notifyClicked() }
        val interaction = claim.interaction
        coordinateDeferredClickPersistence(
            mainHandler = clickHandoffHandler,
            claim = claim,
            enqueueBeacon = { completion ->
                AdBeaconManager.enqueue(
                    presentation.impressionId,
                    "click",
                    adFormat = "rewarded",
                    telemetryServeId = presentation.impressionId.takeIf { it.isNotBlank() },
                    interactionId = interaction.id,
                    clickSource = interaction.source,
                    onPersistenceComplete = completion,
                )
            },
            recordTelemetry = { completion -> presentation.callbacks.persistClick(interaction, completion) },
            onHandoff = { committedInteraction, completion ->
                val result = presentation.routeClick({ routeActivity ->
                    if (!canRouteFromCurrentFullscreenActivity(
                            routeActivity.isFinishing,
                            routeActivity.isDestroyed,
                        )) return@routeClick false
                    val opened = CreativeCtaRouter.openPrimaryCta(
                        routeActivity.applicationContext,
                        route,
                        presentation.destination,
                        presentation.adBehavior?.storeOpen,
                        presentation.androidStoreUrl,
                    )
                    if (opened) {
                        presentation.autoRedirectCoordinator.recordUserRouteOpened()
                        routeActivity.recordClickStoreOpen(committedInteraction.source)
                    } else {
                        CreativeCtaRouter.admittedInWebViewFallback(
                            route.tappedUrl,
                            presentation.trackingUrl,
                        )?.let { presentation.openPrimaryFallback(it, routeActivity) }
                    }
                    opened
                }, completion)
                if (result == PresentationRouteResult.REJECTED) ClickRouteStart.REJECTED else ClickRouteStart.STARTED
            },
            onCreated = { handoff ->
                presentation.trackClickHandoff(handoff)
                clickHandoffPending = true
            },
            onFinished = { handoff ->
                presentation.clearClickHandoff(handoff)
                clickHandoffPending = presentation.pendingClickHandoff() != null
            },
        )
        return true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (!bridgeUnavailable) AndroidView(
            factory = { ctx ->
                var realLoadArmed = false
                var mainFrameLoadFailed = false
                WebViewPool.acquire(
                    context = ctx,
                    client = object : CreativeTelemetryWebViewClient("rewarded") {
                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, pageUrl, favicon) // starts the page-load timer
                            primaryCtaNavigation.onNavigationStarted(pageUrl, fallbackOwner)
                            if (CreativeCtaRouter.matchesKnownTrackingUrl(pageUrl, presentation.trackingUrl)) {
                                presentation.automaticNavigationGate.markTrackerRequestedInWebView()
                            }
                            BridgeWebViewInstaller.onPageStarted(view)
                        }

                        override fun onPageCommitVisible(view: WebView?, url: String?) {
                            super.onPageCommitVisible(view, url)
                            if (view != null && view === creativeWebView && !rendererGone &&
                                isQualifiedRewardedCreativeCommit(
                                    source = creativeSource,
                                    loadArmed = realLoadArmed,
                                    mainFrameFailed = mainFrameLoadFailed,
                                    url = url,
                                )
                            ) {
                                creativeCommitTimeout?.let(clickHandoffHandler::removeCallbacks)
                                creativeCommitTimeout = null
                                creativeCommitted = true
                                presentation.creativeExposed = true
                                if (bridgeInstalled) bridgeReady = true
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            super.onReceivedError(view, request, error)
                            if (view === creativeWebView && request?.isForMainFrame == true) {
                                creativeCommitTimeout?.let(clickHandoffHandler::removeCallbacks)
                                creativeCommitTimeout = null
                                mainFrameLoadFailed = true
                                presentation.clearPrimaryFallback(fallbackOwner)
                                runCatching { view?.visibility = View.INVISIBLE }
                                markBridgeUnavailable()
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?,
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            if (view === creativeWebView && request?.isForMainFrame == true) {
                                creativeCommitTimeout?.let(clickHandoffHandler::removeCallbacks)
                                creativeCommitTimeout = null
                                mainFrameLoadFailed = true
                                presentation.clearPrimaryFallback(fallbackOwner)
                                runCatching { view?.visibility = View.INVISIBLE }
                                markBridgeUnavailable()
                            }
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?,
                        ): Boolean {
                            runCatching { super.onRenderProcessGone(view, detail) }
                            if (view != null && view === creativeWebView) {
                                creativeCommitTimeout?.let(clickHandoffHandler::removeCallbacks)
                                creativeCommitTimeout = null
                                // A renderer-dead WebView cannot be resumed or repainted. Remove its
                                // dead surface immediately and let the existing graceful failure path
                                // complete the rewarded creative without exposing a black screen.
                                rendererGone = true
                                presentation.clearPrimaryFallback(fallbackOwner)
                                view.visibility = View.INVISIBLE
                                // Once content was visibly committed, renderer loss is SDK failure,
                                // not an early user exit; fail open so the user keeps the reward.
                                val earned = rewardEarnedAfterRendererGone(
                                    creativeCommitted = presentation.creativeExposed,
                                    candidate = rewardEarned,
                                    retained = presentation.rewardEarned,
                                )
                                presentation.rewardEarned = earned
                                rewardEarned = earned
                                markBridgeUnavailable()
                            }
                            return true
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val requestUrl = request?.url?.toString() ?: return false
                            primaryCtaNavigation.navigationOverride(
                                targetUrl = requestUrl,
                                isMainFrame = request.isForMainFrame,
                                hasGesture = request.hasGesture(),
                                owner = fallbackOwner,
                            )?.let { return it }
                            return when (val action = rewardedNavigationAction(
                                isMainFrame = request.isForMainFrame,
                                hasGesture = request.hasGesture(),
                                targetUrl = requestUrl,
                                currentPageUrl = view?.url,
                                initialPageUrl = initialPageUrl,
                                destination = presentation.destination,
                                trackingUrl = presentation.trackingUrl,
                            )) {
                                RewardedNavigationAction.AllowInWebView -> false
                                RewardedNavigationAction.Consume -> true
                                RewardedNavigationAction.RouteUserCta -> beginPrimaryCta(requestUrl, view?.url)
                                is RewardedNavigationAction.RouteAutomatic -> {
                                    if (view !== creativeWebView) return true
                                    presentation.automaticNavigationGate.retain(
                                        action.targetUrl,
                                        presentation.automaticNavigationGate.wasTrackerRequestedInWebView() ||
                                            CreativeCtaRouter.matchesKnownTrackingUrl(
                                                view?.url,
                                                presentation.trackingUrl,
                                            ),
                                    )
                                    routeAutomaticStoreNavigation()
                                    true
                                }
                            }
                        }
                    },
                    surface = "rewarded",
                ).apply {
                    webChromeClient = CreativeTelemetryWebChromeClient("rewarded", SimulaAds.devMode)
                    val injectionMode = BridgeWebViewInstaller.install(this, bridge) { url ->
                        beginPrimaryCta(url)
                    }
                    if (injectionMode == BridgeInjectionMode.UNAVAILABLE) {
                        post { if (creativeWebView === this) markBridgeUnavailable() }
                    } else {
                        post {
                            if (creativeWebView !== this || rendererGone || bridgeUnavailable) return@post
                            displayAdmitted = admitFullscreenDisplay(
                                alreadyReported = presentation.displayedReported,
                                markReported = { presentation.displayedReported = true },
                                notifyDisplayed = presentation.callbacks::onDisplayed,
                                enqueueShown = {
                                    AdBeaconManager.enqueue(
                                        presentation.impressionId,
                                        "shown",
                                        adFormat = "rewarded",
                                        telemetryServeId = presentation.impressionId.takeIf { it.isNotBlank() },
                                    )
                                },
                            )
                            bridgeInstalled = true
                            if (creativeCommitted) bridgeReady = true
                        }
                        creativeWebView = this
                        realLoadArmed = true
                        val timeout = Runnable {
                            if (creativeWebView === this && !creativeCommitted && !rendererGone && !bridgeUnavailable) {
                                mainFrameLoadFailed = true
                                presentation.clearPrimaryFallback(fallbackOwner)
                                runCatching { visibility = View.INVISIBLE }
                                markBridgeUnavailable()
                            }
                        }
                        creativeCommitTimeout = timeout
                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            clickHandoffHandler.postDelayed(timeout, REWARDED_CREATIVE_COMMIT_TIMEOUT_MS)
                        }
                        when (val source = creativeSource) {
                            is RewardedCreativeSource.Html -> {
                                // Primary HTML stays opaque and never inherits iframe origin state.
                                loadDataWithBaseURL(null, source.value, "text/html", "UTF-8", null)
                            }
                            is RewardedCreativeSource.Iframe -> loadUrl(source.url)
                            null -> Unit
                        }
                    }
                    if (creativeWebView == null) creativeWebView = this
                }
            },
            // The game canvas fills edge-to-edge: inset only vertically (status / nav / top
            // notch) and let it draw under any horizontal display-cutout. In landscape on a
            // device with a side cutout, padding the cutout in would expose the transparent
            // WebView's black backing as left/right "black bars" around the game. The overlay
            // controls below keep the full safeDrawing inset so they never sit under a cutout.
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
            onRelease = { webView ->
                creativeCommitTimeout?.let(clickHandoffHandler::removeCallbacks)
                creativeCommitTimeout = null
                if (creativeWebView === webView) creativeWebView = null
                presentation.clearPrimaryFallback(fallbackOwner)
                if (rendererGone) {
                    BridgeWebViewInstaller.releaseAfterRendererGone(webView)
                } else {
                    BridgeWebViewInstaller.release(webView)
                }
            },
        )

        // Close button — honors the server `ad_behavior.close` treatment (hidden / countdown ring /
        // progress bar / reward-or-close label) exactly like the interstitial, but gated on the
        // play-to-earn progress: the ✕ unlocks only once the reward is earned.
        val close = presentation.adBehavior?.close ?: CloseBehavior()
        AdCloseButton(
            treatment = close.treatment,
            position = close.position,
            progressBarColor = close.progressBarColor,
            isRewardCopy = true,
            enabled = canDismissFullscreen(rewardEarned, clickHandoffPending, displayAdmitted, storeVisitPending),
            remaining = secondsLeft,
            progress = closeProgress.value,
            onClose = {
                if (canDismissFullscreen(rewardEarned, clickHandoffPending, displayAdmitted, storeVisitPending)) {
                    presentation.automaticNavigationGate.clear()
                    onFinish(true)
                }
            },
        )

        // Mid-ad store prompt — appears at half the play-to-earn gate and is removed the instant the
        // reward unlocks (the reward/close pill takes over). Pinned to the corner opposite the
        // reward/close pill (the SDK mirrors the close position); a tap routes to the advertised store.
        if (displayAdmitted && storePrompt != null && storePrompt.enabled && storePromptVisible && !rewardEarned) {
            StorePromptBadge(
                prompt = storePrompt,
                closePosition = close.position,
                // Match the reward/close pill's 8dp inset and center the badge in the same 48dp
                // touch-target band so the two share one centerline (parity with the interstitial).
                edgePadding = 8.dp,
                rowHeight = MIN_TOUCH_TARGET_DP.dp,
                onTap = {
                    // A genuine admitted tap notifies once; auto redirects never enter this path.
                    val claim = notifyPublisherClickForClaim(
                        presentation.claimClick(ClickSources.STORE_PROMPT),
                        { presentation.callbacks.notifyClicked() },
                    ) ?: return@StorePromptBadge
                    val interaction = claim.interaction
                    coordinateDeferredClickPersistence(
                        mainHandler = clickHandoffHandler,
                        claim = claim,
                        enqueueBeacon = { completion ->
                            AdBeaconManager.enqueue(
                                presentation.impressionId,
                                "click",
                                adFormat = "rewarded",
                                telemetryServeId = presentation.impressionId.takeIf { it.isNotBlank() },
                                interactionId = interaction.id,
                                clickSource = interaction.source,
                                onPersistenceComplete = completion,
                            )
                        },
                        recordTelemetry = { completion ->
                            presentation.callbacks.persistClick(interaction, completion)
                        },
                        onHandoff = { committedInteraction, completion ->
                            val result = presentation.routeClick({ routeActivity ->
                                if (!canRouteFromCurrentFullscreenActivity(
                                        routeActivity.isFinishing,
                                        routeActivity.isDestroyed,
                                    )
                                ) return@routeClick false
                                val opened = CreativeCtaRouter.open(
                                    routeActivity.applicationContext,
                                    presentation.trackingUrl,
                                    presentation.destination,
                                    presentation.adBehavior?.storeOpen,
                                    presentation.androidStoreUrl,
                                )
                                if (opened) {
                                    routeActivity.recordClickStoreOpen(committedInteraction.source)
                                }
                                opened
                            }, completion)
                            if (result == PresentationRouteResult.REJECTED) ClickRouteStart.REJECTED else ClickRouteStart.STARTED
                        },
                        onCreated = { handoff ->
                            presentation.trackClickHandoff(handoff)
                            clickHandoffPending = true
                        },
                        onFinished = { handoff ->
                            presentation.clearClickHandoff(handoff)
                            clickHandoffPending = presentation.pendingClickHandoff() != null
                        },
                    )
                },
            )
        }

        // Persistent ad-info "i" + report sheet (required disclosure). Last so its sheet overlays.
        AdInfoReportOverlay(
            adId = presentation.impressionId,
            apiKey = presentation.apiKey,
            // A genuine bottom-left ✕ shares the bottom-left corner with the "i" (shrink its hit area);
            // a progress_bar bottom ✕ relocates to top-right, leaving the "i" its full hit area.
            closeAtBottomLeft = close.position == ClosePosition.BOTTOM_LEFT && !closeBarAtBottom(close.treatment, close.position),
        )
    }
}

/** 0→1 close-treatment fill from play-to-earn progress (foreground play time / required duration). */
private fun rewardCloseProgress(playMs: Long, durationSeconds: Int): Float =
    if (durationSeconds > 0) (playMs.toFloat() / (durationSeconds * 1000f)).coerceIn(0f, 1f) else 1f
