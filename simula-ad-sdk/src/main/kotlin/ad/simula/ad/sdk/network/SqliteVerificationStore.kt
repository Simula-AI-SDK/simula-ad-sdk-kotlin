package ad.simula.ad.sdk.network

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * SQLite-backed [VerificationStore] (WAL) replacing the single-JSON-blob SharedPreferences
 * store — the same migration the beacon queue and telemetry already made (QueuedWork /
 * whole-blob-reserialize is an ANR risk under a backlog; row-level SQLite has neither).
 * Prunes rows older than [maxAgeMs] on load: a verification that old is outside any useful
 * attribution window and not worth retrying.
 *
 * One-time drain of the legacy SharedPreferences blob on first use, so verifications queued by
 * an older SDK version are not lost on upgrade. Every operation is wrapped so a DB failure
 * degrades to an empty load / no-op save and never throws into the host.
 */
internal class SqliteVerificationStore(
    context: Context,
    private val json: Json,
    private val maxAgeMs: Long = TimeUnit.HOURS.toMillis(24),
    private val clock: () -> Long = System::currentTimeMillis,
) : VerificationStore {

    private val helper = Helper(context.applicationContext)

    init {
        runCatching { migrateFromSharedPrefs(context.applicationContext) }
    }

    override fun load(): List<PendingVerification> = runCatching {
        val db = helper.writableDatabase
        db.delete(TABLE, "$COL_TS > 0 AND $COL_TS < ?", arrayOf((clock() - maxAgeMs).toString()))
        val out = ArrayList<PendingVerification>()
        db.query(TABLE, arrayOf(COL_JSON), null, null, null, null, "$COL_TS ASC").use { c ->
            val idx = c.getColumnIndexOrThrow(COL_JSON)
            while (c.moveToNext()) {
                val s = c.getString(idx) ?: continue
                runCatching { json.decodeFromString<PendingVerification>(s) }.getOrNull()?.let { out.add(it) }
            }
        }
        out
    }.getOrDefault(emptyList())

    override fun save(queue: List<PendingVerification>) {
        runCatching {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                val keepIds = queue.mapTo(HashSet()) { it.serveId }
                val existing = ArrayList<String>()
                db.query(TABLE, arrayOf(COL_SERVE), null, null, null, null, null).use { c ->
                    val idx = c.getColumnIndexOrThrow(COL_SERVE)
                    while (c.moveToNext()) existing.add(c.getString(idx))
                }
                for (id in existing) if (id !in keepIds) db.delete(TABLE, "$COL_SERVE = ?", arrayOf(id))
                val values = ContentValues()
                for (v in queue) {
                    values.clear()
                    values.put(COL_SERVE, v.serveId)
                    values.put(COL_TS, v.createdAt)
                    values.put(COL_JSON, json.encodeToString(v))
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
            val legacy = json.decodeFromString<List<PendingVerification>>(jsonStr)
            val now = clock()
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                val values = ContentValues()
                for (v in legacy) {
                    values.clear()
                    values.put(COL_SERVE, v.serveId)
                    // Legacy rows carry no createdAt — TTL baseline = last attempt (or now when
                    // never attempted), so genuinely old rows expire on the first load.
                    values.put(COL_TS, if (v.createdAt > 0) v.createdAt else (v.lastAttemptTimestamp.takeIf { it > 0 } ?: now))
                    values.put(COL_JSON, json.encodeToString(v))
                    db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        // Only retire the legacy blob after a SUCCESSFUL migration — deleting it after a failed
        // one would lose pending reward verifications the retry could have recovered.
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
                "CREATE TABLE IF NOT EXISTS $TABLE " +
                    "($COL_SERVE TEXT PRIMARY KEY, $COL_TS INTEGER NOT NULL DEFAULT 0, $COL_JSON TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_ts ON $TABLE($COL_TS)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Reward verifications are best-effort — a schema bump drops the old table rather
            // than risking a botched in-place migration.
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    private companion object {
        const val DB_NAME = "simula_ad_sdk_reward_verifications.db"
        const val DB_VERSION = 1
        const val TABLE = "pending_verifications"
        const val COL_SERVE = "serve_id"
        const val COL_TS = "created_ts"
        const val COL_JSON = "json"
        // Legacy SharedPrefs source for the one-time migration.
        const val LEGACY_PREFS = "simula_ad_sdk_verification_prefs"
        const val LEGACY_KEY = "pending_reward_verifications"
    }
}
