package ad.simula.ad.sdk.ads

internal sealed interface PrimaryFullscreenCreativeSource {
    data class Html(val value: String) : PrimaryFullscreenCreativeSource
    data class Iframe(val url: String) : PrimaryFullscreenCreativeSource
}

/** Primary fullscreen contract: rendered HTML wins; iframe URL is fallback only. */
internal fun primaryFullscreenCreativeSource(
    renderedHtml: String?,
    iframeUrl: String?,
): PrimaryFullscreenCreativeSource? {
    renderedHtml?.takeIf { it.isNotBlank() }?.let {
        return PrimaryFullscreenCreativeSource.Html(it)
    }
    iframeUrl?.takeIf { it.isNotBlank() }?.let {
        return PrimaryFullscreenCreativeSource.Iframe(it)
    }
    return null
}
