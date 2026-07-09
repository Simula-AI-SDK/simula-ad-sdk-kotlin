package ad.simula.ad.sdk.ads

import java.util.TimeZone

/**
 * Session-scoped cache for [SimulaAds.checkFrequencyCap]: remembers a `true` (cap reached) result
 * for the rest of the local day, resetting when local device time crosses midnight — matching the
 * PRD's "cache a true result for the rest of the session, unless local device time crosses midnight
 * (cap resets daily)".
 *
 * Only a `true` result is ever cached: a `false` (eligible) is never stored, since a user can become
 * capped later in the same session and every call already fails open to `false` on error. Keyed by
 * (adUnitId, ppid) via a structured [CacheKey] so a mid-session [SimulaAds.updatePrimaryUserID]
 * (login/logout) can't leak a prior user's cached cap, and no delimiter concatenation can make two
 * distinct pairs collide.
 *
 * Bounding: the cache holds only the CURRENT local day's capped keys. The first read/mark on a new
 * day clears the whole set (midnight reset), so entries never accumulate across days — memory is
 * bounded to a single day's distinct capped keys and self-resets, with no fixed cap that could evict
 * a still-valid same-day entry (which would violate the "rest of the day" guarantee).
 *
 * The "local day" is computed from device wall-clock time (no [java.time], which needs API 26+
 * desugaring; this SDK targets API 24+) via an injectable [nowMillis] so day-rollover is
 * unit-testable without waiting for real midnight.
 */
internal object FrequencyCapCache {

    private const val MILLIS_PER_DAY = 86_400_000L

    /** Local calendar day number for a given epoch millis, per the device's current time zone. */
    private fun localDay(nowMillis: Long): Long {
        val offsetMillis = TimeZone.getDefault().getOffset(nowMillis)
        return (nowMillis + offsetMillis) / MILLIS_PER_DAY
    }

    private data class CacheKey(val adUnitId: String, val ppid: String?)

    // Guarded by [lock]. Holds only [currentDay]'s capped keys; a change in the local day clears the
    // set (see [rolloverIfNeeded]).
    private val lock = Any()
    private var currentDay: Long = Long.MIN_VALUE
    private val cappedKeys = HashSet<CacheKey>()

    /** Returns `true` only if [adUnitId]/[ppid] was marked capped on the current local day. */
    fun isCapped(adUnitId: String, ppid: String?, nowMillis: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            val today = localDay(nowMillis)
            rolloverIfNeeded(today)
            // `today < currentDay` is a stale read (a request whose start time predates a rollover
            // another call already advanced): that day's cap has reset, so report not-capped.
            today == currentDay && cappedKeys.contains(CacheKey(adUnitId, ppid))
        }

    /** Marks [adUnitId]/[ppid] as capped for the rest of the current local day. */
    fun markCapped(adUnitId: String, ppid: String?, nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val today = localDay(nowMillis)
            rolloverIfNeeded(today)
            // Ignore a mark carrying an already-past day: it must neither resurrect a reset cap nor
            // (via a rewind) wipe the current day's still-valid entries.
            if (today == currentDay) cappedKeys.add(CacheKey(adUnitId, ppid))
        }
    }

    /**
     * Advances to a NEWER local day only, clearing the prior day's caps (the midnight reset). It
     * never rewinds: a late call carrying an older day (e.g. a check whose start time was captured
     * before midnight but that completes after) must not wipe the current day's valid entries.
     */
    private fun rolloverIfNeeded(today: Long) {
        if (today > currentDay) {
            currentDay = today
            cappedKeys.clear()
        }
    }

    /** Clears every cached entry. Exposed for tests. */
    internal fun clear() {
        synchronized(lock) {
            cappedKeys.clear()
            currentDay = Long.MIN_VALUE
        }
    }
}
