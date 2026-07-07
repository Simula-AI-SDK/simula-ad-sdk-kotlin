package ad.simula.ad.sdk.ads

import java.util.TimeZone

/**
 * Session-scoped cache for [SimulaAds.checkFrequencyCap]: caches a `true` (cap reached) result
 * for the rest of the local day, unless local device time crosses midnight — matching the PRD's
 * "cache a true result for the rest of the session, unless local device time crosses midnight
 * (cap resets daily)".
 *
 * Only a `true` result is ever cached: a `false` (eligible) is never stored, since a user can
 * become capped later in the same session and every call already fails open to `false` on error.
 * Keyed by `adUnitId|ppid` so a mid-session [SimulaAds.updatePrimaryUserID] (login/logout) can't
 * leak a prior user's cached cap onto the new identity.
 *
 * The "local day" is computed from device wall-clock time (no [java.time], which needs API 26+
 * desugaring; this SDK targets API 24+) via an injectable [nowMillis] so day-rollover is
 * unit-testable without waiting for real midnight.
 */
internal object FrequencyCapCache {

    private const val MILLIS_PER_DAY = 86_400_000L

    /** Upper bound on distinct (adUnitId, ppid) entries retained, so a host that checks many ad
     *  units / users across a long-lived process can't grow this without bound. Eldest-accessed
     *  entries evict first — mirrors the SDK's other bounded caches (MAX_AD_CACHE_ENTRIES). */
    private const val MAX_ENTRIES = 64

    /** Local calendar day number for a given epoch millis, per the device's current time zone. */
    private fun localDay(nowMillis: Long): Long {
        val offsetMillis = TimeZone.getDefault().getOffset(nowMillis)
        return (nowMillis + offsetMillis) / MILLIS_PER_DAY
    }

    private fun key(adUnitId: String, ppid: String?): String = "$adUnitId|${ppid.orEmpty()}"

    // key -> local day the `true` result was cached on. Recency-ordered (eldest first): re-marking a
    // key moves it to the tail, so eviction never drops a freshly re-marked current-day entry. Stale
    // (prior-day) entries are removed on read and pruned on write, so they can't consume a slot and
    // push out a valid entry. Guarded by [lock] for cross-thread use (isCapped/markCapped run on
    // arbitrary caller threads).
    private val lock = Any()
    private val cappedDays = LinkedHashMap<String, Long>()

    /** Returns `true` only if [adUnitId]/[ppid] was marked capped on the current local day. */
    fun isCapped(adUnitId: String, ppid: String?, nowMillis: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            val k = key(adUnitId, ppid)
            val cachedDay = cappedDays[k] ?: return@synchronized false
            if (cachedDay == localDay(nowMillis)) {
                true
            } else {
                // Prior day: the cap has reset. Drop the stale entry so it can't occupy a slot.
                cappedDays.remove(k)
                false
            }
        }

    /** Marks [adUnitId]/[ppid] as capped for the rest of the current local day. */
    fun markCapped(adUnitId: String, ppid: String?, nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val today = localDay(nowMillis)
            val k = key(adUnitId, ppid)
            // Prune prior-day entries up front so stale ones never consume a slot.
            val it = cappedDays.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value != today) it.remove()
            }
            // Refresh recency: remove then re-insert so this key moves to the tail (most recent).
            cappedDays.remove(k)
            cappedDays[k] = today
            // Evict the eldest (least-recently marked) while over the cap.
            while (cappedDays.size > MAX_ENTRIES) {
                cappedDays.remove(cappedDays.keys.iterator().next())
            }
        }
    }

    /** Clears every cached entry. Exposed for tests. */
    internal fun clear() {
        synchronized(lock) { cappedDays.clear() }
    }
}
