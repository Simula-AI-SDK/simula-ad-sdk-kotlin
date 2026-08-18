package ad.simula.ad.sdk.network

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

/** One durable queue row. [key] is the stable action key; [rowId] identifies its exact revision. */
internal data class DurableQueueRow(
    val key: String,
    val rowId: String,
    val createdAt: Long,
    val payload: String,
)

internal sealed interface DurableLoadResult<out T> {
    data class Loaded<T>(val value: T) : DurableLoadResult<T>
    data object Failed : DurableLoadResult<Nothing>
}

internal enum class DurableMutationResult {
    Applied,
    NoMatch,
    Failed,
}

internal const val DEFAULT_MAX_PENDING_ENQUEUES = 100

/** Storage retries start small but cap so a broken database never creates a hot loop. */
internal fun durableMutationBackoffMs(failureCount: Int): Long {
    if (failureCount <= 0) return 100L
    val shift = (failureCount - 1).coerceAtMost(6)
    return minOf(100L shl shift, 5_000L)
}

/** Small seam around SQLite so migration/reconciliation stays deterministic in JVM tests. */
internal interface DurableQueueRows {
    fun load(): DurableLoadResult<List<DurableQueueRow>>
    fun upsert(row: DurableQueueRow): DurableMutationResult
    fun upsertAll(rows: List<DurableQueueRow>): DurableMutationResult
    fun insertIfAbsent(row: DurableQueueRow): DurableMutationResult
    fun insertAllIfAbsent(rows: List<DurableQueueRow>): DurableMutationResult
    fun replaceIfRevision(
        key: String,
        expectedRowId: String,
        row: DurableQueueRow,
    ): DurableMutationResult
    fun removeIfRevision(key: String, expectedRowId: String): DurableMutationResult
    fun remove(key: String): DurableMutationResult
    fun replaceAll(rows: List<DurableQueueRow>): DurableMutationResult
    fun import(rows: List<DurableQueueRow>): DurableMutationResult
}

/** Legacy single-value source. It is cleared only after SQLite confirms the import transaction. */
internal interface LegacyQueueSource {
    fun read(): String?
    fun clear(): Boolean
}

internal class SharedPrefsLegacyQueueSource(
    context: Context,
    prefsName: String,
    private val key: String,
) : LegacyQueueSource {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun read(): String? = runCatching { prefs.getString(key, null) }.getOrNull()

    override fun clear(): Boolean = runCatching { prefs.edit().remove(key).commit() }.getOrDefault(false)
}

/**
 * WAL SQLite row storage shared by the billing and reward queues. Billing rows are never evicted by
 * age/count/bytes: only an explicit acknowledged delivery/permanent failure may remove one.
 */
