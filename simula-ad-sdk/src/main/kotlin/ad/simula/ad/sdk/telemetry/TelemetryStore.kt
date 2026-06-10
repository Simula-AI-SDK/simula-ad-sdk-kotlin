package ad.simula.ad.sdk.telemetry

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the telemetry buffer so events — especially errors — survive process death.
 * Abstracted (like [ad.simula.ad.sdk.network.VerificationStore]) so the queue engine can
 * be unit-tested with an in-memory fake.
 */
internal interface TelemetryStore {
    fun load(): List<TelemetryEvent>
    fun save(events: List<TelemetryEvent>)
}

/** Real store: a single `SharedPreferences` entry holding the JSON-encoded buffer. */
internal class SharedPrefsTelemetryStore(
    private val context: Context,
    private val json: Json,
    private val prefsName: String = PREFS_NAME,
    private val key: String = KEY_BUFFER,
) : TelemetryStore {

    override fun load(): List<TelemetryEvent> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(key, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<TelemetryEvent>>(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun save(events: List<TelemetryEvent>) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (events.isEmpty()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, json.encodeToString(events)).apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "simula_ad_sdk_telemetry_prefs"
        const val KEY_BUFFER = "pending_telemetry_events"
    }
}
