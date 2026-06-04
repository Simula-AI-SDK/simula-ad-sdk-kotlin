package ad.simula.ad.sdk.ads

/**
 * Lifecycle callbacks for a [SimulaRewardedAd]. Naming follows the Android ad-SDK
 * convention (AdMob-style `onAd*`).
 *
 * All methods have default no-op bodies — override only the ones you need. Every
 * callback is delivered on the main thread.
 */
interface SimulaRewardedAdListener {
    /** The rewarded minigame finished loading and is ready to [SimulaRewardedAd.show]. */
    fun onAdLoaded(ad: SimulaRewardedAd) {}

    /** Loading failed. The ad returns to the idle state and may be loaded again. */
    fun onAdFailedToLoad(ad: SimulaRewardedAd, error: SimulaAdError) {}

    /** The rewarded minigame was presented full-screen. */
    fun onAdDisplayed(ad: SimulaRewardedAd) {}

    /** Presentation failed (e.g. not ready, already showing, no Activity). */
    fun onAdFailedToDisplay(ad: SimulaRewardedAd, error: SimulaAdError) {}

    /**
     * The user played long enough to earn the reward — fired on a qualifying dismiss,
     * before server verification completes.
     */
    fun onAdEarnedReward(ad: SimulaRewardedAd) {}

    /**
     * The server verified the play and fired the publisher's SSV postback. [token] is
     * the publisher-facing reward token (may be null when the call was an idempotent
     * re-verification of an already-claimed reward).
     */
    fun onAdRewardVerified(ad: SimulaRewardedAd, token: String?) {}

    /**
     * Verification could not be completed. It may still be retried in the background
     * from the persistent queue.
     */
    fun onAdRewardVerificationFailed(ad: SimulaRewardedAd, error: Throwable) {}

    /** The rewarded minigame was dismissed. The next ad is auto-preloaded. */
    fun onAdClosed(ad: SimulaRewardedAd) {}
}
