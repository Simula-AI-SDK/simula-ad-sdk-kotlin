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
}
