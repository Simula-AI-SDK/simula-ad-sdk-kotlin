package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.Message
import ad.simula.ad.sdk.model.MiniGameInterstitialTheme
import ad.simula.ad.sdk.model.MiniGameTheme
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
    val catalog: SimulaApiClient.CatalogResult,
    val charID: String,
    val charName: String,
    val charImage: String,
    val charDesc: String?,
    val invitationText: String,
    val ctaText: String,
    val backgroundImage: String?,
    val theme: MiniGameTheme,
    val inviteTheme: MiniGameInterstitialTheme,
    val messages: List<Message>,
    val maxGamesToShow: Int,
    val delegateChar: Boolean,
    val callbacks: InterstitialCallbacks,
) {
    /** Guards a duplicate DISPLAYED if the Activity is recreated on a config change. */
    var displayedReported: Boolean = false
}

/**
 * Hands a non-parcelable [InterstitialPresentation] to [SimulaInterstitialActivity]
 * via a token placed in the launch Intent — the loaded catalog and the callback
 * bridge can't travel through Intent extras. This is the standard ad-SDK pattern.
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
