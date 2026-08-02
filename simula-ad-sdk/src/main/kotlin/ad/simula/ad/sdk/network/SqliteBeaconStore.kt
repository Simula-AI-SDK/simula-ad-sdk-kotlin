package ad.simula.ad.sdk.network

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * SQLite-backed [BeaconStore] (WAL) replacing the single-JSON-blob SharedPreferences store.
 * SharedPreferences re-serializes the **whole** queue on every save and flushes through
 * `QueuedWork.waitToFinish()` on background/process death — an ANR risk under a backlog (the
 * hazard that already moved telemetry to SQLite; see `SqliteTelemetryStore`). SQLite instead
 * does **row-level** upsert/delete keyed by `(api_key, impression_id, action)`, has no QueuedWork flush
 * path, and prunes rows older than [maxAgeMs] on load (a beacon older than the attribution
 * window is not worth retrying).
 *
 * One-time drain of the legacy SharedPreferences blob on first use, so beacons queued by an
 * older SDK version are not lost on upgrade. Every operation is wrapped so a DB failure
 * degrades to an empty load / no-op save and never throws into the host.
 */
internal class SqliteBeaconStore(
    context: Context,
    private val json: Json,
    private val fallbackApiKey: String,
    private val maxAgeMs: Long = TimeUnit.HOURS.toMillis(24),
    private val clock: () -> Long = System::currentTimeMillis,
) : BeaconStore {

    private val helper = Helper(context.applicationContext)

    init {
        runCatching { migrateFromSharedPrefs(context.applicationContext) }
    }

    override fun load(): List<PendingBeacon> = runCatching {
        val db = helper.writableDatabase
        // Built-in expiry: drop stale rows before reading (ts == 0 is exempt: pre-TTL rows).
        db.delete(TABLE, "$COL_TS > 0 AND $COL_TS < ?", arrayOf((clock() - maxAgeMs).toString()))
        val out = ArrayList<PendingBeacon>()
        db.query(TABLE, arrayOf(COL_API_KEY, COL_JSON, COL_TS), null, null, null, null, "$COL_TS ASC").use { c ->
            val apiKeyIdx = c.getColumnIndexOrThrow(COL_API_KEY)
            val jsonIdx = c.getColumnIndexOrThrow(COL_JSON)
            val tsIdx = c.getColumnIndexOrThrow(COL_TS)
            while (c.moveToNext()) {
                val s = c.getString(jsonIdx) ?: continue
                runCatching { json.decodeFromString<PendingBeacon>(s) }.getOrNull()?.let {
                    // Repairs rows written by the first SQLite migration, whose column had a TTL
                    // baseline but whose JSON still carried createdAt=0.
                    val storedApiKey = c.getString(apiKeyIdx).orEmpty().ifBlank { fallbackApiKey }
                    out.add(normalizeMigratedBeacon(it, c.getLong(tsIdx), storedApiKey))
                }
            }
        }
        out
    }.getOrDefault(emptyList())

    override fun save(queue: List<PendingBeacon>) {
        runCatching {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                // Row-level delete of entries removed since the last save (delivered/dropped).
                val keepKeys = queue.mapTo(HashSet(), PendingBeacon::persistenceKey)
                val existing = ArrayList<BeaconPersistenceKey>()
                db.query(TABLE, arrayOf(COL_API_KEY, COL_IMPRESSION, COL_ACTION), null, null, null, null, null).use { c ->
                    val kIdx = c.getColumnIndexOrThrow(COL_API_KEY)
                    val iIdx = c.getColumnIndexOrThrow(COL_IMPRESSION)
                    val aIdx = c.getColumnIndexOrThrow(COL_ACTION)
                    while (c.moveToNext()) {
                        existing.add(BeaconPersistenceKey(c.getString(kIdx), c.getString(iIdx), c.getString(aIdx)))
                    }
                }
                for (key in existing) {
                    if (key !in keepKeys) {
                        db.delete(
                            TABLE,
                            "$COL_API_KEY = ? AND $COL_IMPRESSION = ? AND $COL_ACTION = ?",
                            arrayOf(key.apiKey, key.impressionId, key.action),
                        )
                    }
                }
                // Upsert the current entries (per row — never a single whole-queue blob).
                val values = ContentValues()
                for (b in queue) {
                    values.clear()
                    values.put(COL_API_KEY, b.apiKey)
                    values.put(COL_IMPRESSION, b.impressionId)
                    values.put(COL_ACTION, b.action)
                    values.put(COL_TS, b.createdAt)
                    values.put(COL_JSON, json.encodeToString(b))
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
        val migrated = runCatching {
            val legacy = json.decodeFromString<List<PendingBeacon>>(jsonStr)
            val now = clock()
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                val values = ContentValues()
                for (b in legacy) {
                    val normalized = normalizeMigratedBeacon(b, now, fallbackApiKey)
                    values.clear()
                    values.put(COL_API_KEY, normalized.apiKey)
                    values.put(COL_IMPRESSION, normalized.impressionId)
                    values.put(COL_ACTION, normalized.action)
                    values.put(COL_TS, normalized.createdAt)
                    // Persist the normalized baseline in the payload too. A later load/save must
                    // not overwrite the TTL column with the legacy createdAt=0 value.
                    values.put(COL_JSON, json.encodeToString(normalized))
                    // IGNORE (not REPLACE) so a migration can never clobber rows already in SQLite.
                    val inserted = db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    if (!migrationRowPersisted(inserted) {
                            rowExists(db, normalized.persistenceKey())
                        }
                    ) {
                        error("legacy beacon row was not persisted")
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        // Only retire the legacy blob after a SUCCESSFUL migration — deleting it after a failed
        // one would lose still-undelivered billing beacons the retry could have recovered.
        if (migrated.isSuccess) {
            prefs.edit().remove(LEGACY_KEY).apply()
        }
    }

    private fun rowExists(db: SQLiteDatabase, key: BeaconPersistenceKey): Boolean =
        db.query(
            TABLE,
            arrayOf(COL_IMPRESSION),
            "$COL_API_KEY = ? AND $COL_IMPRESSION = ? AND $COL_ACTION = ?",
            arrayOf(key.apiKey, key.impressionId, key.action),
            null,
            null,
            null,
            "1",
        ).use { it.moveToFirst() }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE (" +
                    "$COL_API_KEY TEXT NOT NULL, $COL_IMPRESSION TEXT NOT NULL, $COL_ACTION TEXT NOT NULL, " +
                    "$COL_TS INTEGER NOT NULL DEFAULT 0, $COL_JSON TEXT NOT NULL, " +
                    "PRIMARY KEY ($BEACON_PRIMARY_KEY_SQL))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_ts ON $TABLE($COL_TS)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // Preserve every v1 row. Its key is recovered from JSON on load when present, or
                // from the current initialization key for truly legacy payloads; the next save
                // rewrites the temporary blank column with that resolved key.
                db.execSQL("ALTER TABLE $TABLE RENAME TO $LEGACY_TABLE")
                db.execSQL("DROP INDEX IF EXISTS idx_${TABLE}_ts")
                onCreate(db)
                db.execSQL(
                    "INSERT OR IGNORE INTO $TABLE " +
                        "($COL_API_KEY, $COL_IMPRESSION, $COL_ACTION, $COL_TS, $COL_JSON) " +
                        "SELECT '', $COL_IMPRESSION, $COL_ACTION, $COL_TS, $COL_JSON FROM $LEGACY_TABLE",
                )
                db.execSQL("DROP TABLE $LEGACY_TABLE")
            }
        }
    }

    private companion object {
        const val DB_NAME = "simula_ad_sdk_beacons.db"
        const val DB_VERSION = 2
        const val TABLE = "pending_beacons"
        const val LEGACY_TABLE = "pending_beacons_v1"
        const val COL_API_KEY = "api_key"
        const val COL_IMPRESSION = "impression_id"
        const val COL_ACTION = "action"
        const val COL_TS = "created_ts"
        const val COL_JSON = "json"
        // Legacy SharedPrefs source for the one-time migration.
        const val LEGACY_PREFS = "simula_ad_sdk_beacon_prefs"
        const val LEGACY_KEY = "pending_beacons"
    }
}
