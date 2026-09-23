package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.bridge.recordRenderProcessGone
import ad.simula.ad.sdk.bridge.BridgeWebViewInstaller
import ad.simula.ad.sdk.bridge.BridgeInjectionMode
import ad.simula.ad.sdk.bridge.androidCreativeBridge
import ad.simula.ad.sdk.bridge.TrustedCtaOpen
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.minigame.repaintOnNextFrame
import ad.simula.ad.sdk.model.AutoStoreRedirect
import ad.simula.ad.sdk.model.CloseAction
import ad.simula.ad.sdk.model.CloseBehavior
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.CloseTreatment
import ad.simula.ad.sdk.model.CreativeType
import ad.simula.ad.sdk.model.resolveFallbackCloseAction
import ad.simula.ad.sdk.model.endScreenTriggerForIndex
import ad.simula.ad.sdk.model.videoCloseGateMs
import ad.simula.ad.sdk.model.admittedVideoUrl
import ad.simula.ad.sdk.model.RenderAttemptGate
import ad.simula.ad.sdk.model.VideoChromeStyle
import ad.simula.ad.sdk.model.VideoLifecycleReason
import ad.simula.ad.sdk.network.AutoRedirectCoordinator
import ad.simula.ad.sdk.network.AutoRedirectResult
import ad.simula.ad.sdk.network.AdBeaconManager
import ad.simula.ad.sdk.network.BeaconPersistenceOutcome
import ad.simula.ad.sdk.network.ClickRouteStart
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.network.SimulaUserAgent
import ad.simula.ad.sdk.network.ClickInteraction
import ad.simula.ad.sdk.network.ClickInteractionClaim
import ad.simula.ad.sdk.network.ClickInteractionGate
import ad.simula.ad.sdk.network.ClickPersistenceHandoff
import ad.simula.ad.sdk.network.ClickSources
import ad.simula.ad.sdk.network.PresentationRouteResult
import android.content.Context
import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.View
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import java.lang.ref.WeakReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

internal enum class FallbackStage { CONTENT, FETCHING, SHOWING, DONE }
private const val FALLBACK_FETCH_ATTEMPTS = 2
private const val FALLBACK_FETCH_RETRY_MS = 250L
internal const val FALLBACK_POST_CLOSE_WAIT_MS = 2_000L
internal const val FALLBACK_CLOSE_GATE_MS = 5_000L
internal const val FALLBACK_RENDER_TIMEOUT_MS = 10_000L
internal const val FALLBACK_SCREEN_MAX = 2

internal enum class FallbackHtmlFailureAction { FAIL_BLANK, IGNORE }

internal fun fallbackHtmlFailureAction(
    pageCommitted: Boolean,
    isMainFrame: Boolean,
): FallbackHtmlFailureAction = if (isMainFrame && !pageCommitted) {
    FallbackHtmlFailureAction.FAIL_BLANK
} else {
    FallbackHtmlFailureAction.IGNORE
}

internal fun fallbackFailureAutoAdvances(type: CreativeType): Boolean = type == CreativeType.VIDEO

internal fun fallbackPreparationWindow(
    ads: List<SimulaApiClient.FallbackAd>,
    currentIndex: Int,
): List<SimulaApiClient.FallbackAd> = ads.drop(currentIndex.coerceAtLeast(0)).take(2)

internal enum class VideoOverlayCloseOrigin { USER, AUTOMATIC }

internal enum class FallbackUnavailableExitCause { USER_ESCAPE, RENDERER_FAILURE }

internal fun fallbackUnavailableExitReportsAuthority(
    cause: FallbackUnavailableExitCause,
    rendererClaimAccepted: Boolean,
): Boolean = cause == FallbackUnavailableExitCause.RENDERER_FAILURE && rendererClaimAccepted

internal fun videoOverlayCloseAllowed(
    origin: VideoOverlayCloseOrigin,
    videoPlanV2: Boolean,
    videoTerminal: Boolean,
    claimUserClose: () -> Boolean,
): Boolean = origin == VideoOverlayCloseOrigin.AUTOMATIC ||
    !videoPlanV2 || videoTerminal || claimUserClose()

internal fun fallbackCloseGateUsesPresentedTime(type: CreativeType): Boolean = type != CreativeType.VIDEO

internal fun fallbackScreenIsAuthoritative(index: Int, total: Int): Boolean =
    total > 0 && index == total - 1

internal fun fallbackReachedAuthoritativeGate(
    isVideo: Boolean,
    renderAdmitted: Boolean,
    countdown: Int,
    videoTerminal: Boolean,
): Boolean = if (isVideo) {
    videoTerminal || (renderAdmitted && countdown <= 0)
} else {
    renderAdmitted && countdown <= 0
}

internal fun fallbackPlayableGateCanAccrue(
    renderAdmitted: Boolean,
    foreground: Boolean,
    clickHandoffPending: Boolean,
    storeVisitPending: Boolean,
): Boolean = renderAdmitted && foreground && !clickHandoffPending && !storeVisitPending

internal fun shouldEnterFallbackVideoUnavailable(
    type: CreativeType,
    url: String?,
    alreadyUnavailable: Boolean,
): Boolean = type == CreativeType.VIDEO && admittedVideoUrl(url) == null && !alreadyUnavailable

internal data class FallbackVideoRouting(
    val route: ad.simula.ad.sdk.network.PrimaryCtaRoute,
    val destination: String,
    val storeUrl: String?,
    val inheritedFromPrimary: Boolean,
)

internal fun resolveFallbackVideoRouting(
    ad: SimulaApiClient.FallbackAd,
    parentTrackingUrl: String?,
    parentDestination: String,
    parentStoreUrl: String?,
    allowParentFallback: Boolean,
): FallbackVideoRouting? {
    val hasItemRouting = ad.routingFieldsPresent
    val inherited = !hasItemRouting && allowParentFallback
    if (!hasItemRouting && !inherited) return null
    val destination = if (hasItemRouting) ad.destination ?: "appstore" else parentDestination
    if (hasItemRouting && destination !in setOf("appstore", "web")) return null
    val trackingUrl = if (hasItemRouting) {
        ad.trackingUrl?.let(CreativeCtaRouter::admittedHttpUrl)
    } else parentTrackingUrl
    val storeUrl = if (hasItemRouting) {
        ad.androidStoreUrl
            ?.takeIf { destination == "appstore" }
            ?.let(CreativeCtaRouter::admittedDirectPlayStoreUrl)
    } else parentStoreUrl
    val route = videoCtaRoute(trackingUrl, storeUrl, destination) ?: return null
    return FallbackVideoRouting(route, destination, storeUrl, inherited)
}

