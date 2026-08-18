package ad.simula.ad.sdk.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableQueueStoreTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun <T> loaded(result: DurableLoadResult<T>): T = when (result) {
        is DurableLoadResult.Loaded -> result.value
        DurableLoadResult.Failed -> throw AssertionError("Expected durable load to succeed")
    }

    private class FakeLegacy(private val encoded: String?) : LegacyQueueSource {
        var clearCount = 0
        override fun read(): String? = encoded
        override fun clear(): Boolean {
            clearCount++
            return true
        }
    }

    private class FakeRows(var importSucceeds: Boolean = true) : DurableQueueRows {
        val data = linkedMapOf<String, DurableQueueRow>()
        var replaceAllCount = 0
        var failLoads = false

        override fun load(): DurableLoadResult<List<DurableQueueRow>> = if (failLoads) {
            DurableLoadResult.Failed
        } else {
            DurableLoadResult.Loaded(data.values.toList())
        }
        override fun upsert(row: DurableQueueRow): DurableMutationResult {
            data[row.key] = row
            return DurableMutationResult.Applied
        }
        override fun upsertAll(rows: List<DurableQueueRow>): DurableMutationResult {
            rows.forEach { data[it.key] = it }
            return if (rows.isEmpty()) DurableMutationResult.NoMatch else DurableMutationResult.Applied
        }
        override fun insertIfAbsent(row: DurableQueueRow): DurableMutationResult {
            val previous = data.putIfAbsent(row.key, row)
            return if (previous == null) DurableMutationResult.Applied else DurableMutationResult.NoMatch
        }
        override fun insertAllIfAbsent(rows: List<DurableQueueRow>): DurableMutationResult {
            var applied = false
            rows.forEach {
                if (data.putIfAbsent(it.key, it) == null) applied = true
            }
            return if (applied) DurableMutationResult.Applied else DurableMutationResult.NoMatch
        }
        override fun replaceIfRevision(
            key: String,
            expectedRowId: String,
            row: DurableQueueRow,
        ): DurableMutationResult {
            if (data[key]?.rowId != expectedRowId) return DurableMutationResult.NoMatch
            data[key] = row
            return DurableMutationResult.Applied
        }
        override fun removeIfRevision(key: String, expectedRowId: String): DurableMutationResult {
            if (data[key]?.rowId != expectedRowId) return DurableMutationResult.NoMatch
            data.remove(key)
            return DurableMutationResult.Applied
        }
        override fun remove(key: String): DurableMutationResult {
            return if (data.remove(key) != null) DurableMutationResult.Applied else DurableMutationResult.NoMatch
        }
        override fun replaceAll(rows: List<DurableQueueRow>): DurableMutationResult {
            replaceAllCount++
            data.clear()
            rows.forEach { data[it.key] = it }
            return DurableMutationResult.Applied
        }
        override fun import(rows: List<DurableQueueRow>): DurableMutationResult {
            if (!importSucceeds) return DurableMutationResult.Failed
            rows.forEach { data.putIfAbsent(it.key, it) }
            return DurableMutationResult.Applied
        }
    }

    @Test
    fun `beacon migration imports exact legacy payload before clearing preferences`() {
        val legacyPayload = """[{"impressionId":"imp","action":"seen","retryCount":1,"lastAttemptTimestamp":42}]"""
        val legacy = FakeLegacy(legacyPayload)
        val rows = FakeRows()

        val store = SqliteBeaconStore(rows, legacy, json, clock = { 123L })

        assertEquals("simula_ad_sdk_beacon_prefs", SqliteBeaconStore.LEGACY_PREFS)
        assertEquals("pending_beacons", SqliteBeaconStore.LEGACY_KEY)
        assertEquals(1, legacy.clearCount)
        assertEquals(1, rows.data.size)
        val row = rows.data.getValue(beaconActionKey("imp", "seen"))
        assertEquals(123L, row.createdAt)
        assertEquals("imp", loaded(store.load()).single().impressionId)
    }

    @Test
    fun `failed beacon import retains legacy preferences`() {
        val legacy = FakeLegacy(json.encodeToString(listOf(PendingBeacon("imp", "seen"))))
        val rows = FakeRows(importSucceeds = false)

        SqliteBeaconStore(rows, legacy, json, clock = { 123L })

        assertEquals(0, legacy.clearCount)
        assertTrue(rows.data.isEmpty())
    }

    @Test
    fun `beacon revisions use row operations and preserve a newer metadata revision`() {
        val rows = FakeRows()
        val store = SqliteBeaconStore(rows, FakeLegacy(null), json, clock = { 10L })
        val first = PendingBeacon("imp", "seen", metadata = mapOf("a" to "old"), createdTimestamp = 10L)
        store.upsert(first)
        val replacement = first.copy(rowId = "revision-2", metadata = mapOf("a" to "new"))

        assertEquals(DurableMutationResult.Applied, store.replaceIfRevision(first, replacement))
        assertEquals(DurableMutationResult.NoMatch, store.remove(first))

        assertEquals(0, rows.replaceAllCount)
        val loaded = loaded(store.load())
        assertEquals("revision-2", loaded.single().rowId)
        assertNotEquals(first.rowId, loaded.single().rowId)
    }

    @Test
    fun `reward migration is keyed by serve id and clears only after import success`() {
        val pending = PendingVerification("serve", "session", 3.0, 0, 0L, "unit")
        val legacy = FakeLegacy(json.encodeToString(listOf(pending)))
        val rows = FakeRows()

        val store = SqliteVerificationStore(rows, legacy, json, clock = { 456L })

        assertEquals("simula_ad_sdk_verification_prefs", SqliteVerificationStore.LEGACY_PREFS)
        assertEquals("pending_reward_verifications", SqliteVerificationStore.LEGACY_KEY)
        assertEquals(1, legacy.clearCount)
        assertEquals(setOf("serve"), rows.data.keys)
        assertEquals(456L, rows.data.getValue("serve").createdAt)
        assertEquals("session", loaded(store.load()).single().sessionId)
    }

    @Test
    fun `failed reward import retains legacy preferences`() {
        val pending = PendingVerification("serve", "session", 3.0, 0, 0L)
        val legacy = FakeLegacy(json.encodeToString(listOf(pending)))

        SqliteVerificationStore(FakeRows(importSucceeds = false), legacy, json, clock = { 456L })

        assertFalse(legacy.clearCount > 0)
    }

    @Test
    fun `malformed beacon payload fails the whole load and remains durable`() {
        val rows = FakeRows()
        val bad = DurableQueueRow("bad", "revision", 1L, "{not-json")
        rows.data[bad.key] = bad
        val store = SqliteBeaconStore(rows, FakeLegacy(null), json, clock = { 10L })

        assertEquals(DurableLoadResult.Failed, store.load())
        assertEquals(bad, rows.data[bad.key])
    }

    @Test
    fun `raw row load failure propagates through reward store`() {
        val rows = FakeRows().apply { failLoads = true }
        val store = SqliteVerificationStore(rows, FakeLegacy(null), json, clock = { 10L })

        assertEquals(DurableLoadResult.Failed, store.load())
    }

    @Test
    fun `durable mutation retry delay is exponential and capped`() {
        assertEquals(100L, durableMutationBackoffMs(1))
        assertEquals(200L, durableMutationBackoffMs(2))
        assertEquals(5_000L, durableMutationBackoffMs(20))
    }
}
