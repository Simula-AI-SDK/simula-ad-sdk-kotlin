package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.network.SimulaApiClient
import java.util.concurrent.ConcurrentHashMap

/** Bridge from the interstitial Activity back to the [SimulaInterstitialAd] instance. */
internal interface InterstitialCallbacks {
    fun onDisplayed()
    fun onClicked()
    fun onEarnedReward()
    fun onClosed()
}

/** Everything [SimulaInterstitialActivity] needs to render one presentation. */
internal class InterstitialPresentation(
    val ad: SimulaApiClient.AdLoadResult,
    val ctaText: String,
    val apiKey: String,
    val rewarded: Boolean,
    val minPlayThresholdMs: Long,
    val callbacks: InterstitialCallbacks,
) {
    /** Guards a duplicate DISPLAYED/impression if the Activity is recreated on a config change. */
    var displayedReported = false

    /** Set true once the rewarded play threshold elapses; gates the reward callback. */
    var rewardEarned = false

    /**
     * `SystemClock.elapsedRealtime()` when the rewarded dwell first started, or 0 if
     * not yet started. Anchored on the presentation (which survives Activity
     * recreation via the handoff) so a config change resumes the remaining dwell
     * instead of restarting it — otherwise rotating could reset/evade the gate.
     */
    var gateStartedAtMs = 0L
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

    fun remove(token: String) {
        pending.remove(token)
    }
}
