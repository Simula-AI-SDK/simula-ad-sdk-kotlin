package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.FullscreenPresentationRegistry
import ad.simula.ad.sdk.model.AdBehavior
import ad.simula.ad.sdk.model.AdValue
import ad.simula.ad.sdk.network.AutoRedirectCoordinator
import ad.simula.ad.sdk.network.ClickInteraction
import ad.simula.ad.sdk.network.ClickInteractionClaim
import ad.simula.ad.sdk.network.ClickInteractionGate
import ad.simula.ad.sdk.network.ClickPersistenceHandoff
import ad.simula.ad.sdk.network.PresentationRouteResult
import ad.simula.ad.sdk.network.RetainedPrimaryCtaNavigationState
import ad.simula.ad.sdk.network.ResumedPresentationRoute
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap

/** Bridge from the rewarded Activity back to the [SimulaRewardedAd] instance. */
internal interface RewardedCallbacks {
    /** The "shown" signal — the playable was presented full-screen. */
    fun onDisplayed()

    /** The billable impression — fired ~2s after begin-to-render, independent of the reward gate. */
    fun onImpression()

    /** The paid event — fired together with [onImpression], carrying the on-device estimate. */
    fun onPaid(adValue: AdValue)

    fun persistClick(interaction: ClickInteraction, onTelemetryPersisted: () -> Unit = {})
    fun persistFallbackClick(
        adId: String,
        serveId: String?,
        interaction: ClickInteraction,
        onTelemetryPersisted: () -> Unit = {},
    ) = persistClick(interaction, onTelemetryPersisted)
    fun notifyClicked()

    /** A successfully routed CTA. Fallback HTML still owns its backend beacon. */
    fun onClicked(interaction: ClickInteraction, onTelemetryPersisted: () -> Unit = {}) {
        persistClick(interaction, onTelemetryPersisted)
        notifyClicked()
    }

    /**
     * The minigame (playable) surface was dismissed. [earned] is whether the play reached the
     * required duration; [elapsedPlayTimeSeconds] is the measured play time. The post-game fallback
     * ad screens may still follow, so the reward is NOT verified here — see [onRewardCompleted].
     */
    fun onClose(earned: Boolean, elapsedPlayTimeSeconds: Double)

    /**
     * The whole rewarded unit has been completed — the user dismissed the playable AND every
     * post-game fallback ad screen (fires immediately on close when there are none). The reward is
     * contingent on reaching this point, so the earned-reward signal and server-side verification
     * happen here rather than at [onClose].
     */
    fun onRewardCompleted(earned: Boolean, elapsedPlayTimeSeconds: Double)
}

