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
    fun `same-day capped entries are never evicted regardless of count`() {
        // The "cache true for the rest of the day" guarantee must hold no matter how many distinct
        // ad units are checked in a day — there is no fixed cap that could drop a valid entry.
        for (i in 0 until 500) {
            FrequencyCapCache.markCapped("unit_$i", "user_1", day1Noon)
        }
        assertTrue(FrequencyCapCache.isCapped("unit_0", "user_1", day1Noon))
        assertTrue(FrequencyCapCache.isCapped("unit_499", "user_1", day1Noon))
    }

    @Test
    fun `crossing midnight clears all cached caps`() {
        FrequencyCapCache.markCapped("unit_1", "user_1", day1Noon)
        FrequencyCapCache.markCapped("unit_2", "user_2", day1Noon)
        val nextDay = day1Noon + oneDayMillis
        // Any read/mark on the new day resets the whole set.
        assertFalse(FrequencyCapCache.isCapped("unit_1", "user_1", nextDay))
        assertFalse(FrequencyCapCache.isCapped("unit_2", "user_2", nextDay))
    }

    @Test
    fun `a late prior-day mark does not wipe current-day entries`() {
        val nextDay = day1Noon + oneDayMillis
        // A request on the new day establishes the current day and caps "current".
        FrequencyCapCache.markCapped("current", "user_1", nextDay)
        // A request that STARTED before midnight finishes now and marks with its prior-day start time;
        // it must neither rewind the day (wiping "current") nor resurrect the reset prior-day cap.
        FrequencyCapCache.markCapped("late", "user_1", day1Noon)
        assertTrue(FrequencyCapCache.isCapped("current", "user_1", nextDay))
        assertFalse(FrequencyCapCache.isCapped("late", "user_1", nextDay))
    }

    @Test
    fun `pipe characters in ids do not collide across pairs`() {
        // "foo" + "bar|baz" and "foo|bar" + "baz" must be distinct keys (a naive concatenation with
        // a '|' delimiter would collide them into "foo|bar|baz").
        FrequencyCapCache.markCapped("foo", "bar|baz", day1Noon)
        assertTrue(FrequencyCapCache.isCapped("foo", "bar|baz", day1Noon))
        assertFalse(FrequencyCapCache.isCapped("foo|bar", "baz", day1Noon))
    }
}
