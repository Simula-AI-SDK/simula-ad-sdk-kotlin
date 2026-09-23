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
import ad.simula.ad.sdk.model.CreativeType
import ad.simula.ad.sdk.model.ProgressBarStyle
import ad.simula.ad.sdk.model.RewardCompletionReason
import ad.simula.ad.sdk.model.closeGateSecondsLeft
import ad.simula.ad.sdk.model.videoCloseGateMs
import ad.simula.ad.sdk.model.retainVideoMaxPosition
import ad.simula.ad.sdk.model.rewardedVideoDurationGateReached
import ad.simula.ad.sdk.model.videoStorePromptReached
import ad.simula.ad.sdk.model.VideoChromeStyle
import ad.simula.ad.sdk.model.VideoLifecycleReason
import ad.simula.ad.sdk.model.VideoSequenceAdvance
import ad.simula.ad.sdk.model.primaryCreativeCloseAllowed
import ad.simula.ad.sdk.model.videoSequenceAdvance
import ad.simula.ad.sdk.model.effectiveSkOverlayConfig
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
import androidx.compose.runtime.mutableFloatStateOf
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
 * [RewardedPresentation] from [RewardedHandoff] by token and renders playable HTML or native video
 * with the play-to-earn chrome. Mirrors [SimulaInterstitialActivity].
 */
