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
 * does **row-level** upsert/delete keyed by `(impression_id, action)`, has no QueuedWork flush
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
        db.query(TABLE, arrayOf(COL_JSON), null, null, null, null, "$COL_TS ASC").use { c ->
            val idx = c.getColumnIndexOrThrow(COL_JSON)
            while (c.moveToNext()) {
                val s = c.getString(idx) ?: continue
                runCatching { json.decodeFromString<PendingBeacon>(s) }.getOrNull()?.let { out.add(it) }
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
                val keepKeys = queue.mapTo(HashSet()) { it.impressionId to it.action }
                val existing = ArrayList<Pair<String, String>>()
                db.query(TABLE, arrayOf(COL_IMPRESSION, COL_ACTION), null, null, null, null, null).use { c ->
                    val iIdx = c.getColumnIndexOrThrow(COL_IMPRESSION)
                    val aIdx = c.getColumnIndexOrThrow(COL_ACTION)
                    while (c.moveToNext()) existing.add(c.getString(iIdx) to c.getString(aIdx))
                }
                for ((imp, act) in existing) {
                    if ((imp to act) !in keepKeys) {
                        db.delete(TABLE, "$COL_IMPRESSION = ? AND $COL_ACTION = ?", arrayOf(imp, act))
                    }
                }
                // Upsert the current entries (per row — never a single whole-queue blob).
                val values = ContentValues()
                for (b in queue) {
                    values.clear()
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
                    values.clear()
                    values.put(COL_IMPRESSION, b.impressionId)
                    values.put(COL_ACTION, b.action)
                    // Legacy rows carry no createdAt — give them a TTL baseline of their last
                    // attempt (falling back to now when never attempted), so genuinely old
                    // rows expire on the first load instead of retrying forever.
                    values.put(COL_TS, if (b.createdAt > 0) b.createdAt else (b.lastAttemptTimestamp.takeIf { it > 0 } ?: now))
                    values.put(COL_JSON, json.encodeToString(b))
                    // IGNORE (not REPLACE) so a migration can never clobber rows already in SQLite.
                    db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
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

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE (" +
                    "$COL_IMPRESSION TEXT NOT NULL, $COL_ACTION TEXT NOT NULL, " +
                    "$COL_TS INTEGER NOT NULL DEFAULT 0, $COL_JSON TEXT NOT NULL, " +
                    "PRIMARY KEY ($COL_IMPRESSION, $COL_ACTION))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_ts ON $TABLE($COL_TS)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Billing beacons are best-effort — a schema bump drops the old table rather than
            // risking a botched in-place migration of money-bearing rows.
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    private companion object {
        const val DB_NAME = "simula_ad_sdk_beacons.db"
        const val DB_VERSION = 1
        const val TABLE = "pending_beacons"
        const val COL_IMPRESSION = "impression_id"
        const val COL_ACTION = "action"
        const val COL_TS = "created_ts"
        const val COL_JSON = "json"
        // Legacy SharedPrefs source for the one-time migration.
        const val LEGACY_PREFS = "simula_ad_sdk_beacon_prefs"
        const val LEGACY_KEY = "pending_beacons"
    }
}