internal class SqliteDurableQueueRows(
    context: Context,
    databaseName: String,
    tableName: String,
) : DurableQueueRows {
    private val table = tableName
    private val helper = Helper(context.applicationContext, databaseName, tableName)

    override fun load(): DurableLoadResult<List<DurableQueueRow>> = runCatching {
        val db = helper.writableDatabase
        val rows = ArrayList<DurableQueueRow>()
        db.query(
            table,
            arrayOf(COL_KEY, COL_ROW_ID, COL_CREATED_AT, COL_PAYLOAD),
            null,
            null,
            null,
            null,
            "$COL_CREATED_AT ASC, $COL_KEY ASC",
        ).use { cursor ->
            val keyIndex = cursor.getColumnIndexOrThrow(COL_KEY)
            val idIndex = cursor.getColumnIndexOrThrow(COL_ROW_ID)
            val createdIndex = cursor.getColumnIndexOrThrow(COL_CREATED_AT)
            val payloadIndex = cursor.getColumnIndexOrThrow(COL_PAYLOAD)
            while (cursor.moveToNext()) {
                val key = cursor.getString(keyIndex) ?: return@runCatching DurableLoadResult.Failed
                val rowId = cursor.getString(idIndex) ?: return@runCatching DurableLoadResult.Failed
                val payload = cursor.getString(payloadIndex) ?: return@runCatching DurableLoadResult.Failed
                rows += DurableQueueRow(key, rowId, cursor.getLong(createdIndex), payload)
            }
        }
        DurableLoadResult.Loaded(rows)
    }.getOrDefault(DurableLoadResult.Failed)

    override fun upsert(row: DurableQueueRow): DurableMutationResult = transaction { db ->
        insert(db, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun upsertAll(rows: List<DurableQueueRow>): DurableMutationResult = transaction { db ->
        rows.forEach { insert(db, it, SQLiteDatabase.CONFLICT_REPLACE) }
        if (rows.isEmpty()) DurableMutationResult.NoMatch else DurableMutationResult.Applied
    }

    override fun insertIfAbsent(row: DurableQueueRow): DurableMutationResult = transaction { db ->
        insert(db, row, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun insertAllIfAbsent(rows: List<DurableQueueRow>): DurableMutationResult = transaction { db ->
        var applied = false
        for (row in rows) {
            if (insert(db, row, SQLiteDatabase.CONFLICT_IGNORE) == DurableMutationResult.Applied) applied = true
        }
        if (applied) DurableMutationResult.Applied else DurableMutationResult.NoMatch
    }

    override fun replaceIfRevision(
        key: String,
        expectedRowId: String,
        row: DurableQueueRow,
    ): DurableMutationResult = transaction { db ->
        val affected = db.update(
            table,
            values(row),
            "$COL_KEY = ? AND $COL_ROW_ID = ?",
            arrayOf(key, expectedRowId),
        )
        if (affected > 0) DurableMutationResult.Applied else DurableMutationResult.NoMatch
    }

    override fun removeIfRevision(key: String, expectedRowId: String): DurableMutationResult =
        transaction { db ->
            val affected = db.delete(
                table,
                "$COL_KEY = ? AND $COL_ROW_ID = ?",
                arrayOf(key, expectedRowId),
            )
            if (affected > 0) DurableMutationResult.Applied else DurableMutationResult.NoMatch
        }

    override fun remove(key: String): DurableMutationResult = transaction { db ->
        val affected = db.delete(table, "$COL_KEY = ?", arrayOf(key))
        if (affected > 0) DurableMutationResult.Applied else DurableMutationResult.NoMatch
    }

    override fun replaceAll(rows: List<DurableQueueRow>): DurableMutationResult = transaction { db ->
        db.delete(table, null, null)
        rows.forEach { insert(db, it, SQLiteDatabase.CONFLICT_REPLACE) }
        DurableMutationResult.Applied
    }

    override fun import(rows: List<DurableQueueRow>): DurableMutationResult = transaction { db ->
        rows.forEach { insert(db, it, SQLiteDatabase.CONFLICT_IGNORE) }
        DurableMutationResult.Applied
    }

    private fun transaction(block: (SQLiteDatabase) -> DurableMutationResult): DurableMutationResult = runCatching {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }.getOrDefault(DurableMutationResult.Failed)

    private fun insert(db: SQLiteDatabase, row: DurableQueueRow, conflict: Int): DurableMutationResult {
        val inserted = db.insertWithOnConflict(table, null, values(row), conflict)
        if (inserted != -1L) return DurableMutationResult.Applied
        // CONFLICT_IGNORE is successful only when the stable key already exists. Any other -1
        // (including disk-full/I/O failure) must fail the transaction so migration keeps prefs.
        val existing = if (conflict == SQLiteDatabase.CONFLICT_IGNORE) {
            db.query(table, arrayOf(COL_KEY), "$COL_KEY = ?", arrayOf(row.key), null, null, null)
                .use { it.moveToFirst() }
        } else {
            false
        }
        if (!existing) throw SQLiteException("Unable to persist durable queue row")
        return DurableMutationResult.NoMatch
    }

    private fun values(row: DurableQueueRow) = ContentValues().apply {
        put(COL_KEY, row.key)
        put(COL_ROW_ID, row.rowId)
        put(COL_CREATED_AT, row.createdAt)
        put(COL_PAYLOAD, row.payload)
    }

    private class Helper(context: Context, databaseName: String, private val table: String) :
        SQLiteOpenHelper(context, databaseName, null, DB_VERSION) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $table " +
                    "($COL_KEY TEXT PRIMARY KEY, $COL_ROW_ID TEXT NOT NULL, " +
                    "$COL_CREATED_AT INTEGER NOT NULL, $COL_PAYLOAD TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_${table}_created ON $table($COL_CREATED_AT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw SQLiteException("Unsupported durable queue schema upgrade: $oldVersion to $newVersion")
        }
    }

    private companion object {
        const val DB_VERSION = 1
        const val COL_KEY = "action_key"
        const val COL_ROW_ID = "row_id"
        const val COL_CREATED_AT = "created_at"
        const val COL_PAYLOAD = "payload"
    }
}
