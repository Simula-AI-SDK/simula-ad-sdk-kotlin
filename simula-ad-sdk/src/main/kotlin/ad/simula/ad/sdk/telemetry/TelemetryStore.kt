package ad.simula.ad.sdk.telemetry

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

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

/**
 * SQLite-backed store (WAL) replacing [SharedPrefsTelemetryStore]. SharedPreferences re-serializes the
 * **whole** buffer on every save and flushes through `QueuedWork.waitToFinish()` on background/process
 * death — an ANR risk under load (PRD). SQLite instead does **row-level** upsert/delete keyed by
 * `event_id`, has no `QueuedWork` flush path, and prunes anything older than [maxAgeMs] on load (built-in
 * 24h expiry). The [TelemetryStore] interface is unchanged, so [TelemetryManager] is untouched.
 *
 * Every operation is wrapped so a DB failure degrades to an empty load / no-op save and never throws
 * into the host. Access is serialized by [TelemetryManager]'s mutex (off the main thread); `SQLiteDatabase`
 * is itself internally synchronized.
 */
internal class SqliteTelemetryStore(
    context: Context,
    private val json: Json,
    private val maxAgeMs: Long = TimeUnit.HOURS.toMillis(24),
    private val clock: () -> Long = System::currentTimeMillis,
) : TelemetryStore {

    private val helper = Helper(context.applicationContext)

    init {
        // One-time drain of the legacy SharedPrefs buffer into SQLite (no-op once cleared).
        runCatching { migrateFromSharedPrefs(context.applicationContext) }
    }

    override fun load(): List<TelemetryEvent> = runCatching {
        val db = helper.writableDatabase
        // Built-in expiry: drop stale rows before reading (only recovered-from-disk rows can be stale;
        // the in-memory buffer re-supplies live events on the next save).
        db.delete(TABLE, "$COL_TS < ?", arrayOf((clock() - maxAgeMs).toString()))
        val out = ArrayList<TelemetryEvent>()
        db.query(TABLE, arrayOf(COL_JSON), null, null, null, null, "$COL_TS ASC").use { c ->
            val idx = c.getColumnIndexOrThrow(COL_JSON)
            while (c.moveToNext()) {
                val s = c.getString(idx) ?: continue
                runCatching { json.decodeFromString<TelemetryEvent>(s) }.getOrNull()?.let { out.add(it) }
            }
        }
        out
    }.getOrDefault(emptyList())

    override fun save(events: List<TelemetryEvent>) {
        runCatching {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                val keepIds = events.mapTo(HashSet()) { it.eventId }
                // Row-level delete of events removed since the last save (acked/flushed).
                val existing = ArrayList<String>()
                db.query(TABLE, arrayOf(COL_ID), null, null, null, null, null).use { c ->
                    val idx = c.getColumnIndexOrThrow(COL_ID)
                    while (c.moveToNext()) existing.add(c.getString(idx))
                }
                for (id in existing) if (id !in keepIds) db.delete(TABLE, "$COL_ID = ?", arrayOf(id))
                // Upsert the current events (per row — never a single whole-buffer blob).
                val values = ContentValues()
                for (e in events) {
                    values.clear()
                    values.put(COL_ID, e.eventId)
                    values.put(COL_TS, e.timestamp)
                    values.put(COL_JSON, json.encodeToString(e))
                    db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun migrateFromSharedPrefs(context: Context) {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(LEGACY_KEY, null) ?: return
        runCatching {
            val legacy = json.decodeFromString<List<TelemetryEvent>>(jsonStr)
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                val values = ContentValues()
                for (e in legacy) {
                    values.clear()
                    values.put(COL_ID, e.eventId)
                    values.put(COL_TS, e.timestamp)
                    values.put(COL_JSON, json.encodeToString(e))
                    // IGNORE (not REPLACE) so a migration can never clobber rows already in SQLite.
                    db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        prefs.edit().remove(LEGACY_KEY).apply()
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE " +
                    "($COL_ID TEXT PRIMARY KEY, $COL_TS INTEGER NOT NULL, $COL_JSON TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_ts ON $TABLE($COL_TS)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Telemetry is ephemeral diagnostics — a schema bump just drops the old table.
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    private companion object {
        const val DB_NAME = "simula_ad_sdk_telemetry.db"
        const val DB_VERSION = 1
        const val TABLE = "telemetry_events"
        const val COL_ID = "event_id"
        const val COL_TS = "ts"
        const val COL_JSON = "json"
        // Legacy SharedPrefs source for the one-time migration.
        const val LEGACY_PREFS = "simula_ad_sdk_telemetry_prefs"
        const val LEGACY_KEY = "pending_telemetry_events"
    }
}
