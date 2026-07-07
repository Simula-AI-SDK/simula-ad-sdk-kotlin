package ad.simula.ad.sdk.ads

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [FrequencyCapCache]'s session-scoped caching: a `true` result sticks for the rest of
 * the local day and resets at local midnight; `false` is never cached; entries are keyed per
 * (adUnitId, ppid) so a different user id doesn't inherit another user's cached cap.
 */
class FrequencyCapCacheTest {

    @After
    fun tearDown() {
        FrequencyCapCache.clear()
    }

    private val day1Noon = 1_700_000_000_000L // arbitrary reference instant
    private val oneDayMillis = 86_400_000L

    @Test
    fun `uncached ad unit is not capped`() {
        assertFalse(FrequencyCapCache.isCapped("unit_1", "user_1", day1Noon))
    }

    @Test
    fun `marked capped is capped later the same day`() {
        FrequencyCapCache.markCapped("unit_1", "user_1", day1Noon)
        assertTrue(FrequencyCapCache.isCapped("unit_1", "user_1", day1Noon + 1_000))
    }

    @Test
    fun `capped resets after crossing a day boundary`() {
        FrequencyCapCache.markCapped("unit_1", "user_1", day1Noon)
        assertFalse(FrequencyCapCache.isCapped("unit_1", "user_1", day1Noon + oneDayMillis))
    }

    @Test
    fun `capped state is keyed per ppid`() {
        FrequencyCapCache.markCapped("unit_1", "user_1", day1Noon)
        assertFalse(FrequencyCapCache.isCapped("unit_1", "user_2", day1Noon))
        assertFalse(FrequencyCapCache.isCapped("unit_1", null, day1Noon))
    }

    @Test
    fun `capped state is keyed per ad unit`() {
        FrequencyCapCache.markCapped("unit_1", "user_1", day1Noon)
        assertFalse(FrequencyCapCache.isCapped("unit_2", "user_1", day1Noon))
    }

    @Test
    fun `cache is bounded and evicts the eldest entry`() {
        // Mark more than the cap (64) distinct ad units; the earliest inserted must be evicted so
        // the store can never grow without bound for the process lifetime.
        for (i in 0..64) {
            FrequencyCapCache.markCapped("unit_$i", "user_1", day1Noon)
        }
        assertFalse(FrequencyCapCache.isCapped("unit_0", "user_1", day1Noon))
        assertTrue(FrequencyCapCache.isCapped("unit_64", "user_1", day1Noon))
    }

    @Test
    fun `re-marking refreshes recency so a valid entry is not evicted early`() {
        FrequencyCapCache.markCapped("unit_0", "user_1", day1Noon)
        for (i in 1..63) FrequencyCapCache.markCapped("unit_$i", "user_1", day1Noon) // 64 entries
        // Re-mark the eldest so it moves back to the tail, then overflow by one.
        FrequencyCapCache.markCapped("unit_0", "user_1", day1Noon)
        FrequencyCapCache.markCapped("unit_64", "user_1", day1Noon) // 65 -> evicts the new eldest
        assertTrue(FrequencyCapCache.isCapped("unit_0", "user_1", day1Noon))
        assertFalse(FrequencyCapCache.isCapped("unit_1", "user_1", day1Noon))
    }

    @Test
    fun `stale entries do not evict a current-day entry`() {
        // A prior-day entry must not occupy a slot: fill the cap with fresh entries and the valid
        // ones must all survive (the stale one is pruned, not counted against the cap).
        FrequencyCapCache.markCapped("stale", "user_1", day1Noon)
        val nextDay = day1Noon + oneDayMillis
        for (i in 0 until 64) FrequencyCapCache.markCapped("unit_$i", "user_1", nextDay)
        assertFalse(FrequencyCapCache.isCapped("stale", "user_1", nextDay))
        assertTrue(FrequencyCapCache.isCapped("unit_0", "user_1", nextDay))
        assertTrue(FrequencyCapCache.isCapped("unit_63", "user_1", nextDay))
    }
}