internal fun closeGateProgress(elapsedMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 1f else (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)

internal fun closeGateSecondsRemaining(elapsedMs: Long, durationMs: Long): Int =
    ceil((durationMs - elapsedMs).coerceAtLeast(0L) / 1000.0).toInt()

internal class FallbackCloseGateState {
    private val elapsedByIndex = LinkedHashMap<Int, Long>()
    private val videoDurationByIndex = LinkedHashMap<Int, Long>()

    @Synchronized
    fun elapsedMs(index: Int): Long = elapsedByIndex[index.coerceAtLeast(0)] ?: 0L

    @Synchronized
    fun addElapsedMs(index: Int, elapsedMs: Long, durationMs: Long): Long {
        val key = index.coerceAtLeast(0)
        val total = durationMs.coerceAtLeast(0L)
        val current = (elapsedByIndex[key] ?: 0L).coerceAtMost(total)
        val updated = current + elapsedMs.coerceIn(0L, total - current)
        elapsedByIndex[key] = updated
        return updated
    }

    fun addPlayableElapsedMs(
        index: Int,
        elapsedMs: Long,
        durationMs: Long,
        renderAdmitted: Boolean,
        foreground: Boolean,
        clickHandoffPending: Boolean,
        storeVisitPending: Boolean,
    ): Long {
        if (!fallbackPlayableGateCanAccrue(
                renderAdmitted,
                foreground,
                clickHandoffPending,
                storeVisitPending,
            )
        ) return this.elapsedMs(index)
        return addElapsedMs(index, elapsedMs, durationMs)
    }

    @Synchronized
    fun videoDurationMs(index: Int): Long = videoDurationByIndex[index.coerceAtLeast(0)] ?: 0L

    @Synchronized
    fun retainVideoDurationMs(index: Int, durationMs: Long) {
        if (durationMs > 0L) videoDurationByIndex[index.coerceAtLeast(0)] = durationMs
    }

    @Synchronized
    fun clear() {
        elapsedByIndex.clear()
        videoDurationByIndex.clear()
    }
}

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

internal class FallbackPresentationState(
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
    videoPlanV2: Boolean = false,
    videoPlanState: VideoPlanPresentationState? = null,
) {
    val videoPlan = videoPlanState ?: VideoPlanPresentationState(videoPlanV2, clockMs)
    var stage: FallbackStage = FallbackStage.CONTENT
        private set
    var index: Int = 0
        private set
    var clickHandoffPending by mutableStateOf(false)
        private set
    var fetchedAds: List<SimulaApiClient.FallbackAd>? = null
        private set
    var primaryEndReached by mutableStateOf(false)
        private set
    private var navigationOwner: Any? = null
    private var navigateInWebView: ((String) -> Boolean)? = null
    private var pendingNavigationUrl: String? = null
    private var activeDelivery: NavigationDelivery? = null
    private var deliveryRevision = 0L
    private var cleared = false
    private var fetchWaitGeneration = 0L
    private var fetchWaitDeadlineMs = 0L
    private var fetchWaitTargetIndex = 0
    private var fallbackResolutionFailed = false
    private var authoritativeEndClaimed = false
    private val closeGateState = FallbackCloseGateState()
    private val automaticNavigationGates = LinkedHashMap<Int, AutomaticNavigationGate>()
    private val rendererAbandonedIndices = mutableSetOf<Int>()
    private val videoLeases = LinkedHashMap<Int, VideoAssetLease>()
    private var serverCandidates: List<SimulaApiClient.FallbackAd> = emptyList()
    private val pendingVideoIndices = mutableSetOf<Int>()
    private val unavailableVideoIndices = mutableSetOf<Int>()
    private val videoPreparationCancellations = LinkedHashMap<Int, () -> Unit>()

    fun showing(index: Int) {
        val nextIndex = index.coerceAtLeast(0)
        if (stage == FallbackStage.SHOWING && this.index != nextIndex) {
            cancelNavigationLocked()
            automaticNavigationGates.clear()
        }
        stage = FallbackStage.SHOWING
        this.index = nextIndex
    }
    fun done() {
        cancelNavigationLocked()
        stage = FallbackStage.DONE
        automaticNavigationGates.clear()
    }
    fun setClickPending(pending: Boolean) {
        synchronized(this) {
            if (cleared) return
            clickHandoffPending = pending
        }
        if (!pending) dispatchReadyNavigation()
    }
    fun retainFetchedAds(ads: List<SimulaApiClient.FallbackAd>) {
        fetchedAds = ads
        fallbackResolutionFailed = false
    }

    @Synchronized
    fun retainServerCandidates(ads: List<SimulaApiClient.FallbackAd>) {
        serverCandidates = ads
        pendingVideoIndices.clear()
        unavailableVideoIndices.clear()
        ads.filter { it.type == CreativeType.VIDEO }.forEach { ad ->
            if (admittedVideoUrl(ad.url) == null) unavailableVideoIndices += ad.sourceIndex
            else if (!videoLeases.containsKey(ad.sourceIndex)) pendingVideoIndices += ad.sourceIndex
        }
    }

    @Synchronized
    fun registerVideoPreparation(sourceIndex: Int, cancel: () -> Unit): Boolean {
        if (sourceIndex !in pendingVideoIndices || cleared) return false
        videoPreparationCancellations.put(sourceIndex, cancel)?.invoke()
        return true
    }

    @Synchronized
    fun settleVideoPreparation(sourceIndex: Int, lease: VideoAssetLease?) {
        videoPreparationCancellations.remove(sourceIndex)
        if (sourceIndex !in pendingVideoIndices || cleared) {
            lease?.release()
            return
        }
        pendingVideoIndices.remove(sourceIndex)
        if (lease == null) {
            unavailableVideoIndices += sourceIndex
        } else {
            videoLeases.put(sourceIndex, lease)?.release()
        }
    }

    @Synchronized
    fun markPendingVideosUnavailable() {
        val pending = pendingVideoIndices.toList()
        pendingVideoIndices.clear()
        unavailableVideoIndices += pending
        pending.forEach { videoPreparationCancellations.remove(it)?.invoke() }
    }

    @Synchronized
    fun hasPendingVideoPreparation(): Boolean = pendingVideoIndices.isNotEmpty()

    @Synchronized
    fun displayablePreparedAds(): List<SimulaApiClient.FallbackAd> {
        val displayable = ArrayList<SimulaApiClient.FallbackAd>(serverCandidates.size)
        serverCandidates.forEach { ad ->
            if (ad.type != CreativeType.VIDEO) {
                displayable += ad
            } else when {
                videoLeases.containsKey(ad.sourceIndex) -> displayable += ad
                ad.sourceIndex in pendingVideoIndices -> return displayable
                ad.sourceIndex in unavailableVideoIndices -> Unit
                else -> Unit
            }
        }
        return displayable
    }
    fun videoFile(sourceIndex: Int): java.io.File? = videoLeases[sourceIndex]?.file
    fun notifyFirstResolvedStepReady(ads: List<SimulaApiClient.FallbackAd>) {
        if (ads.firstOrNull()?.type == CreativeType.PLAYABLE) videoPlan.nextStepReady()
    }
    fun markPrimaryEndReached() {
        primaryEndReached = true
    }
    fun fetchFailed() = Unit
    fun terminalizeInitialFetchFailure(): List<SimulaApiClient.FallbackAd> {
        val retained = fetchedAds
        if (retained != null) return retained
        fallbackResolutionFailed = true
        return emptyList<SimulaApiClient.FallbackAd>().also { fetchedAds = it }
    }
    @Synchronized
    internal fun fallbackResolutionReasonOverride(): VideoLifecycleReason? =
        VideoLifecycleReason.NEXT_STEP_FAILED.takeIf {
            fallbackResolutionFailed || unavailableVideoIndices.isNotEmpty()
        }

    @Synchronized
    fun claimAuthoritativeEnd(): Boolean {
        if (authoritativeEndClaimed) return false
        authoritativeEndClaimed = true
        return true
    }

    fun reportAuthoritativeEnd(report: () -> Unit): Boolean {
        if (!claimAuthoritativeEnd()) return false
        runCatching(report)
        return true
    }

    fun closePendingAfterFallbackResolution() {
        videoPlan.closePendingHandoff(fallbackResolutionReasonOverride())
    }
    fun retainAutomaticNavigation(
        index: Int,
        owner: Any,
        targetUrl: String,
        trackerAlreadyRequested: Boolean,
    ): Boolean {
        if (stage != FallbackStage.SHOWING || this.index != index || navigationOwner !== owner) return false
        return automaticNavigationGate(index).retain(targetUrl, trackerAlreadyRequested)
    }

    fun attemptAutomaticNavigation(
        index: Int,
        open: (PendingAutomaticNavigation) -> AutomaticNavigationOutcome,
    ): AutomaticNavigationOutcome {
        if (stage != FallbackStage.SHOWING || this.index != index) return AutomaticNavigationOutcome.FAILED
        return automaticNavigationGate(index).attemptPending(open)
    }

    fun beginAutomaticNavigation(index: Int): AutomaticNavigationAttempt? {
        if (stage != FallbackStage.SHOWING || this.index != index) return null
        return automaticNavigationGate(index).beginPending()
    }

    fun isAutomaticNavigationActive(index: Int, attempt: AutomaticNavigationAttempt): Boolean =
        stage == FallbackStage.SHOWING && this.index == index &&
            automaticNavigationGate(index).isActive(attempt)

    fun completeAutomaticNavigation(
        index: Int,
        attempt: AutomaticNavigationAttempt,
        outcome: AutomaticNavigationOutcome,
    ): Boolean = automaticNavigationGate(index).complete(attempt, outcome)

    fun abandonAutomaticNavigation(index: Int) {
        automaticNavigationGates[index.coerceAtLeast(0)]?.abandonInFlight()
    }

    fun suppressAutomaticNavigation(index: Int) {
        automaticNavigationGates[index.coerceAtLeast(0)]?.suppressPending()
    }

    @Synchronized
    fun abandonRenderer(index: Int, owner: Any): Boolean {
        val normalizedIndex = index.coerceAtLeast(0)
        if (cleared || stage != FallbackStage.SHOWING || this.index != index ||
            normalizedIndex in rendererAbandonedIndices
        ) {
            return false
        }
        val currentOwner = navigationOwner
        if (currentOwner != null && currentOwner !== owner) return false
        automaticNavigationGates[normalizedIndex]?.clear()
        cancelNavigationLocked()
        rendererAbandonedIndices += normalizedIndex
        return true
    }

    @Synchronized
    fun isRendererAbandoned(index: Int): Boolean = index.coerceAtLeast(0) in rendererAbandonedIndices

    fun markAutomaticTrackerRequested(index: Int) {
        if (stage != FallbackStage.SHOWING || this.index != index) return
        automaticNavigationGate(index).markTrackerRequestedInWebView()
    }

    fun wasAutomaticTrackerRequested(index: Int): Boolean =
        stage == FallbackStage.SHOWING && this.index == index &&
            automaticNavigationGate(index).wasTrackerRequestedInWebView()

    private fun automaticNavigationGate(index: Int): AutomaticNavigationGate =
        automaticNavigationGates.getOrPut(index.coerceAtLeast(0)) { AutomaticNavigationGate() }

    fun closeGateElapsedMs(index: Int): Long = closeGateState.elapsedMs(index)

    fun addCloseGateElapsedMs(
        index: Int,
        elapsedMs: Long,
        durationMs: Long = FALLBACK_CLOSE_GATE_MS,
    ): Long = closeGateState.addElapsedMs(index, elapsedMs, durationMs)

    fun addPlayableCloseGateElapsedMs(
        index: Int,
        elapsedMs: Long,
        durationMs: Long,
        renderAdmitted: Boolean,
        foreground: Boolean,
        clickHandoffPending: Boolean,
        storeVisitPending: Boolean,
    ): Long = closeGateState.addPlayableElapsedMs(
        index,
        elapsedMs,
        durationMs,
        renderAdmitted,
        foreground,
        clickHandoffPending,
        storeVisitPending,
    )

    fun videoDurationMs(index: Int): Long = closeGateState.videoDurationMs(index)

    fun retainVideoDurationMs(index: Int, durationMs: Long) =
        closeGateState.retainVideoDurationMs(index, durationMs)

    fun startPostCloseFetchWait(targetIndex: Int = 0): Long {
        if (stage != FallbackStage.FETCHING) {
            if (stage == FallbackStage.SHOWING) cancelNavigationLocked()
            fetchWaitGeneration++
            fetchWaitDeadlineMs = clockMs() + FALLBACK_POST_CLOSE_WAIT_MS
            fetchWaitTargetIndex = targetIndex.coerceAtLeast(0)
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
        if (ads.getOrNull(fetchWaitTargetIndex) != null) {
            showing(fetchWaitTargetIndex)
        } else if (hasPendingVideoPreparation()) {
            return false
        } else {
            done()
        }
        notifyFirstResolvedStepReady(ads)
        return true
    }

    fun timeoutPostCloseFetchWait(
        generation: Long,
        ads: List<SimulaApiClient.FallbackAd> = emptyList(),
    ): Boolean {
        if (stage != FallbackStage.FETCHING || generation != fetchWaitGeneration) return false
        markPendingVideosUnavailable()
        if (ads.getOrNull(fetchWaitTargetIndex) != null) {
            showing(fetchWaitTargetIndex)
            notifyFirstResolvedStepReady(ads.drop(fetchWaitTargetIndex))
        } else {
            done()
            videoPlan.closePendingHandoff(VideoLifecycleReason.NEXT_STEP_TIMEOUT)
        }
        return true
    }

    fun bindNavigation(owner: Any, navigate: (String) -> Boolean) {
        synchronized(this) {
            if (cleared) return
            claimNavigationOwnerLocked(owner)
            navigateInWebView = navigate
        }
        dispatchReadyNavigation()
    }

    @Synchronized
    fun claimNavigationOwner(owner: Any) {
        if (cleared) return
        claimNavigationOwnerLocked(owner)
    }

    @Synchronized
    fun unbindNavigation(owner: Any) {
        if (navigationOwner === owner) {
            clearBindingLocked()
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
        cancelNavigationLocked()
        closeGateState.clear()
        automaticNavigationGates.clear()
        rendererAbandonedIndices.clear()
        videoPreparationCancellations.values.forEach { runCatching(it) }
        videoPreparationCancellations.clear()
        pendingVideoIndices.clear()
        videoLeases.values.forEach(VideoAssetLease::release)
        videoLeases.clear()
    }

    private fun dispatchReadyNavigation() {
        val delivery = synchronized(this) {
            if (cleared || clickHandoffPending || activeDelivery != null) return
            val navigate = navigateInWebView ?: return
            val url = pendingNavigationUrl ?: return
            val owner = navigationOwner ?: return
            NavigationDelivery(++deliveryRevision, owner, url).also { activeDelivery = it } to navigate
        }
        val delivered = runCatching { delivery.second(delivery.first.url) }.getOrDefault(false)
        synchronized(this) {
            if (activeDelivery !== delivery.first) return@synchronized
            if (delivered && pendingNavigationUrl == delivery.first.url) {
                pendingNavigationUrl = null
            }
            if (!delivered || delivery.first.permitConsumed) activeDelivery = null
        }
    }

    @Synchronized
    fun navigationOverride(
        targetUrl: String? = null,
        isMainFrame: Boolean = true,
        hasGesture: Boolean = false,
        owner: Any? = navigationOwner,
    ): Boolean? {
        if (cleared) return true
        if (clickHandoffPending) return true
        activeDelivery?.let { delivery ->
            if (delivery.owner === owner && isMainFrame && !hasGesture &&
                targetUrl == delivery.url && !delivery.permitConsumed
            ) {
                delivery.permitConsumed = true
                if (pendingNavigationUrl == null) activeDelivery = null
                return false
            }
            return true
        }
        return if (pendingNavigationUrl != null) true else null
    }

    @Synchronized
    fun onNavigationStarted(url: String?, owner: Any? = navigationOwner) {
        val delivery = activeDelivery ?: return
        if (delivery.owner !== owner || url != delivery.url) return
        delivery.permitConsumed = true
        if (pendingNavigationUrl == null) activeDelivery = null
    }

    @Synchronized
    fun advance(total: Int): Boolean {
        if (clickHandoffPending || stage != FallbackStage.SHOWING) return false
        cancelNavigationLocked()
        if (index + 1 < total) showing(index + 1) else done()
        return true
    }

    private fun cancelNavigationLocked() {
        pendingNavigationUrl = null
        activeDelivery = null
        deliveryRevision++
        navigationOwner = null
        navigateInWebView = null
    }

    private fun clearBindingLocked() {
        activeDelivery?.takeIf { it.owner === navigationOwner }?.let { delivery ->
            if (pendingNavigationUrl == null) pendingNavigationUrl = delivery.url
            activeDelivery = null
            deliveryRevision++
        }
        navigationOwner = null
        navigateInWebView = null
    }

    private fun claimNavigationOwnerLocked(owner: Any) {
        if (navigationOwner !== owner) clearBindingLocked()
        navigationOwner = owner
    }

    private data class NavigationDelivery(
        val revision: Long,
        val owner: Any,
        val url: String,
        var permitConsumed: Boolean = false,
    )
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
    adUnitId: String? = null,
    presentationState: FallbackPresentationState = remember { FallbackPresentationState() },
    onFullyClosed: () -> Unit,
    onAuthoritativeEndReached: () -> Unit = {},
    autoStoreRedirect: AutoStoreRedirect? = null,
    onAutoStoreRedirect: (() -> Boolean, (Boolean) -> Unit, ((() -> Unit) -> Unit)) -> Unit =
        { _, completion, _ -> completion(false) },
    openAutomaticNavigation: (
        String,
        Boolean,
        () -> Boolean,
        (AutomaticNavigationOutcome) -> Unit,
        ((() -> Unit) -> Unit),
    ) -> Unit = { _, _, _, completion, _ ->
        completion(AutomaticNavigationOutcome.FAILED)
    },
    onAdClick: (ClickInteraction) -> Unit = {},
    onStoreOpen: (ClickInteraction) -> Unit = {},
    persistClick: (String, ClickInteraction, () -> Unit) -> Unit = { _, _, complete -> complete() },
    claimClick: ((String) -> ClickInteractionClaim?)? = null,
    claimTrustedClick: ((String, String) -> ClickInteractionClaim?)? = null,
    claimWebClick: ((String) -> ClickInteractionClaim?)? = null,
    routeClick: (((Context) -> Boolean, (Boolean) -> Unit) -> ClickRouteStart)? = null,
    onClickHandoffCreated: (ClickPersistenceHandoff) -> Unit = {},
    onClickHandoffFinished: (ClickPersistenceHandoff) -> Unit = {},
    autoRedirectCoordinator: AutoRedirectCoordinator? = null,
    pendingClickHandoff: () -> ClickPersistenceHandoff? = { null },
    storeVisitPending: () -> Boolean = { false },
    // The primary serve's CTA routing context, threaded into each end screen so its CTA opens
    // through the shared router (direct resolved Play URL, raw store link as deterministic fallback).
    // Defaults preserve today's behavior when no context is available.
    ctaTrackingUrl: String? = null,
    ctaDestination: String = "appstore",
    ctaStoreUrl: String? = null,
    videoPlanV2: Boolean = false,
    content: @Composable (onClose: () -> Unit, nextVideoUrl: String?, hasNextStep: () -> Boolean) -> Unit,
) {
    val canonicalAdFormat = canonicalFullscreenAdFormat(adFormat)
    val context = LocalContext.current
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
    val clickClaim = claimClick ?: { source: String -> fallbackClickGate.claim(source) }
    val webClickClaim = claimWebClick ?: { source: String -> fallbackClickGate.claimWeb(source) }
    // Fullscreen presentations pass their shared coordinator; the local instance is only for the
    // standalone/default host and must not replace presentation state across end-screen indices.
    val localAutoRedirectCoordinator = remember(impressionId) { AutoRedirectCoordinator() }
    val redirects = autoRedirectCoordinator ?: localAutoRedirectCoordinator
    VideoPlanOverlayClockEffect(presentationState.videoPlan) {
        presentationState.clickHandoffPending || pendingClickHandoff() != null || storeVisitPending()
    }
    DisposableEffect(localAutoRedirectCoordinator, autoRedirectCoordinator) {
        onDispose {
            if (autoRedirectCoordinator == null) localAutoRedirectCoordinator.dispose()
        }
    }
    fun reportAuthoritativeEnd() {
        presentationState.reportAuthoritativeEnd(onAuthoritativeEndReached)
    }
    // auto_store_redirect END_SCREEN_N: open the primary ad's store once, when the fallback screen
    // whose index matches the configured trigger is presented (index 0 = END SCREEN 1, index 1 = 2).
    // Scope replacement drops deferred callbacks from an end screen that has already closed.

    // Prefetch the fallback screens in the background while the primary creative is on screen, so
    // they present instantly on close instead of fetching then (which flashed the host behind).
    // `GET /load/fallbacks` is side-effect-free, so prefetching reports nothing prematurely.
    // null = still in flight; empty = none returned.
    var prefetched by remember(presentationState) { mutableStateOf(presentationState.fetchedAds) }
    LaunchedEffect(prefetched, presentationState.primaryEndReached) {
        if (presentationState.primaryEndReached && prefetched?.isEmpty() == true) {
            reportAuthoritativeEnd()
        }
    }
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
            val accepted = fetched?.take(FALLBACK_SCREEN_MAX)
            if (accepted == null) {
                prefetched = presentationState.terminalizeInitialFetchFailure()
                return@LaunchedEffect
            }
            presentationState.retainServerCandidates(accepted)
            fun publishPreparedState() {
                val resolved = presentationState.displayablePreparedAds()
                if (resolved.isNotEmpty() || !presentationState.hasPendingVideoPreparation()) {
                    presentationState.retainFetchedAds(resolved)
                    prefetched = resolved
                }
            }
            publishPreparedState()
            fallbackPreparationWindow(accepted, currentIndex = 0)
                .filter { it.type == CreativeType.VIDEO && admittedVideoUrl(it.url) != null }
                .forEach { ad ->
                    val job = launch {
                        val lease = when (val result = VideoAssetCache.acquire(context.applicationContext, ad.url)) {
                            is VideoAssetCacheResult.Ready -> result.lease
                            is VideoAssetCacheResult.Failed -> null
                        }
                        presentationState.settleVideoPreparation(ad.sourceIndex, lease)
                        publishPreparedState()
                    }
                    if (!presentationState.registerVideoPreparation(ad.sourceIndex, job::cancel)) {
                        job.cancel()
                    }
                }
        }
    }

    // Primary creative closed → present the prefetched screens immediately. If the prefetch is
    // somehow still in flight (user closed very fast), wait briefly in [FallbackPhase.Fetching].
    fun onPrimaryClosed() {
        val ads = prefetched
        phase = when {
            ads == null -> FallbackPhase.Fetching(presentationState.startPostCloseFetchWait())
            ads.isNotEmpty() -> FallbackPhase.Showing(ads, index = 0).also { presentationState.showing(0) }
            else -> FallbackPhase.Done.also {
                presentationState.closePendingAfterFallbackResolution()
                presentationState.done()
            }
        }
        ads?.let(presentationState::notifyFirstResolvedStepReady)
    }

    // This root survives every phase, including Done's final callback frame, so no transition can
    // expose the Activity window or host beneath it.
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val p = phase) {
            FallbackPhase.Content -> content(
                { onPrimaryClosed() },
                null,
                {
                    presentationState.markPrimaryEndReached()
                    false
                },
            )
            // Prefetch wasn't ready at close — hold on the black backdrop and advance when it lands.
            is FallbackPhase.Fetching -> {
                // Swallow back during this brief settle window so a fast back-press can't finish the
                // Activity before the end screens are revealed (parity with the gated close).
                BackHandler(enabled = true) {}
                LaunchedEffect(p.generation) {
                    val remainingMs = presentationState.postCloseFetchWaitRemainingMs(p.generation)
                        ?: return@LaunchedEffect
                    if (remainingMs > 0L) delay(remainingMs)
                    presentationState.markPendingVideosUnavailable()
                    val resolved = presentationState.displayablePreparedAds()
                    presentationState.retainFetchedAds(resolved)
                    prefetched = resolved
                    if (presentationState.timeoutPostCloseFetchWait(p.generation, resolved)) {
                        phase = if (presentationState.stage == FallbackStage.SHOWING) {
                            FallbackPhase.Showing(resolved, presentationState.index)
                        } else {
                            reportAuthoritativeEnd()
                            FallbackPhase.Done
                        }
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
                        presentationState.closePendingAfterFallbackResolution()
                        reportAuthoritativeEnd()
                        FallbackPhase.Done
                    }
                }
            }
            is FallbackPhase.Showing -> {
                val ad = p.ads[p.index]
                val screenIndex = ad.sourceIndex
                val autoRedirectScope = remember(impressionId, screenIndex) { Any() }
                fun dispatchAutomaticNavigation() {
                    val result = redirects.requestAsync(
                        scope = autoRedirectScope,
                        pendingHandoff = pendingClickHandoff(),
                    ) { routeCanOpen, completion, registerCancellation ->
                        val attempt = presentationState.beginAutomaticNavigation(p.index)
                        if (attempt == null) {
                            completion(false)
                            return@requestAsync
                        }
                        openAutomaticNavigation(
                            attempt.route.targetUrl,
                            attempt.route.trackerAlreadyRequested,
                            {
                                routeCanOpen() && redirects.isActive(autoRedirectScope) &&
                                    presentationState.isAutomaticNavigationActive(p.index, attempt)
                            },
                            { outcome ->
                                presentationState.completeAutomaticNavigation(p.index, attempt, outcome)
                                completion(outcome != AutomaticNavigationOutcome.FAILED)
                            },
                            { cancellation ->
                                registerCancellation {
                                    cancellation()
                                    presentationState.abandonAutomaticNavigation(p.index)
                                }
                            },
                        )
                    }
                    if (result == AutoRedirectResult.SUPPRESSED) {
                        presentationState.suppressAutomaticNavigation(p.index)
                    }
                }
                DisposableEffect(redirects, autoRedirectScope) {
                    redirects.activate(autoRedirectScope)
                    onDispose {
                        presentationState.abandonAutomaticNavigation(p.index)
                        redirects.deactivate(autoRedirectScope)
                    }
                }
                LaunchedEffect(autoRedirectScope) { dispatchAutomaticNavigation() }
                // Fire the auto store redirect when the END_SCREEN_N fallback screen is presented.
                LaunchedEffect(screenIndex) {
                    if (autoStoreRedirect?.enabled == true &&
                        autoStoreRedirect.trigger == endScreenTriggerForIndex(screenIndex)
                    ) {
                        redirects.requestAsync(
                            scope = autoRedirectScope,
                            pendingHandoff = pendingClickHandoff(),
                        ) { routeCanOpen, completion, registerCancellation ->
                            onAutoStoreRedirect(
                                { routeCanOpen() && redirects.isActive(autoRedirectScope) },
                                completion,
                                registerCancellation,
                            )
                        }
                    }
                }
                // key() so each screen gets fresh overlay state (countdown, WebView) — without it the
                // next screen would inherit the previous one's elapsed countdown and loaded page.
                key(screenIndex) {
                    val resolvedClose = ad.closeBehavior.copy(
                        action = resolveFallbackCloseAction(ad.closeBehavior.action, p.index, p.ads.size),
                    )
                    FallbackAdOverlay(
                        ad = ad,
                        onAdClick = onAdClick,
                        onStoreOpen = { interaction ->
                            redirects.recordUserRouteOpened()
                            onStoreOpen(interaction)
                        },
                        persistClick = persistClick,
                        claimClick = clickClaim,
                        claimTrustedClick = claimTrustedClick,
                        claimWebClick = webClickClaim,
                        impressionId = impressionId,
                        adFormat = canonicalAdFormat,
                        adUnitId = adUnitId,
                        routeClick = routeClick,
                        presentationState = presentationState,
                        fallbackIndex = p.index,
                        sourceIndex = ad.sourceIndex,
                        nextVideoUrl = null,
                        hasNextStep = p.index + 1 < p.ads.size,
                        closeBehavior = resolvedClose,
                        onClickHandoffCreated = { handoff ->
                            presentationState.abandonAutomaticNavigation(p.index)
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
                        onAutomaticNavigation = ::dispatchAutomaticNavigation,
                        onRendererUnavailable = { redirects.deactivate(autoRedirectScope) },
                        onAuthoritativeEndReached = {
                            if (fallbackScreenIsAuthoritative(p.index, p.ads.size)) reportAuthoritativeEnd()
                        },
                        storeVisitPending = storeVisitPending,
                        onClose = {
                            val latest = presentationState.displayablePreparedAds()
                            presentationState.retainFetchedAds(latest)
                            val currentIndex = latest.indexOfFirst { it.sourceIndex == ad.sourceIndex }
                                .takeIf { it >= 0 } ?: p.index
                            val nextIndex = currentIndex + 1
                            if (latest.getOrNull(nextIndex) == null &&
                                presentationState.hasPendingVideoPreparation()
                            ) {
                                phase = FallbackPhase.Fetching(
                                    presentationState.startPostCloseFetchWait(nextIndex),
                                )
                                return@FallbackAdOverlay
                            }
                            if (!presentationState.advance(latest.size)) return@FallbackAdOverlay
                            phase = if (presentationState.stage == FallbackStage.SHOWING) {
                                latest.getOrNull(presentationState.index)
                                    ?.takeIf { it.type == CreativeType.PLAYABLE }
                                    ?.let { presentationState.videoPlan.nextStepReady() }
                                FallbackPhase.Showing(latest, presentationState.index)
                            } else {
                                presentationState.videoPlan.closePendingHandoff()
                                reportAuthoritativeEnd()
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
 * Full-screen fallback ad: server-rendered HTML in a pooled WebView or a native video surface,
 * with per-item close chrome and close/forward accessibility semantics.
 */
@Composable
private fun FallbackAdOverlay(
    ad: SimulaApiClient.FallbackAd,
    onAdClick: (ClickInteraction) -> Unit = {},
    onStoreOpen: (ClickInteraction) -> Unit = {},
    persistClick: (String, ClickInteraction, () -> Unit) -> Unit,
    claimClick: (String) -> ClickInteractionClaim?,
    claimTrustedClick: ((String, String) -> ClickInteractionClaim?)?,
    claimWebClick: (String) -> ClickInteractionClaim?,
    impressionId: String,
    adFormat: String,
    adUnitId: String?,
    routeClick: (((Context) -> Boolean, (Boolean) -> Unit) -> ClickRouteStart)?,
    presentationState: FallbackPresentationState,
    fallbackIndex: Int,
    sourceIndex: Int,
    nextVideoUrl: String?,
    hasNextStep: Boolean,
    closeBehavior: CloseBehavior,
    onClickHandoffCreated: (ClickPersistenceHandoff) -> Unit,
    onClickHandoffFinished: (ClickPersistenceHandoff) -> Unit,
    ctaTrackingUrl: String? = null,
    ctaDestination: String = "appstore",
    ctaStoreUrl: String? = null,
    onAutomaticNavigation: () -> Unit,
    onRendererUnavailable: () -> Unit,
    onAuthoritativeEndReached: () -> Unit,
    storeVisitPending: () -> Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val adId = ad.adId
    val nativeClickBeaconV1Enabled = ad.nativeClickBeaconV1Enabled
    val lifecycleOwner = LocalLifecycleOwner.current
    val clickHandler = remember { Handler(Looper.getMainLooper()) }
    val inlineHtml = ad.renderedHtml?.takeIf { it.isNotBlank() }
    val isVideo = ad.type == CreativeType.VIDEO
    val videoUrl = remember(ad.url) { admittedVideoUrl(ad.url) }
    var videoDurationMs by remember(presentationState, fallbackIndex) {
        mutableStateOf(presentationState.videoDurationMs(fallbackIndex))
    }
    var gateMs by remember(presentationState, fallbackIndex) {
        mutableStateOf(videoCloseGateMs(closeBehavior.delaySeconds, videoDurationMs))
    }
    // The pooled fallback WebView, captured from the AndroidView factory below so we can pause/resume
    // it with the host and force a repaint on foreground return — AndroidView won't pause a WebView, and
    // a hardware-accelerated WebView returns black/blank after the window loses visibility (background).
    val retainedRendererAbandonment = presentationState.isRendererAbandoned(fallbackIndex)
    var fallbackWebView by remember { mutableStateOf<WebView?>(null) }
    var rendererGone by remember(presentationState, fallbackIndex) {
        mutableStateOf(retainedRendererAbandonment)
    }
    var renderProcessGone by remember(presentationState, fallbackIndex) { mutableStateOf(false) }
    val navigationOwner = remember(presentationState, fallbackIndex) { Any() }
    val bridge = remember(context) {
        androidCreativeBridge(
            appContext = context.applicationContext,
            activityProvider = { context as? Activity },
            onEarlyComplete = {},
        )
    }
    val renderGate = remember(presentationState, sourceIndex) { RenderAttemptGate() }
    var renderToken by remember(presentationState, sourceIndex) { mutableStateOf(0L) }
    // Keep an opaque layer over the pooled WebView until this creative commits a visible frame.
    var pageCommitted by remember { mutableStateOf(false) }
    var pageLoadFailed by remember { mutableStateOf(false) }
    var videoFirstFrameAdmitted by remember(presentationState, fallbackIndex) { mutableStateOf(false) }
    var videoTerminal by remember(presentationState, fallbackIndex) { mutableStateOf(false) }
    val storeVisitBlocked = storeVisitPending()
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
                Lifecycle.Event.ON_PAUSE -> if (!rendererGone) wv?.onPause()
                Lifecycle.Event.ON_STOP -> wasStopped = true
                Lifecycle.Event.ON_RESUME -> {
                    if (rendererGone) return@LifecycleEventObserver
                    wv?.onResume()
                    onAutomaticNavigation()
                    if (wasStopped) {
                        wasStopped = false
                        wv?.repaintOnNextFrame { !rendererGone }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var rendererOwnsPhase by remember(presentationState, fallbackIndex) {
        mutableStateOf(retainedRendererAbandonment)
    }
    fun applyRendererUnavailable() {
        if (rendererGone) return
        rendererGone = true
        rendererOwnsPhase = presentationState.abandonRenderer(fallbackIndex, navigationOwner)
        runCatching(onRendererUnavailable)
    }
    fun failInitialRenderer(token: Long) {
        if (!renderGate.fail(token)) return
        pageLoadFailed = true
        applyRendererUnavailable()
    }
    fun beginTrustedFallbackCta(request: TrustedCtaOpen) {
        val routePlan = when (val plan = CreativeCtaRouter.fallbackCtaTapPlan(
            isMainFrame = true,
            hasGesture = true,
            tappedUrl = request.url,
            creativeBaseUrl = null,
            trackingUrl = ctaTrackingUrl,
            destination = ctaDestination,
        )) {
            CreativeCtaRouter.PrimaryCtaTapPlan.AllowInWebView,
            CreativeCtaRouter.PrimaryCtaTapPlan.ConsumeWithoutClick,
            -> return
            is CreativeCtaRouter.PrimaryCtaTapPlan.Route -> plan.route
        }
        val fallbackSource = when (sourceIndex) {
            0 -> ClickSources.END_SCREEN_1_UNKNOWN
            1 -> ClickSources.END_SCREEN_2_UNKNOWN
            else -> ClickSources.END_SCREEN_2_UNKNOWN
        }
        val source = ClickSources.trustedHtmlOr(request.clickSource, fallbackSource)
        val claim = request.interactionId?.let { claimTrustedClick?.invoke(it, source) }
            ?: claimWebClick(source) ?: return
        notifyPublisherClick { onAdClick(claim.interaction) }
        val interaction = claim.interaction
        val routeStartedAtNanos = System.nanoTime()
        coordinateDeferredClickPersistence(
            mainHandler = clickHandler,
            claim = claim,
            enqueueBeacon = { completion ->
                enqueueOwnedFallbackClickBeacon(adId, nativeClickBeaconV1Enabled, completion) { beaconId ->
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
            recordTelemetry = { completion -> persistClick(adId, interaction, completion) },
            onHandoff = { committedInteraction, completion ->
                prepareDeferredCtaRoute(
                    prepare = {
                        CreativeCtaRouter.preparePrimaryCta(
                            routePlan,
                            ctaDestination,
                            ctaStoreUrl,
                            routeStartedAtNanos,
                        )
                    },
                    requestRoute = { route, routeCompletion ->
                        val start = routeClick?.invoke(route, routeCompletion)
                        if (start == null) {
                            routeCompletion(route(context))
                            PresentationRouteResult.EXECUTED
                        } else if (start == ClickRouteStart.REJECTED) {
                            PresentationRouteResult.REJECTED
                        } else PresentationRouteResult.EXECUTED
                    },
                    completion = completion,
                    open = { routeContext, prepared ->
                        val outcome = CreativeCtaRouter.launchPrepared(routeContext, prepared)
                        if (outcome == AutomaticNavigationOutcome.STORE_OPENED) {
                            runCatching { onStoreOpen(committedInteraction) }
                        }
                        outcome != AutomaticNavigationOutcome.FAILED &&
                            outcome != AutomaticNavigationOutcome.HANDLED
                    },
                )
            },
            onCreated = onClickHandoffCreated,
            onFinished = onClickHandoffFinished,
        )
    }
    var authoritativeEndReported by remember(presentationState, fallbackIndex) { mutableStateOf(false) }
    fun reportAuthoritativeEnd() {
        if (authoritativeEndReported) return
        authoritativeEndReported = true
        runCatching(onAuthoritativeEndReached)
    }
    var unavailableExitIssued by remember { mutableStateOf(false) }
    fun closeOnce(origin: VideoOverlayCloseOrigin) {
        if (unavailableExitIssued) return
        if (!videoOverlayCloseAllowed(origin, false, videoTerminal) {
                presentationState.videoPlan.closeCurrent(VideoLifecycleReason.USER)
            }
        ) return
        unavailableExitIssued = true
        runCatching(onClose)
    }
    fun finishUnavailableExit(cause: FallbackUnavailableExitCause) {
        if (fallbackUnavailableExitReportsAuthority(cause, rendererOwnsPhase)) {
            reportAuthoritativeEnd()
        }
        closeOnce(
            if (cause == FallbackUnavailableExitCause.USER_ESCAPE) {
                VideoOverlayCloseOrigin.USER
            } else {
                VideoOverlayCloseOrigin.AUTOMATIC
            },
        )
    }
    fun escapePreFirstFrameVideo() {
        if (!videoPreFirstFrameEscapeAvailable(isVideo, videoFirstFrameAdmitted) ||
            presentationState.clickHandoffPending || storeVisitPending()
        ) return
        applyRendererUnavailable()
        finishUnavailableExit(FallbackUnavailableExitCause.USER_ESCAPE)
    }
    LaunchedEffect(isVideo, videoUrl, rendererGone) {
        if (shouldEnterFallbackVideoUnavailable(ad.type, videoUrl, rendererGone)) {
            applyRendererUnavailable()
        }
    }
    LaunchedEffect(rendererGone, rendererOwnsPhase, presentationState.clickHandoffPending, storeVisitBlocked) {
        if (!rendererGone || !rendererOwnsPhase || presentationState.clickHandoffPending) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            withFrameNanos { }
            if (shouldExitUnavailableCreative(
                    creativeUnavailable = rendererGone,
                    clickHandoffPending = presentationState.clickHandoffPending,
                    storeVisitPending = storeVisitPending(),
                ) && !unavailableExitIssued
            ) {
                finishUnavailableExit(FallbackUnavailableExitCause.RENDERER_FAILURE)
            }
        }
    }
    val retainedGateMs = presentationState.closeGateElapsedMs(fallbackIndex).coerceAtMost(gateMs)
    var countdown by remember(presentationState, fallbackIndex) {
        mutableStateOf(closeGateSecondsRemaining(retainedGateMs, gateMs))
    }
    // Ring fills clockwise from the top (right to left), unfilled → filled, over the countdown.
    val ring = remember(presentationState, fallbackIndex) {
        Animatable(closeGateProgress(retainedGateMs, gateMs))
    }
    var videoRingProgress by remember(presentationState, fallbackIndex) {
        mutableFloatStateOf(closeGateProgress(retainedGateMs, gateMs))
    }
    val smoothVideoRingProgress = smoothVideoProgress(videoRingProgress)
    val videoRouting = remember(ad, ctaTrackingUrl, ctaStoreUrl, ctaDestination) {
        resolveFallbackVideoRouting(
            ad = ad,
            parentTrackingUrl = ctaTrackingUrl,
            parentDestination = ctaDestination,
            parentStoreUrl = ctaStoreUrl,
            allowParentFallback = true,
        )
    }
    // Foreground-only per-item gate: time accrues only while the Activity is RESUMED, so leaving the app
    // pauses the countdown (parity with the interstitial / rewarded close gates). repeatOnLifecycle
    // cancels the loop when backgrounded and resumes it from the accrued time on return.
    LaunchedEffect(
        gateMs,
        isVideo,
        pageCommitted,
        presentationState.clickHandoffPending,
        storeVisitBlocked,
    ) {
        if (!fallbackCloseGateUsesPresentedTime(ad.type)) return@LaunchedEffect
        if (!fallbackPlayableGateCanAccrue(
                renderAdmitted = pageCommitted,
                foreground = true,
                clickHandoffPending = presentationState.clickHandoffPending,
                storeVisitPending = storeVisitBlocked,
            )
        ) return@LaunchedEffect
        if (gateMs <= 0L) {
            countdown = 0
            ring.snapTo(1f)
            return@LaunchedEffect
        }
        var accumulatedMs = presentationState.closeGateElapsedMs(fallbackIndex).coerceAtMost(gateMs)
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Re-anchor on each resume so the backgrounded interval is never counted.
            var lastTickMs = SystemClock.elapsedRealtime()
            while (accumulatedMs < gateMs) {
                delay(50L)
                val canAccrue = fallbackPlayableGateCanAccrue(
                    renderAdmitted = pageCommitted,
                    foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                    clickHandoffPending = presentationState.clickHandoffPending,
                    storeVisitPending = storeVisitPending(),
                )
                if (!canAccrue) break
                val now = SystemClock.elapsedRealtime()
                val deltaMs = (now - lastTickMs).coerceIn(0L, gateMs)
                accumulatedMs = presentationState.addPlayableCloseGateElapsedMs(
                    index = fallbackIndex,
                    elapsedMs = deltaMs,
                    durationMs = gateMs,
                    renderAdmitted = pageCommitted,
                    foreground = true,
                    clickHandoffPending = presentationState.clickHandoffPending,
                    storeVisitPending = storeVisitPending(),
                )
                lastTickMs = now
                ring.snapTo(closeGateProgress(accumulatedMs, gateMs))
                countdown = closeGateSecondsRemaining(accumulatedMs, gateMs)
            }
        }
    }
    LaunchedEffect(isVideo, renderToken, pageCommitted, rendererGone) {
        val token = renderToken
        if (isVideo || token == 0L || pageCommitted || rendererGone || !renderGate.isPending(token)) {
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(FALLBACK_RENDER_TIMEOUT_MS)
            failInitialRenderer(token)
        }
    }
    // Back can only close once the countdown elapses (parity with the creative's gated close).
    BackHandler(enabled = true) {
        if (videoPreFirstFrameEscapeAvailable(isVideo, videoFirstFrameAdmitted)) {
            escapePreFirstFrameVideo()
            return@BackHandler
        }
        if ((isVideo || pageCommitted) && countdown <= 0 &&
            !presentationState.clickHandoffPending && !storeVisitBlocked
        ) {
            closeOnce(VideoOverlayCloseOrigin.USER)
        }
    }
    LaunchedEffect(countdown, isVideo, pageCommitted, videoFirstFrameAdmitted, videoTerminal) {
        val renderAdmitted = if (isVideo) videoFirstFrameAdmitted else pageCommitted
        if (fallbackReachedAuthoritativeGate(isVideo, renderAdmitted, countdown, videoTerminal)) {
            reportAuthoritativeEnd()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (isVideo && !rendererGone) {
            presentationState.videoFile(sourceIndex)?.let { videoFile ->
                FullscreenVideo(
                    file = videoFile,
                    posterUrl = ad.posterUrl,
                    adFormat = adFormat,
                    adUnitId = adUnitId,
                    adId = adId.takeIf { it.isNotBlank() },
                    serveId = impressionId.takeIf { it.isNotBlank() },
                    configuredGateSeconds = closeBehavior.delaySeconds,
                    initialPlayedMs = presentationState.closeGateElapsedMs(fallbackIndex),
                    ctaEnabled = videoRouting != null,
                    ctaLabel = ad.cta,
                    appIconUrl = ad.appIconUrl,
                    appName = ad.appName,
                    subtitle = ad.subtitle,
                    chromeStyle = ad.videoBehavior?.style ?: VideoChromeStyle.CORNER_CTA,
                    effectiveClosePosition = closeBehavior.position,
                    bottomProgressBarObstructed = false,
                    videoPool = ad.videoPool,
                    playbackSlotIdentity = VideoPlaybackSlotIdentity.Fallback(ad.sourceIndex),
                    clipIndex = ad.clipIndex,
                    skoverlayEnabled = ad.skoverlay?.enabled,
                    skoverlayDelaySeconds = ad.skoverlay?.delaySeconds,
                    videoPlanV2 = false,
                    videoPlanState = presentationState.videoPlan,
                    presentationBlocked = presentationState.clickHandoffPending || storeVisitBlocked,
                    willHandoff = { hasNextStep },
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
                    onReady = { durationMs ->
                        videoFirstFrameAdmitted = true
                        videoDurationMs = durationMs
                        presentationState.retainVideoDurationMs(fallbackIndex, durationMs)
                        gateMs = videoCloseGateMs(closeBehavior.delaySeconds, durationMs)
                        val accumulated = presentationState.closeGateElapsedMs(fallbackIndex).coerceAtMost(gateMs)
                        countdown = closeGateSecondsRemaining(accumulated, gateMs)
                        videoRingProgress = closeGateProgress(accumulated, gateMs)
                    },
                    onProgress = { _, durationMs, advancedMs ->
                        videoDurationMs = durationMs
                        presentationState.retainVideoDurationMs(fallbackIndex, durationMs)
                        gateMs = videoCloseGateMs(closeBehavior.delaySeconds, durationMs)
                        val accumulated = presentationState.addCloseGateElapsedMs(
                            fallbackIndex,
                            advancedMs,
                            gateMs,
                        )
                        countdown = closeGateSecondsRemaining(accumulated, gateMs)
                        videoRingProgress = closeGateProgress(accumulated, gateMs)
                    },
                    onCompleted = {
                        presentationState.addCloseGateElapsedMs(fallbackIndex, gateMs, gateMs)
                        countdown = 0
                        videoRingProgress = 1f
                        videoTerminal = true
                    },
                    onError = {
                        applyRendererUnavailable()
                    },
                    onCta = {
                        val routing = videoRouting ?: return@FullscreenVideo
                        val routePlan = routing.route
                        if (presentationState.clickHandoffPending) return@FullscreenVideo
                        val claim = claimClick(ClickSources.FALLBACK_CTA) ?: return@FullscreenVideo
                        notifyPublisherClick { onAdClick(claim.interaction) }
                        val interaction = claim.interaction
                        val routeStartedAtNanos = System.nanoTime()
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
                            recordTelemetry = { completion -> persistClick(adId, interaction, completion) },
                            onHandoff = { committedInteraction, completion ->
                                prepareDeferredCtaRoute(
                                    prepare = {
                                        CreativeCtaRouter.preparePrimaryCta(
                                            routePlan,
                                            routing.destination,
                                            routing.storeUrl,
                                            routeStartedAtNanos,
                                        )
                                    },
                                    requestRoute = { route, routeCompletion ->
                                        val start = routeClick?.invoke(route, routeCompletion)
                                        if (start == null) {
                                            routeCompletion(route(context))
                                            PresentationRouteResult.EXECUTED
                                        } else if (start == ClickRouteStart.REJECTED) {
                                            PresentationRouteResult.REJECTED
                                        } else {
                                            PresentationRouteResult.EXECUTED
                                        }
                                    },
                                    completion = completion,
                                    open = { routeContext, prepared ->
                                        val outcome = CreativeCtaRouter.launchPrepared(routeContext, prepared)
                                        if (outcome == AutomaticNavigationOutcome.STORE_OPENED) {
                                            runCatching { onStoreOpen(committedInteraction) }
                                        }
                                        outcome != AutomaticNavigationOutcome.FAILED &&
                                            outcome != AutomaticNavigationOutcome.HANDLED
                                    },
                                )
                            },
                            onCreated = onClickHandoffCreated,
                            onFinished = onClickHandoffFinished,
                        )
                    },
                )
            }
        } else if (!rendererGone) AndroidView(
            factory = { ctx ->
                var realLoadStarted = false
                val token = renderGate.begin().also { renderToken = it }
                WebViewPool.acquire(
                    context = ctx,
                    client = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (!realLoadStarted) return
                            BridgeWebViewInstaller.onPageStarted(view)
                            presentationState.onNavigationStarted(url, navigationOwner)
                            if (CreativeCtaRouter.matchesKnownTrackingUrl(url, ctaTrackingUrl)) {
                                presentationState.markAutomaticTrackerRequested(fallbackIndex)
                            }
                            if (renderGate.isPending(token)) {
                                pageCommitted = false
                                if (!url.isNullOrBlank() && (url != "about:blank" || inlineHtml != null)) {
                                    pageLoadFailed = false
                                }
                            }
                        }
                        override fun onPageCommitVisible(view: WebView?, url: String?) {
                            if (!realLoadStarted) return
                            if (!url.isNullOrBlank() &&
                                (url != "about:blank" || inlineHtml != null) &&
                                !pageLoadFailed
                            ) {
                                if (renderGate.ready(token)) pageCommitted = true
                            }
                        }
                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (!realLoadStarted) return
                            if (request?.isForMainFrame == true) {
                                if (fallbackHtmlFailureAction(pageCommitted, isMainFrame = true) ==
                                    FallbackHtmlFailureAction.FAIL_BLANK
                                ) {
                                    pageLoadFailed = true
                                    failInitialRenderer(token)
                                }
                            }
                        }
                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?,
                        ) {
                            if (!realLoadStarted) return
                            if (request?.isForMainFrame == true) {
                                if (fallbackHtmlFailureAction(pageCommitted, isMainFrame = true) ==
                                    FallbackHtmlFailureAction.FAIL_BLANK
                                ) {
                                    pageLoadFailed = true
                                    failInitialRenderer(token)
                                }
                            }
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url?.toString() ?: return false
                            presentationState.navigationOverride(
                                targetUrl = target,
                                isMainFrame = request.isForMainFrame,
                                hasGesture = request.hasGesture(),
                                owner = navigationOwner,
                            )?.let { return it }
                            if (request.hasGesture() != true && request.isForMainFrame) {
                                when (val plan = CreativeCtaRouter.automaticNavigationPlan(
                                    value = target,
                                    destination = ctaDestination,
                                    trackingUrl = ctaTrackingUrl,
                                )) {
                                    CreativeCtaRouter.AutomaticNavigationPlan.AllowInWebView -> Unit
                                    CreativeCtaRouter.AutomaticNavigationPlan.Consume -> return true
                                    is CreativeCtaRouter.AutomaticNavigationPlan.RouteExact -> {
                                        presentationState.retainAutomaticNavigation(
                                            fallbackIndex,
                                            navigationOwner,
                                            plan.targetUrl,
                                            presentationState.wasAutomaticTrackerRequested(fallbackIndex) ||
                                                CreativeCtaRouter.matchesKnownTrackingUrl(view?.url, ctaTrackingUrl),
                                        )
                                        onAutomaticNavigation()
                                        return true
                                    }
                                }
                            }
                            val routePlan = when (val plan = CreativeCtaRouter.fallbackCtaTapPlan(
                                isMainFrame = request.isForMainFrame,
                                hasGesture = request.hasGesture(),
                                tappedUrl = target,
                                creativeBaseUrl = null,
                                trackingUrl = ctaTrackingUrl,
                                destination = ctaDestination,
                            )) {
                                CreativeCtaRouter.PrimaryCtaTapPlan.AllowInWebView -> return false
                                CreativeCtaRouter.PrimaryCtaTapPlan.ConsumeWithoutClick -> return true
                                is CreativeCtaRouter.PrimaryCtaTapPlan.Route -> plan.route
                            }
                            // Route through the shared CTA router: app-store trackers resolve to an
                            // exact referrer-preserving Play URL; the serve's raw store link is the
                            // deterministic fallback when it cannot be launched. A failed launch
                            // returns false so the WebView navigates in place (the pre-router
                            // failure behavior).
                            // A genuine user tap uses one native fallback_cta interaction id for durable
                            // beacon + lifecycle attribution. Programmatic redirects remain non-clicks.
                            val unknownSource = when (sourceIndex) {
                                0 -> ClickSources.END_SCREEN_1_UNKNOWN
                                1 -> ClickSources.END_SCREEN_2_UNKNOWN
                                else -> ClickSources.END_SCREEN_2_UNKNOWN
                            }
                            val claim = claimWebClick(unknownSource) ?: return true
                            notifyPublisherClick { onAdClick(claim.interaction) }
                            val interaction = claim.interaction
                            val routeStartedAtNanos = System.nanoTime()
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
                                recordTelemetry = { completion -> persistClick(adId, interaction, completion) },
                                onHandoff = { committedInteraction, completion ->
                                    prepareDeferredCtaRoute(
                                        prepare = {
                                            CreativeCtaRouter.preparePrimaryCta(
                                                routePlan,
                                                ctaDestination,
                                                ctaStoreUrl,
                                                routeStartedAtNanos,
                                            )
                                        },
                                        requestRoute = { route: (Context) -> Boolean, routeCompletion ->
                                            val start = routeClick?.invoke(route, routeCompletion)
                                            if (start == null) {
                                                routeCompletion(route(ctx))
                                                PresentationRouteResult.EXECUTED
                                            } else if (start == ClickRouteStart.REJECTED) {
                                                PresentationRouteResult.REJECTED
                                            } else {
                                                PresentationRouteResult.EXECUTED
                                            }
                                        },
                                        completion = completion,
                                        open = { routeContext, prepared ->
                                            val outcome = CreativeCtaRouter.launchPrepared(routeContext, prepared)
                                            val opened = outcome != AutomaticNavigationOutcome.FAILED &&
                                                outcome != AutomaticNavigationOutcome.HANDLED
                                            if (outcome == AutomaticNavigationOutcome.STORE_OPENED) {
                                                runCatching { onStoreOpen(committedInteraction) }
                                            }
                                            if (!opened) {
                                                CreativeCtaRouter.admittedInWebViewFallback(
                                                    routePlan.tappedUrl,
                                                    ctaTrackingUrl,
                                                )?.let { fallbackUrl ->
                                                    presentationState.retainNavigation(fallbackUrl)
                                                }
                                            }
                                            opened
                                        },
                                    )
                                },
                                onCreated = onClickHandoffCreated,
                                onFinished = onClickHandoffFinished,
                            )
                            return true
                        }
                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?,
                        ): Boolean {
                            runCatching { recordRenderProcessGone("fallback_ad", detail) }
                            if (view != null && view === fallbackWebView) {
                                renderProcessGone = true
                                pageLoadFailed = true
                                pageCommitted = false
                                renderGate.fail(token)
                                applyRendererUnavailable()
                                runCatching { view.visibility = View.INVISIBLE }
                                fallbackWebView = null
                            }
                            return true
                        }
                    },
                ).apply {
                    SimulaUserAgent.captureBrowser(runCatching { settings.userAgentString }.getOrNull())
                    presentationState.claimNavigationOwner(navigationOwner)
                    fallbackWebView = this
                    if (inlineHtml != null) {
                        val mode = BridgeWebViewInstaller.install(
                            webView = this,
                            bridge = bridge,
                            onTrustedCtaOpen = ::beginTrustedFallbackCta,
                            fallbackClickSource = when (sourceIndex) {
                                0 -> ClickSources.END_SCREEN_1_UNKNOWN
                                else -> ClickSources.END_SCREEN_2_UNKNOWN
                            },
                        )
                        if (mode == BridgeInjectionMode.UNAVAILABLE) {
                            post(::applyRendererUnavailable)
                        } else {
                            realLoadStarted = true
                            loadDataWithBaseURL(null, inlineHtml, "text/html", "UTF-8", null)
                        }
                    }
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
                if (renderProcessGone) {
                    BridgeWebViewInstaller.releaseAfterRendererGone(webView)
                } else {
                    BridgeWebViewInstaller.release(webView)
                }
            },
        )

        if (!isVideo && !pageCommitted) {
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

        val preFirstFrameEscape = videoPreFirstFrameEscapeAvailable(isVideo, videoFirstFrameAdmitted) &&
            !presentationState.clickHandoffPending && !storeVisitBlocked
        val closeReady = (isVideo || pageCommitted) && countdown <= 0 &&
            !presentationState.clickHandoffPending && !storeVisitBlocked
        val closeAlignment = when (closeBehavior.position) {
            ClosePosition.TOP_RIGHT -> Alignment.TopEnd
            ClosePosition.TOP_LEFT -> Alignment.TopStart
            ClosePosition.BOTTOM_LEFT -> Alignment.BottomStart
        }
        if (preFirstFrameEscape) {
            VideoPreFirstFrameEscapeButton("Skip unavailable ad", ::escapePreFirstFrameVideo)
        } else if (closeReady || closeBehavior.treatment == CloseTreatment.COUNTDOWN_CIRCLE) Box(
            modifier = Modifier
                .align(closeAlignment)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = if (closeBehavior.position == ClosePosition.BOTTOM_LEFT) 18.dp else 0.dp)
                .padding(8.dp)
                .size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (closeReady) {
                // Compact close button (16dp circle) with a full 48dp tap target so it's easy to hit.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics {
                            contentDescription = if (closeBehavior.action == CloseAction.FORWARD) {
                                "Next ad"
                            } else {
                                "Close ad"
                            }
                        }
                        .clickable(onClick = { closeOnce(VideoOverlayCloseOrigin.USER) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CloseActionGlyph(closeBehavior.action)
                    }
                }
            } else if (closeBehavior.treatment == CloseTreatment.COUNTDOWN_CIRCLE) {
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
                            sweepAngle = 360f * (if (isVideo) smoothVideoRingProgress else ring.value),
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
            AdInfoReportOverlay(
                adId = adId,
                closeAtBottomLeft = closeBehavior.position == ClosePosition.BOTTOM_LEFT,
            )
        }
    }
}
