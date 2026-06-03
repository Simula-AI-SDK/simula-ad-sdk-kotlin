package ad.simula.ad.sdk.image

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Best-effort prefetch of remote creative assets into [ImageCache].
 *
 * Used to warm the interstitial's images before it is reported as LOADED, so the
 * carousel renders without a flash. Assets are fetched in parallel and the whole
 * warm-up is capped at [PREFETCH_BUDGET_MS]: a slow/hanging CDN can otherwise
 * stall the LOADED callback indefinitely (each [ImageCache.load] only carries the
 * underlying ~10s connect/read timeout, and serial awaiting multiplies that). When
 * the budget elapses we report LOADED anyway — the carousel already tolerates a
 * not-yet-decoded asset (it paints a frame late). Individual failures are swallowed.
 */
internal object ImagePrefetch {
    /** Overall wall-clock budget for warming all assets before reporting LOADED. */
    private const val PREFETCH_BUDGET_MS = 8_000L

    suspend fun preload(context: Context, urls: List<String>) {
        if (urls.isEmpty()) return
        withTimeoutOrNull(PREFETCH_BUDGET_MS) {
            coroutineScope {
                urls.map { url -> async { runCatching { ImageCache.load(context, url) } } }.awaitAll()
            }
        }
    }
}
