package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.AdBehavior
import java.util.concurrent.ConcurrentHashMap

/** Bridge from the rewarded Activity back to the [SimulaRewardedAd] instance. */
internal interface RewardedCallbacks {
    fun onDisplayed()

    /**
     * The surface was dismissed. [earned] is whether the play reached the required
     * duration; [elapsedPlayTimeSeconds] is the measured play time to verify with.
     */
    fun onClose(earned: Boolean, elapsedPlayTimeSeconds: Double)
}

/** Everything [SimulaRewardedActivity] needs to render one rewarded presentation. */
internal class RewardedPresentation(
    val iframeUrl: String,
    val durationSeconds: Int,
    // The impression id from /load/rewarded — the handle for tracking, reporting and fallbacks.
    val impressionId: String,
    val apiKey: String,
    val callbacks: RewardedCallbacks,
    // Mid-ad store prompt config + tap routing (mirrors the interstitial). A null [adBehavior]
    // (no `store_prompt`) means no badge is shown.
    val adBehavior: AdBehavior? = null,
    val trackingUrl: String? = null,
    val destination: String = "appstore",
) {
    /** Guards a duplicate DISPLAYED/impression if the Activity is recreated on a config change. */
    var displayedReported = false

    /** Set true once the required play duration elapses; gates the reward. */
    var rewardEarned = false

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

    fun remove(token: String) {
        pending.remove(token)
    }
}