/** Everything [SimulaRewardedActivity] needs to render one rewarded presentation. */
internal class RewardedPresentation(
    val iframeUrl: String,
    // Server-rendered HTML creative; preferred over [iframeUrl] when non-empty.
    val renderedHtml: String = "",
    // The impression id from /load/rewarded — the handle for tracking, reporting and fallbacks.
    val impressionId: String,
    val apiKey: String,
    val callbacks: RewardedCallbacks,
    // Play-to-earn gate (`close.delaySeconds`) + mid-ad store prompt config + tap routing (mirrors
    // the interstitial). A null [adBehavior] means no gate (instantly earned) and no store prompt.
    val adBehavior: AdBehavior? = null,
    val trackingUrl: String? = null,
    val destination: String = "appstore",
    // Raw, unwrapped Play Store link — the deterministic CTA fallback when the tracker is
    // missing or can't be launched (see CreativeCtaRouter).
    val androidStoreUrl: String? = null,
    // Estimated revenue for this serve, surfaced on the paid event when the impression
    // fires. Held here from load time (no network round-trip at impression). Defaults to a $0 estimate
    // for the preview path, which constructs this presentation without a real serve.
    val adValue: AdValue = AdValue.fromBidCpm(0.0),
    val metadata: Map<String, String>? = null,
) {
    private val clickInteractionGate = ClickInteractionGate()
    private var pendingClickHandoff: ClickPersistenceHandoff? = null
    private val clickRoute = ResumedPresentationRoute<SimulaRewardedActivity>()
    val primaryCtaNavigation = RetainedPrimaryCtaNavigationState<SimulaRewardedActivity>()
    val fallbackState = FallbackPresentationState()
    val automaticNavigationGate = AutomaticNavigationGate()
    val storeExit by lazy(LazyThreadSafetyMode.NONE) {
        StoreExitTracker(
            adId = impressionId.takeIf { it.isNotBlank() },
            adFormat = "rewarded",
        )
    }
    val autoRedirectCoordinator = AutoRedirectCoordinator()
    var primaryCreativeUnavailable by mutableStateOf(false)

    @Synchronized
    fun claimClick(source: String): ClickInteractionClaim? =
        if (pendingClickHandoff == null) clickInteractionGate.claim(source) else null

    fun hasPendingClick(): Boolean = clickInteractionGate.hasPendingClaim()

    fun attachActivity(activity: SimulaRewardedActivity) {
        clickRoute.attach(activity)
        primaryCtaNavigation.attachActivity(activity)
    }

    fun resumeActivity(activity: SimulaRewardedActivity) {
        clickRoute.resume(activity)
    }

    fun pauseActivity(activity: SimulaRewardedActivity) {
        clickRoute.pause(activity)
    }

    fun detachActivity(activity: SimulaRewardedActivity) {
        clickRoute.detach(activity)
        primaryCtaNavigation.detachActivity(activity)
    }

    fun routeClick(
        route: (SimulaRewardedActivity) -> Boolean,
        completion: (Boolean) -> Unit,
    ): PresentationRouteResult = clickRoute.request(route, completion)

    @Synchronized
    fun setPrimaryFallback(
        owner: Any,
        activity: SimulaRewardedActivity,
        fallback: (String) -> Boolean,
    ): Boolean = primaryCtaNavigation.bindNavigation(owner, activity, fallback)

    @Synchronized
    fun clearPrimaryFallback(owner: Any) {
        primaryCtaNavigation.unbindNavigation(owner)
    }

    fun openPrimaryFallback(url: String, activity: SimulaRewardedActivity): Boolean =
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
        automaticNavigationGate.clear()
        fallbackState.clear()
    }

    /** Guards a duplicate SHOWN (DISPLAYED) report if the Activity is recreated on a config change. */
    var displayedReported = false

    /** Guards a duplicate billable IMPRESSION + PAID report across a config-change recreation. */
    var impressionReported = false

    /** Foreground-only on-screen time accrued toward the begin-to-render + 2s impression mark, in ms.
     * Accrues only while the Activity is RESUMED so a backgrounded playable can't accrue it; anchored
     * on the presentation so a config-change recreation resumes rather than restarts. `0L` until the
     * impression loop first ticks. Independent of [accumulatedPlayTimeMs] (the reward gate). */
    var accumulatedImpressionTimeMs = 0L

    /** Set true once the required play duration elapses; gates the reward. */
    var rewardEarned = false

    /** Sticky evidence that this serve committed its intended creative at least once. */
    var creativeExposed = false

    /**
     * Foreground-only accumulated play time, in milliseconds. Time accrues only while
     * the Activity is RESUMED (see [SimulaRewardedActivity]'s gate), so backgrounding
     * the app can't advance it toward the reward. Anchored on the presentation (which
     * survives Activity recreation via the handoff) so a config change resumes the
     * remaining time instead of restarting it. Also the `elapsed_play_time` reported to
     * `verify-reward`.
     */
    var accumulatedPlayTimeMs = 0L
}

/**
 * Hands a non-parcelable [RewardedPresentation] to [SimulaRewardedActivity] via a
 * token placed in the launch Intent — the loaded ad and the callback bridge can't
 * travel through Intent extras. Mirrors [InterstitialHandoff].
 *
 * Reads are non-destructive ([get]) so the presentation survives an Activity
 * recreation; the entry is only dropped via [remove] when the Activity finishes.
 */
internal object RewardedHandoff {
    private val pending = ConcurrentHashMap<String, RewardedPresentation>()

    fun put(token: String, presentation: RewardedPresentation) {
        pending[token] = presentation
    }

    fun get(token: String): RewardedPresentation? = pending[token]

    fun markPresented(token: String) {
        FullscreenPresentationRegistry.claim("rewarded:$token")
    }

    fun remove(token: String) {
        pending.remove(token)?.cancelPendingClickHandoff()
        FullscreenPresentationRegistry.release("rewarded:$token")
    }
}
