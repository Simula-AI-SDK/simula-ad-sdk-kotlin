package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.network.SimulaApiClient
import java.util.concurrent.ConcurrentHashMap

/** Bridge from the interstitial Activity back to the [SimulaInterstitialAd] instance. */
internal interface InterstitialCallbacks {
    fun onDisplayed()
    fun onClicked()
    fun onClosed()
}

/** Everything [SimulaInterstitialActivity] needs to render one presentation. */
internal class InterstitialPresentation(
    val ad: SimulaApiClient.AdLoadResult,
    val apiKey: String,
    val callbacks: InterstitialCallbacks,
) {
    /** Guards a duplicate DISPLAYED/impression if the Activity is recreated on a config change. */
    var displayedReported = false

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

    fun remove(token: String) {
        pending.remove(token)
    }
}
