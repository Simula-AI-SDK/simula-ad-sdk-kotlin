package ad.simula.ad.sdk.ads

internal sealed interface RewardedCreativeSource {
    data class Html(val value: String) : RewardedCreativeSource
    data class Iframe(val url: String) : RewardedCreativeSource
}

/** Rewarded contract: rendered HTML wins; the legacy iframe URL is fallback only. */
internal fun rewardedCreativeSource(
    renderedHtml: String?,
    iframeUrl: String?,
): RewardedCreativeSource? {
    renderedHtml?.takeIf { it.isNotBlank() }?.let {
        return RewardedCreativeSource.Html(it)
    }
    iframeUrl?.takeIf { it.isNotBlank() }?.let {
        return RewardedCreativeSource.Iframe(it)
    }
    return null
}
