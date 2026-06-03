package ad.simula.ad.sdk.image

import android.content.Context

/**
 * Best-effort prefetch of remote creative assets into [ImageCache].
 *
 * Used to warm the interstitial's images before it is reported as LOADED, so the
 * carousel renders without a flash. Each URL is decoded into the shared cache;
 * individual failures are swallowed (a broken asset must not fail the whole load).
 */
internal object ImagePrefetch {
    suspend fun preload(context: Context, urls: List<String>) {
        urls.forEach { runCatching { ImageCache.load(context, it) } }
    }
}
