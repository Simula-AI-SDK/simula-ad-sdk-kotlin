package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.network.SimulaApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Slot-identity tests for [NativeAdCache] — the preload-scoped keying that keeps same-position
 * preloaded slots (a host that never passes `position`, leaving every slot at the default 0) from
 * clobbering each other's entry and converging to one ad as rows recycle.
 *
 * JVM-only constraint: [NativeAdCache.invalidate] evicts removed FILLS through
 * [NativeAdWebViewStore], whose init needs a main Looper — so the invalidate tests here use
 * no-fill entries (which never reach the store) to exercise the same prefix-matching removal.
 * The cache is a process-wide object, so every test namespaces entries under a fresh UUID
 * adUnitId.
 */
class NativeAdCacheTest {

    private fun result(impressionId: String) = SimulaApiClient.NativeAdResult(
        impressionId = impressionId,
        adInserted = true,
        adFormat = "character_ad",
        iframeUrl = null,
        renderedHtml = "<html></html>",
    )

    /** The regression this keying exists for: two preloaded slots sharing (adUnitId, position)
     * must keep distinct entries — previously the second putFill overwrote the first. */
    @Test
    fun samePositionPreloadedSlotsKeepDistinctEntries() {
        val unit = UUID.randomUUID().toString()
        NativeAdCache.putFill(unit, 0, result("imp-a"), preloadedAdId = "preload-a")
        NativeAdCache.putFill(unit, 0, result("imp-b"), preloadedAdId = "preload-b")

        val a = NativeAdCache.get(unit, 0, "preload-a") as? NativeAdCache.Value.Fill
        val b = NativeAdCache.get(unit, 0, "preload-b") as? NativeAdCache.Value.Fill
        assertEquals("imp-a", a?.result?.impressionId)
        assertEquals("imp-b", b?.result?.impressionId)
        assertNull(
            "preload-scoped fills must not populate the un-scoped slot entry",
            NativeAdCache.get(unit, 0),
        )
    }

    /** A preloaded slot must replay only ITS OWN serve — never another slot's live fill at the
     * same position (and vice versa). */
    @Test
    fun scopedAndUnscopedLookupsAreIsolated() {
        val unit = UUID.randomUUID().toString()
        NativeAdCache.putFill(unit, 0, result("imp-live"))

        assertNull(
            "a preloaded slot must not replay another slot's live serve",
            NativeAdCache.get(unit, 0, "preload-x"),
        )
        val live = NativeAdCache.get(unit, 0) as? NativeAdCache.Value.Fill
        assertEquals("imp-live", live?.result?.impressionId)
    }

    /** A blank preloadedAdId (defensive: bridges normalize empty strings) addresses the plain
     * slot key, identical to passing null. */
    @Test
    fun emptyPreloadedAdIdFallsBackToSlotKey() {
        val unit = UUID.randomUUID().toString()
        NativeAdCache.putFill(unit, 0, result("imp-live"), preloadedAdId = "")

        val viaNull = NativeAdCache.get(unit, 0) as? NativeAdCache.Value.Fill
        val viaEmpty = NativeAdCache.get(unit, 0, "") as? NativeAdCache.Value.Fill
        assertEquals("imp-live", viaNull?.result?.impressionId)
        assertEquals("imp-live", viaEmpty?.result?.impressionId)
    }

    /** No-fill outcomes are preload-scoped too: one slot's no-fill must not collapse a sibling
     * slot at the same position. */
    @Test
    fun noFillIsScopedPerPreloadedSlot() {
        val unit = UUID.randomUUID().toString()
        NativeAdCache.putNoFill(unit, 0, preloadedAdId = "preload-a")
        NativeAdCache.putFill(unit, 0, result("imp-b"), preloadedAdId = "preload-b")

        assertEquals(NativeAdCache.Value.NoFill, NativeAdCache.get(unit, 0, "preload-a"))
        val b = NativeAdCache.get(unit, 0, "preload-b") as? NativeAdCache.Value.Fill
        assertEquals("imp-b", b?.result?.impressionId)
        assertNull(NativeAdCache.get(unit, 0))
    }

    /** invalidate(adUnitId, position) addresses the placement: it drops the un-scoped entry AND
     * every preload-scoped entry at that position, while other positions stay untouched.
     * (No-fill entries throughout — see the class doc for why fills can't be used here.) */
    @Test
    fun invalidateDropsScopedEntriesForThePosition() {
        val unit = UUID.randomUUID().toString()
        NativeAdCache.putNoFill(unit, 0)
        NativeAdCache.putNoFill(unit, 0, preloadedAdId = "preload-a")
        NativeAdCache.putNoFill(unit, 0, preloadedAdId = "preload-b")
        NativeAdCache.putNoFill(unit, 1, preloadedAdId = "preload-c")

        NativeAdCache.invalidate(unit, 0)

        assertNull(NativeAdCache.get(unit, 0))
        assertNull(NativeAdCache.get(unit, 0, "preload-a"))
        assertNull(NativeAdCache.get(unit, 0, "preload-b"))
        assertNotNull("other positions must stay untouched", NativeAdCache.get(unit, 1, "preload-c"))
    }

    /** The process-wide impression dedup is independent of the keying change: one fire per
     * served impression id, blank ids (previews) never tracked. */
    @Test
    fun markImpressionFiredDedupsPerImpressionId() {
        val impressionId = "imp-${UUID.randomUUID()}"
        assertTrue(NativeAdCache.markImpressionFired(impressionId))
        assertFalse(NativeAdCache.markImpressionFired(impressionId))
        assertFalse("blank ids (previews) are never tracked", NativeAdCache.markImpressionFired(""))
    }
}
