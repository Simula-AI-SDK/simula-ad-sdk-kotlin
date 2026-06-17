package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.AdValue

/**
 * Lifecycle callbacks for a [SimulaInterstitialAd]. Naming follows the Android
 * ad-SDK convention (AdMob-style `onAd*`).
 *
 * All methods have default no-op bodies — override only the ones you need. Every
 * callback is delivered on the main thread.
 *
 * The AdMob three-signal model maps to: [onAdDisplayed] = *shown*, [onAdImpression] =
 * *billable impression*, [onAdPaid] = *paid* (carries estimated revenue). For a full-screen
 * ad the impression and paid signals fire together, ~2 seconds after the creative begins to
 * render.
 */
interface SimulaInterstitialAdListener {
    /** The ad finished loading and is ready to [SimulaInterstitialAd.show]. */
    fun onAdLoaded(ad: SimulaInterstitialAd) {}

    /** Loading failed. The ad returns to the idle state and may be loaded again. */
    fun onAdFailedToLoad(ad: SimulaInterstitialAd, error: SimulaAdError) {}

    /** The interstitial was presented full-screen (AdMob's "shown"). */
    fun onAdDisplayed(ad: SimulaInterstitialAd) {}

    /**
     * A billable impression was recorded — fired ~2s after the creative begins to render
     * (AdMob's `onAdImpression`). Distinct from [onAdDisplayed]. Followed immediately by [onAdPaid].
     */
    fun onAdImpression(ad: SimulaInterstitialAd) {}

    /**
     * The estimated revenue for this impression (AdMob's `onPaidEvent`). Fired together with
     * [onAdImpression]; [adValue] is already on-device from load time (no network round-trip). Use it
     * for your own analytics — the backend's impression confirmation remains the source of truth for
     * billing.
     */
    fun onAdPaid(ad: SimulaInterstitialAd, adValue: AdValue) {}

    /** Presentation failed (e.g. not ready, already showing, no Activity). */
    fun onAdFailedToDisplay(ad: SimulaInterstitialAd, error: SimulaAdError) {}

    /** The user tapped the call-to-action. */
    fun onAdClicked(ad: SimulaInterstitialAd) {}

    /** The interstitial was dismissed. The next ad is auto-preloaded. */
    fun onAdClosed(ad: SimulaInterstitialAd) {}
}
