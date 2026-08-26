package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.FullscreenPresentationRegistry
import ad.simula.ad.sdk.model.AdValue
import ad.simula.ad.sdk.network.AutoRedirectCoordinator
import ad.simula.ad.sdk.network.ClickInteraction
import ad.simula.ad.sdk.network.ClickInteractionClaim
import ad.simula.ad.sdk.network.ClickInteractionGate
import ad.simula.ad.sdk.network.ClickPersistenceHandoff
import ad.simula.ad.sdk.network.PresentationRouteResult
import ad.simula.ad.sdk.network.RetainedPrimaryCtaNavigationState
import ad.simula.ad.sdk.network.ResumedPresentationRoute
import ad.simula.ad.sdk.network.SimulaApiClient
import java.util.concurrent.ConcurrentHashMap

/** Bridge from the interstitial Activity back to the [SimulaInterstitialAd] instance. */
internal interface InterstitialCallbacks {
    /** The "shown" signal — the creative was presented full-screen. */
    fun onDisplayed()

    /** The billable impression — fired ~2s after begin-to-render. */
    fun onImpression()

    /** The paid event — fired together with [onImpression], carrying the on-device estimate. */
    fun onPaid(adValue: AdValue)

    fun persistClick(interaction: ClickInteraction, onTelemetryPersisted: () -> Unit = {})
    fun notifyClicked()

    fun onClicked(interaction: ClickInteraction, onTelemetryPersisted: () -> Unit = {}) {
        persistClick(interaction, onTelemetryPersisted)
        notifyClicked()
    }
    fun onClosed()
}

internal fun notifyPublisherClick(callback: () -> Unit) {
    runCatching(callback)
}

internal fun notifyPublisherClickForClaim(
    claim: ClickInteractionClaim?,
    callback: (ClickInteraction) -> Unit,
): ClickInteractionClaim? {
    claim ?: return null
    notifyPublisherClick { callback(claim.interaction) }
    return claim
}

/** Everything [SimulaInterstitialActivity] needs to render one presentation. */
internal class InterstitialPresentation(
    val ad: SimulaApiClient.AdLoadResult,
    val apiKey: String,
    val callbacks: InterstitialCallbacks,
    val metadata: Map<String, String>? = null,
) {
    private val clickInteractionGate = ClickInteractionGate()
    private var pendingClickHandoff: ClickPersistenceHandoff? = null
    private val clickRoute = ResumedPresentationRoute<SimulaInterstitialActivity>()
    val primaryCtaNavigation = RetainedPrimaryCtaNavigationState<SimulaInterstitialActivity>()
    val installBannerState = InstallBannerPresentationState(ad.adBehavior?.skoverlay)
    val fallbackState = FallbackPresentationState()
    val storeExit by lazy(LazyThreadSafetyMode.NONE) {
        StoreExitTracker(
            adId = ad.impressionId.takeIf { it.isNotBlank() },
            adFormat = "interstitial",
        )
    }
    val autoRedirectCoordinator = AutoRedirectCoordinator()

    @Synchronized
    fun claimClick(source: String): ClickInteractionClaim? =
        if (pendingClickHandoff == null) clickInteractionGate.claim(source) else null

    fun hasPendingClick(): Boolean = clickInteractionGate.hasPendingClaim()

    @Synchronized
    fun attachActivity(activity: SimulaInterstitialActivity) {
        clickRoute.attach(activity)
        primaryCtaNavigation.attachActivity(activity)
    }

    fun resumeActivity(activity: SimulaInterstitialActivity) {
        clickRoute.resume(activity)
    }

    fun pauseActivity(activity: SimulaInterstitialActivity) {
        clickRoute.pause(activity)
    }

    fun detachActivity(activity: SimulaInterstitialActivity) {
        clickRoute.detach(activity)
        primaryCtaNavigation.detachActivity(activity)
    }

    fun routeClick(
        route: (SimulaInterstitialActivity) -> Boolean,
        completion: (Boolean) -> Unit,
    ): PresentationRouteResult = clickRoute.request(route, completion)

    @Synchronized
    fun setPrimaryFallback(
        owner: Any,
        activity: SimulaInterstitialActivity,
        fallback: (String) -> Boolean,
    ): Boolean = primaryCtaNavigation.bindNavigation(owner, activity, fallback)

    @Synchronized
    fun clearPrimaryFallback(owner: Any) {
        primaryCtaNavigation.unbindNavigation(owner)
    }

    fun openPrimaryFallback(url: String, activity: SimulaInterstitialActivity): Boolean =
        primaryCtaNavigation.retainFallback(url, activity)

    @Synchronized
    fun pendingClickHandoff(): ClickPersistenceHandoff? = pendingClickHandoff

    @Synchronized
    fun trackClickHandoff(handoff: ClickPersistenceHandoff) {
        pendingClickHandoff = handoff
        primaryCtaNavigation.onHandoffCreated(handoff)
        autoRedirectCoordinator.observeUserHandoff(handoff)
    }

    @Synchronized
    fun clearClickHandoff(handoff: ClickPersistenceHandoff) {
        if (pendingClickHandoff === handoff) {
            pendingClickHandoff = null
            primaryCtaNavigation.onHandoffFinished(handoff)
        }
    }

    @Synchronized
    fun cancelPendingClickHandoff() {
        autoRedirectCoordinator.dispose()
        pendingClickHandoff?.cancel()
        pendingClickHandoff = null
        clickRoute.cancel()
        primaryCtaNavigation.clear()
        fallbackState.clear()
    }

    /** Guards a duplicate SHOWN (DISPLAYED) report if the Activity is recreated on a config change. */
    var displayedReported = false

    /** Guards a duplicate billable IMPRESSION + PAID report across a config-change recreation. */
    var impressionReported = false

    /** Foreground-only on-screen time accrued toward the begin-to-render + 2s impression mark, in ms.
     * Accrues only while the Activity is RESUMED (see the impression loop in [SimulaInterstitialActivity])
     * so a backgrounded ad can't accrue it. Lives here (not in the Activity) so a config-change
     * recreation resumes the remaining time instead of restarting it. `0L` until the loop first ticks. */
    var accumulatedImpressionTimeMs = 0L

    /** Foreground-only play/dwell time accrued toward the close-delay gate, in ms. Accrues only
     * while the Activity is RESUMED (see the gate loop in [SimulaInterstitialActivity]) so leaving
     * the app pauses the countdown. Lives here (not in the Activity) so a config-change recreation
     * resumes the remaining dwell instead of restarting it. `0L` until the gate first ticks. */
    var accumulatedGateTimeMs = 0L
}

/**
 * Hands a non-parcelable [InterstitialPresentation] to [SimulaInterstitialActivity]
 * via a token placed in the launch Intent — the loaded ad and the callback bridge
 * can't travel through Intent extras. This is the standard ad-SDK pattern.
 *
 * Reads are non-destructive ([get]) so the presentation survives an Activity
 * recreation (e.g. a config change not covered by `configChanges`); the entry is
 * only dropped via [remove] when the Activity finishes for good.
 */
internal object InterstitialHandoff {
    private val pending = ConcurrentHashMap<String, InterstitialPresentation>()

    fun put(token: String, presentation: InterstitialPresentation) {
        pending[token] = presentation
    }

    fun get(token: String): InterstitialPresentation? = pending[token]

    fun markPresented(token: String) {
        FullscreenPresentationRegistry.claim("interstitial:$token")
    }

    fun remove(token: String) {
        pending.remove(token)?.cancelPendingClickHandoff()
        FullscreenPresentationRegistry.release("interstitial:$token")
    }
}
