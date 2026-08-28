package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.minigame.isWebViewPoolResetUrl

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

internal fun isQualifiedRewardedCreativeCommit(
    source: RewardedCreativeSource?,
    loadArmed: Boolean,
    mainFrameFailed: Boolean,
    url: String?,
): Boolean {
    if (!loadArmed || mainFrameFailed || url.isNullOrBlank()) return false
    return when (source) {
        is RewardedCreativeSource.Html -> url == "about:blank"
        is RewardedCreativeSource.Iframe -> when {
            source.url.startsWith("data:", ignoreCase = true) -> url.startsWith("data:", ignoreCase = true)
            else -> !isWebViewPoolResetUrl(url) && CreativeCtaRouter.admittedHttpUrl(url) != null
        }
        null -> false
    }
}
