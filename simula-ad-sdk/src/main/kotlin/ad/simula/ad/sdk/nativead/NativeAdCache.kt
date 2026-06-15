package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.network.SimulaApiClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-slot cache of resolved native ads, keyed by `"adUnitId:position"`.
 *
 * A [NativeAdSlot] lives inside a lazy list, which disposes and recreates off-screen rows. Without
 * this cache, scrolling a slot out and back would re-run `POST /load/native` and re-fire the
 * impression every time — over-serving and inflating impression counts. With it, the first
 * appearance fetches once; every later remount renders the same ad from memory (no network, no
 * shimmer) and the impression fires exactly once per served ad. The reported height is cached too,
 * so a recycled row sizes correctly on the first frame.
 *
 * Cleared with [ad.simula.ad.sdk.ads.SimulaAds.invalidateNativeAd] when the publisher wants a fresh
 * ad for a slot.
 */
internal object NativeAdCache {

    /** A cached fill (with its measured height + whether its impression already fired) or a no-fill. */
    internal sealed interface Value {
        class Fill(val result: SimulaApiClient.NativeAdResult) : Value {
            @Volatile var heightDp: Float = 0f
            @Volatile var impressionFired: Boolean = false
        }

        data object NoFill : Value
    }

    private val entries = ConcurrentHashMap<String, Value>()

    /** Impression ids whose impression already fired. Backs [markImpressionFired] so the same served
     * ad reports at most one impression process-wide — even if shown in two slots (same cache key) or
     * re-composed — independent of the per-slot [Value.Fill.impressionFired] flag. */
    private val firedImpressions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun key(adUnitId: String?, position: Int) = "${adUnitId.orEmpty()}:$position"

    /** Atomically marks [impressionId] as having fired. Returns true only the first time, so callers
     * fire the impression (callback + server beacon) at most once per served ad. Blank ids (previews)
     * are never tracked and always return false. */
    fun markImpressionFired(impressionId: String): Boolean =
        impressionId.isNotBlank() && firedImpressions.add(impressionId)

    fun get(adUnitId: String?, position: Int): Value? = entries[key(adUnitId, position)]

    fun putFill(adUnitId: String?, position: Int, result: SimulaApiClient.NativeAdResult): Value.Fill {
        val fill = Value.Fill(result)
        entries[key(adUnitId, position)] = fill
        return fill
    }

    fun putNoFill(adUnitId: String?, position: Int) {
        entries[key(adUnitId, position)] = Value.NoFill
    }

    fun invalidate(adUnitId: String?, position: Int) {
        // Drop the impression-id mark too so a deliberately-refreshed slot can fire again, and free the
        // retained WebView so the refreshed slot rebuilds instead of reattaching the stale creative.
        (entries.remove(key(adUnitId, position)) as? Value.Fill)?.let {
            firedImpressions.remove(it.result.impressionId)
            NativeAdWebViewStore.evict(it.result.impressionId)
        }
    }

    fun invalidateAll() {
        entries.clear()
        firedImpressions.clear()
        NativeAdWebViewStore.evictAll()
    }
}
