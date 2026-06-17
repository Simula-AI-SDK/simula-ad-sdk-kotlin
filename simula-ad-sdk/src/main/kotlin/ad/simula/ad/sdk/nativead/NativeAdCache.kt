package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.network.SimulaApiClient

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
 * The map is an access-ordered LRU bounded by [MAX_ENTRIES]: a long feed would otherwise accrue one
 * entry (each holding a full rendered creative) per scrolled-past position for the life of the
 * process. The cap is far above the handful of slots ever on screen, so eviction only targets
 * long-scrolled-past positions — which simply re-fetch (and fire a fresh impression) if revisited.
 *
 * Cleared with [ad.simula.ad.sdk.ads.SimulaAds.invalidateNativeAd] when the publisher wants a fresh
 * ad for a slot.
 */
internal object NativeAdCache {

    /** Hard cap on retained slot entries. Generous relative to on-screen slot count; keeps process
     * memory bounded regardless of feed length. */
    private const val MAX_ENTRIES = 64

    /** A cached fill (with its measured height + whether its impression already fired) or a no-fill. */
    internal sealed interface Value {
        class Fill(val result: SimulaApiClient.NativeAdResult) : Value {
            @Volatile var heightDp: Float = 0f
            @Volatile var impressionFired: Boolean = false
        }

        data object NoFill : Value
    }

    /** Guards [entries] and [firedImpressions]. The access-ordered [entries] map mutates on read, so
     * every access — including [get] — must hold this lock. */
    private val lock = Any()

    /** Impression ids whose impression already fired. Backs [markImpressionFired] so the same served
     * ad reports at most one impression process-wide — even if shown in two slots (same cache key) or
     * re-composed — independent of the per-slot [Value.Fill.impressionFired] flag. Bounded because an
     * id is dropped when its entry is evicted or invalidated. */
    private val firedImpressions: MutableSet<String> = HashSet()

    /** Access-ordered (LRU) and bounded by [MAX_ENTRIES]. When the eldest entry is evicted its fired
     * mark is dropped too; the retained WebView is left to [NativeAdWebViewStore]'s own (smaller) LRU
     * rather than force-destroyed here, so an automatic eviction can never tear down an attached view. */
    private val entries = object : LinkedHashMap<String, Value>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Value>): Boolean {
            if (size <= MAX_ENTRIES) return false
            (eldest.value as? Value.Fill)?.let { firedImpressions.remove(it.result.impressionId) }
            return true
        }
    }

    private fun key(adUnitId: String?, position: Int) = "${adUnitId.orEmpty()}:$position"

    /** Atomically marks [impressionId] as having fired. Returns true only the first time, so callers
     * fire the impression (callback + server beacon) at most once per served ad. Blank ids (previews)
     * are never tracked and always return false. */
    fun markImpressionFired(impressionId: String): Boolean = synchronized(lock) {
        impressionId.isNotBlank() && firedImpressions.add(impressionId)
    }

    fun get(adUnitId: String?, position: Int): Value? =
        synchronized(lock) { entries[key(adUnitId, position)] }

    fun putFill(adUnitId: String?, position: Int, result: SimulaApiClient.NativeAdResult): Value.Fill {
        val fill = Value.Fill(result)
        synchronized(lock) { entries[key(adUnitId, position)] = fill }
        return fill
    }

    fun putNoFill(adUnitId: String?, position: Int) = synchronized(lock) {
        entries[key(adUnitId, position)] = Value.NoFill
    }

    fun invalidate(adUnitId: String?, position: Int) {
        // Drop the impression-id mark too so a deliberately-refreshed slot can fire again, and free the
        // retained WebView so the refreshed slot rebuilds instead of reattaching the stale creative.
        val removed = synchronized(lock) {
            (entries.remove(key(adUnitId, position)) as? Value.Fill)?.also {
                firedImpressions.remove(it.result.impressionId)
            }
        }
        removed?.let { NativeAdWebViewStore.evict(it.result.impressionId) }
    }

    fun invalidateAll() {
        synchronized(lock) {
            entries.clear()
            firedImpressions.clear()
        }
        NativeAdWebViewStore.evictAll()
    }
}
