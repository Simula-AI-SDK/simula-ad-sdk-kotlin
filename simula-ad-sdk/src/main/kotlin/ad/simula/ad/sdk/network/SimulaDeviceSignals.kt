package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.core.SimulaScope
import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.os.StatFs
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/**
 * Device-context signals attached as individual `X-*` headers to every first-party Simula API +
 * telemetry request (merged in at [SimulaApiClient]'s header chokepoint):
 *
 * - `X-Timezone` — IANA time-zone id.
 * - `X-Storage-Free` — available bytes on the app's data volume (helpful to know if the user could
 *   even install a promoted app).
 * - `X-Memory-Free` — available RAM bytes.
 * - `X-Battery-Level` — 0–100 (omitted when unknown).
 * - `X-Battery-State` — `charging` | `full` | `unplugged` | `unknown`.
 * - `X-Volume` — 0–100 media-stream volume (ads tend to perform better with audio).
 * - `X-Ringer-Mode` — `normal` | `vibrate` | `silent`.
 *
 * Performance: the request path only ever reads a pre-built cached [Map] ([headers]) — no syscalls,
 * never blocks. The snapshot is computed off the main thread at [prime] and refreshed lazily on a
 * short TTL with a single-flight guard, so battery/volume/RAM/storage stay reasonably fresh without
 * touching the hot path. Mirrors [SimulaConnectionType] / [SimulaDeviceId].
 *
 * Crash-free: every device read is individually guarded and omitted on failure (no misleading
 * zeros), so a signal can never throw into the host or the request. No new permissions and no
 * third-party dependencies — all reads use permission-free platform APIs.
 */
internal object SimulaDeviceSignals {

    /** How long a computed snapshot is served before a background refresh is triggered. */
    private const val TTL_MS = 10_000L

    private val priming = AtomicBoolean(false)
    private val refreshing = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var snapshot: Map<String, String> = emptyMap()

    @Volatile
    private var computedAtMs: Long = 0L

    /**
     * Store the application context and compute the first snapshot off the main thread (idempotent).
     * Until it completes [headers] returns empty and the signals are simply omitted — the same
     * first-request contract as [SimulaDeviceId] / [SimulaConnectionType].
     */
    fun prime(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!priming.compareAndSet(false, true)) return
        SimulaScope.launch { refresh(app) }
    }

    /**
     * The current signals as request headers. O(1) read of the cached snapshot; if it is older than
     * [TTL_MS] a single background refresh is kicked off (never blocking the caller) so the *next*
     * request picks up fresher values.
     */
    fun headers(): Map<String, String> {
        val app = appContext
        if (app != null && System.currentTimeMillis() - computedAtMs > TTL_MS && refreshing.compareAndSet(false, true)) {
            SimulaScope.launch { refresh(app) }
        }
        return snapshot
    }

    /** Recompute the snapshot from live device state. Runs off the main thread (via [SimulaScope]). */
    private fun refresh(context: Context) {
        try {
            snapshot = buildHeaders(
                timezone = runCatching { TimeZone.getDefault().id }.getOrNull(),
                storageFreeBytes = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull(),
                memoryFreeBytes = runCatching {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    am?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo).availMem }
                }.getOrNull(),
                batteryLevel = runCatching {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                }.getOrNull(),
                batteryStatus = runCatching {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                }.getOrNull(),
                volumeCurrent = runCatching {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    audio?.getStreamVolume(AudioManager.STREAM_MUSIC)
                }.getOrNull(),
                volumeMax = runCatching {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                }.getOrNull(),
                ringerMode = runCatching {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    audio?.ringerMode
                }.getOrNull(),
            )
            computedAtMs = System.currentTimeMillis()
        } finally {
            refreshing.set(false)
        }
    }

    // ── Pure mappers (unit-testable without a live device) ────────────────────

    /** Media volume as a 0–100 percentage; null when unreadable or [max] is non-positive. */
    internal fun volumePercent(current: Int?, max: Int?): Int? {
        if (current == null || max == null || max <= 0) return null
        return (current.toLong() * 100 / max).toInt().coerceIn(0, 100)
    }

    /** Battery capacity clamped to 0–100; null when the platform reports it as unknown (`< 0`). */
    internal fun batteryPercent(raw: Int?): Int? = raw?.takeIf { it in 0..100 }

    /**
     * Maps a [BatteryManager.BATTERY_PROPERTY_STATUS] value to a stable label; null when unknown.
     *
     * `NOT_CHARGING` is deliberately distinct from `unplugged`: on Android it means the device is
     * still connected to power but not accepting charge (e.g. full on AC, or charging paused/limited
     * by the OS), so collapsing it into `unplugged` would misreport the power state to the backend.
     * Only `DISCHARGING` (running on battery) maps to `unplugged`.
     */
    internal fun batteryStateLabel(status: Int?): String? = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "unplugged"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "unknown"
        else -> null
    }

    /** Maps an [AudioManager] ringer-mode constant to a stable label; null when unknown. */
    internal fun ringerModeLabel(mode: Int?): String? = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_SILENT -> "silent"
        else -> null
    }

    /** Assembles the header map from raw signal values, omitting any that are unavailable. Pure. */
    internal fun buildHeaders(
        timezone: String?,
        storageFreeBytes: Long?,
        memoryFreeBytes: Long?,
        batteryLevel: Int?,
        batteryStatus: Int?,
        volumeCurrent: Int?,
        volumeMax: Int?,
        ringerMode: Int?,
    ): Map<String, String> = buildMap {
        timezone?.takeIf { it.isNotBlank() }?.let { put("X-Timezone", it) }
        storageFreeBytes?.takeIf { it >= 0 }?.let { put("X-Storage-Free", it.toString()) }
        memoryFreeBytes?.takeIf { it >= 0 }?.let { put("X-Memory-Free", it.toString()) }
        batteryPercent(batteryLevel)?.let { put("X-Battery-Level", it.toString()) }
        batteryStateLabel(batteryStatus)?.let { put("X-Battery-State", it) }
        volumePercent(volumeCurrent, volumeMax)?.let { put("X-Volume", it.toString()) }
        ringerModeLabel(ringerMode)?.let { put("X-Ringer-Mode", it) }
    }
}