internal class SimulaRewardedActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TOKEN = "ad.simula.ad.sdk.REWARDED_TOKEN"
    }

    private var presentation: RewardedPresentation? = null
    private var token: String? = null
    private var closed = false
    private var displayFailed = false
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
                    adUnitId = p.adUnitId,
                    presentationState = p.fallbackState,
                    onFullyClosed = ::completeReward,
                    onAuthoritativeEndReached = {
                        if (p.videoContract2 &&
                            p.adBehavior?.reward?.earnAt == ad.simula.ad.sdk.model.RewardEarnAt.UNIT_END
                        ) dispatchRewardCompletion(p.markAuthoritativeEndReached())
                    },
                    autoStoreRedirect = p.adBehavior?.autoStoreRedirect,
                    onAdClick = { p.callbacks.notifyClicked(it) },
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
                    claimTrustedClick = p::claimTrustedClick,
                    claimWebClick = p::claimWebClick,
                    routeClick = { route, completion ->
                        val result = p.routeClick(
                            route = { activity ->
                                if (!canRouteFromCurrentFullscreenActivity(
                                        activity.isFinishing,
                                        activity.isDestroyed,
                                    )) false else route(activity)
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
                    storeVisitPending = { storeExit?.hasPendingStoreVisit() == true },
                    // END_SCREEN_N opens the primary ad's store (the same path as a CTA / PLAYABLE_END).
                    onAutoStoreRedirect = { canOpen, completion, registerCancellation ->
                        val job = CreativeCtaRouter.prepareInBackground(
                            prepare = {
                                CreativeCtaRouter.prepare(
                                    p.trackingUrl,
                                    p.destination,
                                    p.androidStoreUrl,
                                )
                            },
                            onPrepared = { prepared ->
                                runWhenLifecycleResumed(
                                    lifecycle = lifecycle,
                                    canRun = canOpen,
                                    onResumed = {
                                        val outcome = CreativeCtaRouter.launchPrepared(
                                            this@SimulaRewardedActivity,
                                            prepared,
                                        )
                                        if (outcome == AutomaticNavigationOutcome.STORE_OPENED) {
                                            storeExit?.recordStoreOpen(ClickSources.AUTO_REDIRECT)
                                        }
                                        completion(outcome != AutomaticNavigationOutcome.FAILED)
                                    },
                                    onUnavailable = { completion(false) },
                                )
                            },
                        )
                        registerCancellation(job::cancel)
                    },
                    openAutomaticNavigation = { targetUrl, trackerAlreadyRequested, canOpen, completion, registerCancellation ->
                        val job = CreativeCtaRouter.prepareInBackground(
                            prepare = {
                                CreativeCtaRouter.prepareAutomaticNavigation(
                                    targetUrl,
                                    p.destination,
                                    p.trackingUrl,
                                    trackerAlreadyRequested,
                                )
                            },
                            onPrepared = { prepared ->
                                runWhenLifecycleResumed(
                                    lifecycle = lifecycle,
                                    canRun = canOpen,
                                    onResumed = {
                                        val outcome = CreativeCtaRouter.launchPrepared(
                                            this@SimulaRewardedActivity,
                                            prepared,
                                        )
                                        if (outcome == AutomaticNavigationOutcome.STORE_OPENED) {
                                            storeExit?.recordStoreOpen(ClickSources.AUTO_REDIRECT)
                                        }
                                        completion(outcome)
                                    },
                                    onUnavailable = { completion(AutomaticNavigationOutcome.FAILED) },
                                )
                            },
                        )
                        registerCancellation(job::cancel)
                    },
                    // End-screen CTA routing context (deterministic store fallback).
                    ctaTrackingUrl = p.trackingUrl,
                    ctaDestination = p.destination,
                    ctaStoreUrl = p.androidStoreUrl,
                    videoPlanV2 = p.videoContract2,
                ) { onClose, nextVideoUrl, hasNextStep ->
                    RewardedMinigame(
                        presentation = p,
                        nextVideoUrl = nextVideoUrl,
                        hasNextStep = hasNextStep,
                        storeVisitPending = { storeExit?.hasPendingStoreVisit() == true },
                        recordStoreOpen = { trigger -> storeExit?.recordStoreOpen(trigger) },
                        onRewardCompletionClaim = ::dispatchRewardCompletion,
                        onPreFirstFrameEscape = ::failBeforeDisplayAdmission,
                        onFinish = { earned ->
                            if (!p.videoContract2 ||
                                p.adBehavior?.reward?.earnAt != ad.simula.ad.sdk.model.RewardEarnAt.UNIT_END
                            ) {
                                p.retainRewardEarned(earned)
                            }
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
        if (closed || displayFailed) return
        closed = true
        runCatching { storeExit?.onAdClosed() } // resolve any outstanding store visit as an abandon
        presentation?.let { p ->
            val earnedClaim = p.claimEarnedRewardOnTeardown()
            runCatching { p.callbacks.onPresentationAborted(p.rewardEarned, elapsedSeconds(p)) }
            if (earnedClaim != null) {
                runCatching {
                    p.callbacks.onRewardCompleted(
                        earnedClaim.earned,
                        elapsedSeconds(p),
                        earnedClaim.reason,
                    )
                }
            }
        }
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
        val p = presentation
        if (p?.videoContract2 == true &&
            p.adBehavior?.reward?.earnAt == ad.simula.ad.sdk.model.RewardEarnAt.UNIT_END
        ) dispatchRewardCompletion(p.markAuthoritativeEndReached())
        val claim = p?.claimRewardCompletion()
        if (p != null && claim != null && !closed && !displayFailed) {
            closed = true
            runCatching { storeExit?.onAdClosed() }
            runCatching {
                p.callbacks.onWholeUnitCompleted(claim.earned, elapsedSeconds(p), claim.reason)
            }
        } else {
            reportClosed()
        }
        finishAd()
    }

    private fun dispatchRewardCompletion(claim: RewardCompletionClaim?) {
        val p = presentation ?: return
        claim ?: return
        runCatching { p.callbacks.onRewardCompleted(claim.earned, elapsedSeconds(p), claim.reason) }
    }

    /** Tear the Activity down (after the optional fallback ad). */
    private fun finishAd() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun failBeforeDisplayAdmission() {
        if (displayFailed || presentation?.displayedReported == true) return
        displayFailed = true
        runCatching { presentation?.callbacks?.onDisplayFailed(SimulaAdError.NoFill) }
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
            reportClosed()
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

internal fun rewardEarnedAfterCreativeFailure(
    isVideo: Boolean,
    everCreativeReady: Boolean,
    candidate: Boolean,
    retained: Boolean,
): Boolean = candidate || retained || (!isVideo && everCreativeReady)

internal fun rewardedDismissalDisplayAdmitted(
    currentDisplayAdmitted: Boolean,
    previouslyDisplayed: Boolean,
): Boolean = currentDisplayAdmitted || previouslyDisplayed

internal enum class RewardedUnavailableCreativeAction { WAIT, ESCAPE, EXIT }

internal fun rewardedUnavailableCreativeAction(
    isVideo: Boolean,
    displayAdmitted: Boolean,
    creativeUnavailable: Boolean,
    clickHandoffPending: Boolean,
    storeVisitPending: Boolean,
): RewardedUnavailableCreativeAction {
    if (!creativeUnavailable || clickHandoffPending || storeVisitPending) {
        return RewardedUnavailableCreativeAction.WAIT
    }
    return if (videoPreFirstFrameEscapeAvailable(isVideo, displayAdmitted)) {
        RewardedUnavailableCreativeAction.ESCAPE
    } else {
        RewardedUnavailableCreativeAction.EXIT
    }
}

internal fun rewardedVideoCtaExecutionRoute(route: PrimaryCtaRoute): PrimaryCtaRoute = route

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
    nextVideoUrl: String?,
    hasNextStep: () -> Boolean,
    storeVisitPending: () -> Boolean,
    recordStoreOpen: (String) -> Unit,
    onRewardCompletionClaim: (RewardCompletionClaim?) -> Unit,
    onPreFirstFrameEscape: () -> Unit,
    onFinish: (earned: Boolean) -> Unit,
) {
    val isVideo = presentation.creative.type == CreativeType.VIDEO
    val creativeSource = remember(presentation) { rewardedCreativeSource(presentation.renderedHtml) }
    val initialPageUrl: String? = null
    // Play-to-earn gate length, in seconds — sourced from `ad_behavior.close.delay_seconds` (the
    // same value that ungates the close button). No `ad_behavior` → 0 → instantly earned.
    val gateSeconds = presentation.adBehavior?.close?.delaySeconds ?: 0
    val close = presentation.adBehavior?.close ?: CloseBehavior()
    val unitEndMode = presentation.videoContract2 &&
        presentation.adBehavior?.reward?.earnAt == ad.simula.ad.sdk.model.RewardEarnAt.UNIT_END
    val videoProgressBarStyle = presentation.adBehavior?.progressBar?.style
        ?.takeIf { isVideo && presentation.videoContract2 }
        ?: ProgressBarStyle.SINGLE

    // Earned immediately when there is no gate; otherwise resolved by the timer below.
    // A gate that already elapsed in a prior Activity instance (config-change recreation)
    // also starts earned — accumulated play time survives on the presentation.
    var rewardEarned by remember {
        mutableStateOf(
            if (unitEndMode) presentation.primaryProgressionAllowed
            else initialRewardEarned(presentation.rewardEarned, presentation.accumulatedPlayTimeMs, gateSeconds),
        )
    }
    LaunchedEffect(presentation, gateSeconds) {
        if (RewardGate.isEarned(presentation.accumulatedPlayTimeMs, gateSeconds)) {
            if (unitEndMode) {
                val update = presentation.markPrimaryProgressionAllowed()
                onRewardCompletionClaim(update.completionClaim)
            } else {
                presentation.recordCompletionReason(RewardCompletionReason.DURATION_ELAPSED)
                presentation.retainRewardEarned(true)
            }
            rewardEarned = true
            if (unitEndMode && !isVideo) hasNextStep()
        }
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
    var videoCloseProgress by remember(presentation) {
        val requiredMs = videoCloseGateMs(gateSeconds, presentation.videoDurationMs)
        mutableFloatStateOf(
            if (requiredMs > 0L) {
                (presentation.accumulatedPlayTimeMs.toFloat() / requiredMs).coerceIn(0f, 1f)
            } else 1f,
        )
    }
    var videoPlaybackProgress by remember(presentation) {
        mutableFloatStateOf(
            if (presentation.videoDurationMs > 0L) {
                (presentation.videoPositionMs.toFloat() / presentation.videoDurationMs).coerceIn(0f, 1f)
            } else 0f,
        )
    }
    val smoothVideoCloseProgress = smoothVideoProgress(videoCloseProgress)
    val showsCloseBar = presentation.adBehavior?.close?.treatment.let {
        it == CloseTreatment.COUNTDOWN_CIRCLE || it == CloseTreatment.PROGRESS_BAR
    }

    // Mid-ad store prompt — shown from half the play-to-earn gate until the reward unlocks.
    // Initialized true on a config-change recreation that resumes past the halfway mark.
    val storePrompt = presentation.adBehavior?.storePrompt
    val videoSkOverlay = presentation.adBehavior.effectiveSkOverlayConfig(presentation.videoContract2)
    var storePromptVisible by remember {
        mutableStateOf(
            storePrompt != null && storePrompt.enabled &&
                if (isVideo) {
                    videoStorePromptReached(
                        videoPlanV2 = presentation.videoContract2,
                        positionMs = presentation.videoPositionMs,
                        durationMs = presentation.videoDurationMs,
                        gateElapsedMs = presentation.accumulatedPlayTimeMs,
                        effectiveGateMs = videoCloseGateMs(gateSeconds, presentation.videoDurationMs),
                    )
                } else {
                    gateSeconds > 0 && presentation.accumulatedPlayTimeMs >= gateSeconds * 1000L / 2
                },
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
        onDispose {
            presentation.automaticNavigationGate.abandonInFlight()
            presentation.autoRedirectCoordinator.deactivate(autoRedirectScope)
        }
    }
    fun routeAutomaticStoreNavigation() {
        val result = presentation.autoRedirectCoordinator.requestAsync(
            scope = autoRedirectScope,
            pendingHandoff = presentation.pendingClickHandoff(),
        ) { routeCanOpen, completion, registerCancellation ->
            prepareAutomaticCtaRoute(
                gate = presentation.automaticNavigationGate,
                lifecycle = lifecycleOwner.lifecycle,
                prepare = { route ->
                    CreativeCtaRouter.prepareAutomaticNavigation(
                        route.targetUrl,
                        presentation.destination,
                        presentation.trackingUrl,
                        route.trackerAlreadyRequested,
                    )
                },
                canOpen = {
                    routeCanOpen() && presentation.autoRedirectCoordinator.isActive(autoRedirectScope)
                },
                open = { prepared ->
                    CreativeCtaRouter.launchPrepared(context, prepared).also { outcome ->
                        if (outcome == AutomaticNavigationOutcome.STORE_OPENED) {
                            recordStoreOpen(ClickSources.AUTO_REDIRECT)
                        }
                    }
                },
                completion = completion,
                registerCancellation = registerCancellation,
            )
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
    val htmlReadiness = remember(presentation) { RewardedHtmlReadinessGate() }
    val commitTimeoutBudget = remember(presentation) {
        ForegroundTimeoutBudget(REWARDED_CREATIVE_COMMIT_TIMEOUT_MS)
    }
    var bridgeReady by remember(presentation) { mutableStateOf(false) }
    var displayAdmitted by remember(presentation) { mutableStateOf(presentation.displayedReported) }
    var bridgeUnavailable by remember(presentation) {
        mutableStateOf(presentation.primaryCreativeUnavailable)
    }
    var videoTerminal by remember(presentation) { mutableStateOf(false) }
    fun earnCreativeCompletion() {
        if (unitEndMode) {
            val update = presentation.markPrimaryProgressionAllowed()
            onRewardCompletionClaim(update.completionClaim)
        } else {
            presentation.recordCompletionReason(RewardCompletionReason.CREATIVE_COMPLETED)
            presentation.retainRewardEarned(true)
        }
        rewardEarned = true
        if (unitEndMode) hasNextStep()
    }
    fun markBridgeUnavailable() {
        presentation.earlyCompleteState.discard()
        if (!unitEndMode && !isVideo && presentation.everCreativeReady &&
            !presentation.rewardEarned && !rewardEarned
        ) {
            presentation.recordCompletionReason(RewardCompletionReason.CREATIVE_COMPLETED)
        }
        val earned = if (unitEndMode) rewardEarned else rewardEarnedAfterCreativeFailure(
            isVideo = isVideo,
            everCreativeReady = presentation.everCreativeReady,
            candidate = rewardEarned,
            retained = presentation.rewardEarned,
        )
        presentation.retainRewardEarned(earned)
        rewardEarned = earned
        presentation.automaticNavigationGate.clear()
        presentation.autoRedirectCoordinator.deactivate(autoRedirectScope)
        presentation.primaryCreativeUnavailable = true
        bridgeUnavailable = true
    }
    var unavailableExitIssued by remember(presentation) { mutableStateOf(false) }
    val storeVisitBlocked = storeVisitPending()
    LaunchedEffect(bridgeUnavailable, clickHandoffPending, storeVisitBlocked) {
        if (rewardedUnavailableCreativeAction(
                isVideo = isVideo,
                displayAdmitted = displayAdmitted,
                creativeUnavailable = bridgeUnavailable,
                clickHandoffPending = clickHandoffPending,
                storeVisitPending = storeVisitBlocked,
            ) != RewardedUnavailableCreativeAction.EXIT
        ) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            withFrameNanos { }
            if (!shouldExitUnavailableCreative(
                    creativeUnavailable = bridgeUnavailable,
                    clickHandoffPending = presentation.pendingClickHandoff() != null,
                    storeVisitPending = storeVisitPending(),
                )
            ) return@repeatOnLifecycle
            if (unavailableExitIssued) return@repeatOnLifecycle
            unavailableExitIssued = true
            val earned = monotonicRewardEarned(rewardEarned, presentation.rewardEarned)
            rewardEarned = earned
            onFinish(earned)
        }
    }
    // Completion unlocks the gate; a user close tap reveals the next screen.

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
                    commitTimeoutBudget.pause(SystemClock.elapsedRealtime())
                    if (!rendererGone) wv?.onPause()
                }
                Lifecycle.Event.ON_STOP -> wasStopped = true
                Lifecycle.Event.ON_RESUME -> {
                    if (rendererGone) return@LifecycleEventObserver
                    creativeCommitTimeout?.takeIf { !creativeCommitted && !bridgeUnavailable }?.let { timeout ->
                        clickHandoffHandler.removeCallbacks(timeout)
                        clickHandoffHandler.postDelayed(
                            timeout,
                            commitTimeoutBudget.resume(SystemClock.elapsedRealtime()),
                        )
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
        presentation.autoRedirectCoordinator.requestAsync(
            scope = autoRedirectScope,
            pendingHandoff = presentation.pendingClickHandoff(),
        ) { routeCanOpen, completion, registerCancellation ->
            val job = CreativeCtaRouter.prepareInBackground(
                prepare = {
                    CreativeCtaRouter.prepare(
                        presentation.trackingUrl,
                        presentation.destination,
                        presentation.androidStoreUrl,
                    )
                },
                onPrepared = { prepared ->
                    runWhenLifecycleResumed(
                        lifecycle = lifecycleOwner.lifecycle,
                        canRun = {
                            routeCanOpen() && presentation.autoRedirectCoordinator.isActive(autoRedirectScope)
                        },
                        onResumed = {
                            val outcome = CreativeCtaRouter.launchPrepared(context, prepared)
                            if (outcome == AutomaticNavigationOutcome.STORE_OPENED) {
                                recordStoreOpen(ClickSources.AUTO_REDIRECT)
                            }
                            completion(outcome != AutomaticNavigationOutcome.FAILED)
                        },
                        onUnavailable = { completion(false) },
                    )
                },
            )
            registerCancellation(job::cancel)
        }
    }
    val bridge = remember {
        androidCreativeBridge(
            appContext = context.applicationContext,
            activityProvider = { context as? Activity },
            onEarlyComplete = {
                if (rewardedEarlyCompleteApplicable(isVideo) &&
                    presentation.earlyCompleteState.signal(bridgeReady && displayAdmitted)
                ) {
                    earnCreativeCompletion()
                }
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
                impressionUrl = presentation.impressionUrl,
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
    LaunchedEffect(bridgeReady, rewardEarned) {
        if (!bridgeReady) return@LaunchedEffect
        if (isVideo) return@LaunchedEffect
        val requiredMs = gateSeconds.coerceAtLeast(0) * 1_000L
        if (presentation.rewardEarned) {
            rewardEarned = true
            return@LaunchedEffect
        }
        if (requiredMs <= 0L) {
            if (unitEndMode) {
                val update = presentation.markPrimaryProgressionAllowed()
                onRewardCompletionClaim(update.completionClaim)
            } else {
                presentation.recordCompletionReason(RewardCompletionReason.DURATION_ELAPSED)
                presentation.retainRewardEarned(true)
            }
            rewardEarned = true
            if (unitEndMode) hasNextStep()
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
                closeProgress.snapTo(
                    (presentation.accumulatedPlayTimeMs.toFloat() / requiredMs.coerceAtLeast(1L)).coerceIn(0f, 1f),
                )
                val remainingMs = (requiredMs - presentation.accumulatedPlayTimeMs).coerceAtLeast(0L)
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
                secondsLeft = closeGateSecondsLeft(presentation.accumulatedPlayTimeMs, requiredMs)
                // Reveal the store prompt at the halfway point to the reward (mid play-to-earn).
                if (!isVideo && presentation.accumulatedPlayTimeMs >= requiredMs / 2L) {
                    storePromptVisible = true
                }
                if (presentation.accumulatedPlayTimeMs >= requiredMs) {
                    if (unitEndMode) {
                        val update = presentation.markPrimaryProgressionAllowed()
                        onRewardCompletionClaim(update.completionClaim)
                    } else {
                        presentation.recordCompletionReason(RewardCompletionReason.DURATION_ELAPSED)
                        presentation.retainRewardEarned(true)
                    }
                    rewardEarned = true
                    if (unitEndMode) hasNextStep()
                    break
                }
            }
        }
    }

    // No early exit: Back does nothing until the reward is earned, then it closes (earned).
    BackHandler(enabled = true) {
        if (videoPreFirstFrameEscapeAvailable(isVideo, displayAdmitted)) {
            onPreFirstFrameEscape()
            return@BackHandler
        }
        if (canDismissFullscreen(
                rewardEarned,
                clickHandoffPending,
                rewardedDismissalDisplayAdmitted(displayAdmitted, presentation.displayedReported),
                storeVisitBlocked,
            )
        ) {
            val closeClaimed = primaryCreativeCloseAllowed(
                presentation.videoContract2,
                presentation.creative.type,
                videoTerminal,
            ) { presentation.fallbackState.videoPlan.closeCurrent(VideoLifecycleReason.USER, hasNextStep()) }
            if (!closeClaimed) return@BackHandler
            presentation.automaticNavigationGate.clear()
            onFinish(true)
        }
    }

    fun beginPrimaryCta(
        route: PrimaryCtaRoute,
        trusted: ad.simula.ad.sdk.bridge.TrustedCtaOpen? = null,
    ): Boolean {
        val claim = (if (trusted == null) {
            presentation.claimClick(ClickSources.PRIMARY_CTA)
        } else if (trusted.interactionId == null) {
            presentation.claimWebClick(
                ClickSources.trustedHtmlOr(trusted.clickSource, ClickSources.PRIMARY_UNKNOWN),
            )
        } else {
            presentation.claimTrustedClick(
                trusted.interactionId,
                ClickSources.trustedHtmlOr(trusted.clickSource, ClickSources.PRIMARY_UNKNOWN),
            )
        }) ?: return true
        notifyPublisherClick { presentation.callbacks.notifyClicked(claim.interaction) }
        val interaction = claim.interaction
        val routeStartedAtNanos = System.nanoTime()
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
                prepareDeferredCtaRoute(
                    prepare = {
                        CreativeCtaRouter.preparePrimaryCta(
                            route,
                            presentation.destination,
                            presentation.androidStoreUrl,
                            routeStartedAtNanos,
                        )
                    },
                    requestRoute = presentation::routeClick,
                    completion = completion,
                    open = { routeActivity, prepared ->
                        if (!canRouteFromCurrentFullscreenActivity(
                                routeActivity.isFinishing,
                                routeActivity.isDestroyed,
                            )
                        ) return@prepareDeferredCtaRoute false
                        val outcome = CreativeCtaRouter.launchPrepared(routeActivity, prepared)
                        val opened = outcome != AutomaticNavigationOutcome.FAILED &&
                            outcome != AutomaticNavigationOutcome.HANDLED
                        if (opened) {
                            presentation.primaryCtaNavigation.lockAfterExternalOpen()
                            presentation.autoRedirectCoordinator.recordUserRouteOpened()
                            if (outcome == AutomaticNavigationOutcome.STORE_OPENED) {
                                routeActivity.recordClickStoreOpen(committedInteraction.source)
                            }
                        } else {
                            CreativeCtaRouter.admittedInWebViewFallback(
                                route.tappedUrl,
                                presentation.trackingUrl,
                            )?.let { presentation.openPrimaryFallback(it, routeActivity) }
                        }
                        opened
                    },
                )
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
        return beginPrimaryCta(
            route,
            ad.simula.ad.sdk.bridge.TrustedCtaOpen(
                url = tappedUrl,
                interactionId = null,
                clickSource = ClickSources.PRIMARY_UNKNOWN,
            ),
        )
    }

    fun beginTrustedPrimaryCta(request: ad.simula.ad.sdk.bridge.TrustedCtaOpen): Boolean {
        val route = when (val plan = CreativeCtaRouter.primaryCtaTapPlan(
            tappedUrl = request.url,
            creativeBaseUrl = CreativeCtaRouter.admittedHttpUrl(creativeWebView?.url) ?: initialPageUrl,
            trackingUrl = presentation.trackingUrl,
            destination = presentation.destination,
        )) {
            CreativeCtaRouter.PrimaryCtaTapPlan.AllowInWebView -> return false
            CreativeCtaRouter.PrimaryCtaTapPlan.ConsumeWithoutClick -> return true
            is CreativeCtaRouter.PrimaryCtaTapPlan.Route -> plan.route
        }
        return beginPrimaryCta(route, request)
    }

    fun beginVideoCta() {
        val route = videoCtaRoute(
            presentation.trackingUrl,
            presentation.androidStoreUrl,
            presentation.destination,
        ) ?: return
        beginPrimaryCta(rewardedVideoCtaExecutionRoute(route))
    }

    fun admitCreativeCommit(view: WebView?, qualified: Boolean) {
        if (!qualified || view == null || view !== creativeWebView || rendererGone || bridgeUnavailable) return
        creativeCommitTimeout?.let(clickHandoffHandler::removeCallbacks)
        creativeCommitTimeout = null
        commitTimeoutBudget.complete()
        htmlReadiness.terminate()
        creativeCommitted = true
        presentation.everCreativeReady = true
        if (bridgeInstalled && !bridgeReady) {
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
            bridgeReady = true
            if (presentation.earlyCompleteState.consumePending(displayAdmitted)) {
                earnCreativeCompletion()
            }
        }
    }

    fun requestHtmlVisualFence(view: WebView?, qualified: Boolean) {
        if (!qualified || view == null || view !== creativeWebView || rendererGone || bridgeUnavailable) return
        val request = htmlReadiness.onPageReady() ?: return
        runCatching {
            view.postVisualStateCallback(
                request,
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        admitCreativeCommit(
                            view = view,
                            qualified = htmlReadiness.acceptVisualState(requestId),
                        )
                    }
                },
            )
        }.onFailure {
            htmlReadiness.terminate()
            if (creativeWebView === view) markBridgeUnavailable()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (isVideo && !bridgeUnavailable) {
            presentation.videoLease?.file?.let { videoFile ->
                FullscreenVideo(
                    file = videoFile,
                    posterUrl = presentation.creative.posterUrl,
                    adFormat = "rewarded",
                    adUnitId = presentation.adUnitId,
                    adId = presentation.impressionId.takeIf { it.isNotBlank() },
                    serveId = presentation.impressionId.takeIf { it.isNotBlank() },
                    configuredGateSeconds = gateSeconds,
                    initialPlayedMs = presentation.accumulatedPlayTimeMs,
                    initialPositionMs = presentation.videoPositionMs,
                    ctaEnabled = videoCtaRoute(
                        presentation.trackingUrl,
                        presentation.androidStoreUrl,
                        presentation.destination,
                    ) != null,
                    ctaLabel = presentation.creative.cta,
                    appIconUrl = presentation.creative.appIconUrl,
                    appName = presentation.creative.appName,
                    subtitle = presentation.creative.subtitle,
                    chromeStyle = presentation.adBehavior?.video?.style
                        ?.takeIf { presentation.videoContract2 } ?: VideoChromeStyle.CORNER_CTA,
                    effectiveClosePosition = effectiveClosePosition(
                        close.treatment,
                        close.position,
                        videoProgressBarStyle,
                    ),
                    bottomProgressBarObstructed = progressBarAtBottom(
                        close.treatment,
                        close.position,
                        videoProgressBarStyle,
                    ),
                    videoPool = presentation.creative.videoPool,
                    playbackSlotIdentity = VideoPlaybackSlotIdentity.Primary,
                    clipIndex = presentation.creative.clipIndex,
                    skoverlayEnabled = videoSkOverlay?.enabled,
                    skoverlayDelaySeconds = videoSkOverlay?.delaySeconds,
                    videoPlanV2 = presentation.videoContract2,
                    segments = presentation.creative.segments.takeIf { presentation.videoContract2 }.orEmpty(),
                    videoPlanState = presentation.fallbackState.videoPlan,
                    presentationBlocked = clickHandoffPending || storeVisitBlocked,
                    willHandoff = hasNextStep,
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
                    onReady = { durationMs ->
                        presentation.videoDurationMs = durationMs
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
                        bridgeReady = true
                    },
                    onProgress = { positionMs, durationMs, advancedMs ->
                        presentation.videoPositionMs = retainVideoMaxPosition(
                            presentation.videoPositionMs,
                            positionMs,
                        )
                        presentation.videoDurationMs = durationMs
                        videoPlaybackProgress = if (durationMs > 0L) {
                            (presentation.videoPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        } else 0f
                        presentation.accumulatedPlayTimeMs += advancedMs
                        val requiredMs = videoCloseGateMs(gateSeconds, durationMs)
                        secondsLeft = closeGateSecondsLeft(presentation.accumulatedPlayTimeMs, requiredMs)
                        videoCloseProgress = if (requiredMs > 0L) {
                            (presentation.accumulatedPlayTimeMs.toFloat() / requiredMs).coerceIn(0f, 1f)
                        } else 1f
                        val promptReached = videoStorePromptReached(
                            videoPlanV2 = presentation.videoContract2,
                            positionMs = presentation.videoPositionMs,
                            durationMs = durationMs,
                            gateElapsedMs = presentation.accumulatedPlayTimeMs,
                            effectiveGateMs = requiredMs,
                        )
                        if (promptReached) {
                            storePromptVisible = true
                        }
                        if (rewardedVideoDurationGateReached(
                                accumulatedPlayTimeMs = presentation.accumulatedPlayTimeMs,
                                configuredDelaySeconds = gateSeconds,
                                durationMs = durationMs,
                            )
                        ) {
                            if (unitEndMode && !rewardEarned) {
                                val update = presentation.markPrimaryProgressionAllowed()
                                onRewardCompletionClaim(update.completionClaim)
                                rewardEarned = true
                                secondsLeft = 0
                                if (update.newlyAllowed) hasNextStep()
                            } else if (!unitEndMode) {
                                presentation.recordCompletionReason(RewardCompletionReason.DURATION_ELAPSED)
                                presentation.retainRewardEarned(true)
                                rewardEarned = true
                            }
                        }
                    },
                    onCompleted = {
                        if (unitEndMode) {
                            val update = presentation.markPrimaryProgressionAllowed()
                            onRewardCompletionClaim(update.completionClaim)
                            if (update.newlyAllowed) hasNextStep()
                        } else {
                            presentation.recordCompletionReason(RewardCompletionReason.VIDEO_COMPLETED)
                            presentation.retainRewardEarned(true)
                        }
                        rewardEarned = true
                        secondsLeft = 0
                        if (presentation.videoContract2) videoTerminal = true
                    },
                    onError = ::markBridgeUnavailable,
                    onCta = ::beginVideoCta,
                )
            }
        } else if (!bridgeUnavailable) AndroidView(
            factory = { ctx ->
                var realLoadArmed = false
                var mainFrameLoadFailed = false
                var bridgeMode = BridgeInjectionMode.UNAVAILABLE
                WebViewPool.acquire(
                    context = ctx,
                    client = object : CreativeTelemetryWebViewClient("rewarded") {
                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, pageUrl, favicon) // starts the page-load timer
                            if (creativeSource is RewardedCreativeSource.Html && view === creativeWebView) {
                                htmlReadiness.onPageStarted()
                            }
                            primaryCtaNavigation.onNavigationStarted(pageUrl, fallbackOwner)
                            if (CreativeCtaRouter.matchesKnownTrackingUrl(pageUrl, presentation.trackingUrl)) {
                                presentation.automaticNavigationGate.markTrackerRequestedInWebView()
                            }
                            BridgeWebViewInstaller.onPageStarted(view)
                        }

                        override fun onPageCommitVisible(view: WebView?, url: String?) {
                            super.onPageCommitVisible(view, url)
                            admitCreativeCommit(
                                view = view,
                                qualified = isQualifiedRewardedCreativeCommit(
                                    source = creativeSource,
                                    loadArmed = realLoadArmed,
                                    mainFrameFailed = mainFrameLoadFailed,
                                    url = url,
                                ),
                            )
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            requestHtmlVisualFence(
                                view = view,
                                qualified = bridgeMode == BridgeInjectionMode.PAGE_START_FALLBACK &&
                                    creativeSource is RewardedCreativeSource.Html &&
                                    realLoadArmed &&
                                    !mainFrameLoadFailed &&
                                    url == "about:blank",
                            )
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
                                commitTimeoutBudget.complete()
                                htmlReadiness.terminate()
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
                                commitTimeoutBudget.complete()
                                htmlReadiness.terminate()
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
                                commitTimeoutBudget.complete()
                                htmlReadiness.terminate()
                                // A renderer-dead WebView cannot be resumed or repainted. Remove its
                                // dead surface immediately and let the existing graceful failure path
                                // complete the rewarded creative without exposing a black screen.
                                rendererGone = true
                                presentation.clearPrimaryFallback(fallbackOwner)
                                view.visibility = View.INVISIBLE
                                // Playable HTML fails open after a visible commit. Video failures use
                                // the same path but preserve only rewards already earned by playback.
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
                    val target = this
                    val injectionMode = BridgeWebViewInstaller.install(
                        webView = this,
                        bridge = bridge,
                        onTrustedCtaOpen = ::beginTrustedPrimaryCta,
                        onPageReady = {
                            requestHtmlVisualFence(
                                view = target,
                                qualified = bridgeMode == BridgeInjectionMode.DOCUMENT_START &&
                                    creativeSource is RewardedCreativeSource.Html &&
                                    realLoadArmed &&
                                    !mainFrameLoadFailed,
                            )
                        },
                    )
                    bridgeMode = injectionMode
                    if (injectionMode == BridgeInjectionMode.UNAVAILABLE) {
                        post { if (creativeWebView === this) markBridgeUnavailable() }
                    } else {
                        post {
                            if (creativeWebView !== this || rendererGone || bridgeUnavailable) return@post
                            bridgeInstalled = true
                            admitCreativeCommit(this, creativeCommitted)
                        }
                        creativeWebView = this
                        realLoadArmed = true
                        val timeout = Runnable {
                            if (creativeWebView === this && !creativeCommitted && !rendererGone && !bridgeUnavailable) {
                                commitTimeoutBudget.complete()
                                htmlReadiness.terminate()
                                mainFrameLoadFailed = true
                                presentation.clearPrimaryFallback(fallbackOwner)
                                runCatching { visibility = View.INVISIBLE }
                                markBridgeUnavailable()
                            }
                        }
                        creativeCommitTimeout = timeout
                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            clickHandoffHandler.postDelayed(
                                timeout,
                                commitTimeoutBudget.resume(SystemClock.elapsedRealtime()),
                            )
                        }
                        when (val source = creativeSource) {
                            is RewardedCreativeSource.Html -> {
                                // Primary HTML stays opaque and has no remote base-origin state.
                                htmlReadiness.arm()
                                runCatching {
                                    loadDataWithBaseURL(null, source.value, "text/html", "UTF-8", null)
                                }.onFailure { markBridgeUnavailable() }
                            }
                            null -> markBridgeUnavailable()
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
                commitTimeoutBudget.complete()
                htmlReadiness.terminate()
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
        if (videoPreFirstFrameEscapeAvailable(isVideo, displayAdmitted)) {
            VideoPreFirstFrameEscapeButton("Close ad without reward", onPreFirstFrameEscape)
        } else {
            AdCloseButton(
                treatment = close.treatment,
                position = close.position,
                action = close.action,
                progressBarColor = close.progressBarColor,
                isRewardCopy = true,
                enabled = canDismissFullscreen(
                    rewardEarned,
                    clickHandoffPending,
                    rewardedDismissalDisplayAdmitted(displayAdmitted, presentation.displayedReported),
                    storeVisitBlocked,
                ),
                remaining = secondsLeft,
                progress = if (isVideo) smoothVideoCloseProgress else closeProgress.value,
                progressBarStyle = videoProgressBarStyle,
                videoProgress = videoPlaybackProgress,
                gateFraction = twoToneGateFraction(gateSeconds, presentation.videoDurationMs),
                onClose = {
                    if (canDismissFullscreen(
                            rewardEarned,
                            clickHandoffPending,
                            rewardedDismissalDisplayAdmitted(displayAdmitted, presentation.displayedReported),
                            storeVisitBlocked,
                        )
                    ) {
                        val closeClaimed = primaryCreativeCloseAllowed(
                            presentation.videoContract2,
                            presentation.creative.type,
                            videoTerminal,
                        ) { presentation.fallbackState.videoPlan.closeCurrent(VideoLifecycleReason.USER, hasNextStep()) }
                        if (closeClaimed) {
                            presentation.automaticNavigationGate.clear()
                            onFinish(true)
                        }
                    }
                },
            )
        }

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
                        { presentation.callbacks.notifyClicked(it) },
                    ) ?: return@StorePromptBadge
                    val interaction = claim.interaction
                    val routeStartedAtNanos = System.nanoTime()
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
                            prepareDeferredCtaRoute(
                                prepare = {
                                    CreativeCtaRouter.prepare(
                                        presentation.trackingUrl,
                                        presentation.destination,
                                        presentation.androidStoreUrl,
                                        routeStartedAtNanos,
                                    )
                                },
                                requestRoute = presentation::routeClick,
                                completion = completion,
                                open = { routeActivity, prepared ->
                                    if (!canRouteFromCurrentFullscreenActivity(
                                            routeActivity.isFinishing,
                                            routeActivity.isDestroyed,
                                        )
                                    ) return@prepareDeferredCtaRoute false
                                    val outcome = CreativeCtaRouter.launchPrepared(routeActivity, prepared)
                                    if (outcome == AutomaticNavigationOutcome.STORE_OPENED) {
                                        routeActivity.recordClickStoreOpen(committedInteraction.source)
                                    }
                                    outcome != AutomaticNavigationOutcome.FAILED
                                },
                            )
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
            closeAtBottomLeft = close.position == ClosePosition.BOTTOM_LEFT && !progressBarAtBottom(
                close.treatment,
                close.position,
                videoProgressBarStyle,
            ),
        )
    }
}

/** 0→1 close-treatment fill from play-to-earn progress (foreground play time / required duration). */
private fun rewardCloseProgress(playMs: Long, durationSeconds: Int): Float =
    if (durationSeconds > 0) (playMs.toFloat() / (durationSeconds * 1000f)).coerceIn(0f, 1f) else 1f
