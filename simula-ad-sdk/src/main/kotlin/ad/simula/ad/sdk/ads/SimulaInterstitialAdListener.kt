package ad.simula.ad.sdk.ads

/**
 * Lifecycle callbacks for a [SimulaInterstitialAd]. Naming follows the Android
 * ad-SDK convention (AdMob-style `onAd*`).
 *
 * All methods have default no-op bodies — override only the ones you need. Every
 * callback is delivered on the main thread.
 */
interface SimulaInterstitialAdListener {
    /** The ad finished loading and is ready to [SimulaInterstitialAd.show]. */
    fun onAdLoaded(ad: SimulaInterstitialAd) {}

    /** Loading failed. The ad returns to the idle state and may be loaded again. */
    fun onAdFailedToLoad(ad: SimulaInterstitialAd, error: SimulaAdError) {}

    /** The interstitial was presented full-screen. */
    fun onAdDisplayed(ad: SimulaInterstitialAd) {}

    /** Presentation failed (e.g. not ready, already showing, no Activity). */
    fun onAdFailedToDisplay(ad: SimulaInterstitialAd, error: SimulaAdError) {}

    /** The user tapped the call-to-action. */
    fun onAdClicked(ad: SimulaInterstitialAd) {}

    /** The interstitial was dismissed. The next ad is auto-preloaded. */
    fun onAdClosed(ad: SimulaInterstitialAd) {}
}
