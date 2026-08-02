package ad.simula.ad.sdk.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier-1 tests for [AdBeaconQueue] — the durable impression/click beacon queue. Deterministic
 * virtual time + in-memory fakes, so delivery / drop / retry / dedup / recovery are exercised
 * without Android or the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdBeaconQueueTest {

    private class FakeStore(initial: List<PendingBeacon> = emptyList()) : BeaconStore {
        var data: List<PendingBeacon> = initial.toList()
        var saveCount = 0
        override fun load(): List<PendingBeacon> = data
        override fun save(queue: List<PendingBeacon>) { saveCount++; data = queue.toList() }
    }

    /** Programmable sender: per-`impressionId:action` status code, or a thrown connectivity error. */
    private class FakeSender : BeaconSender {
        val codes = mutableMapOf<String, Int>()
        val errors = mutableMapOf<String, Throwable>()
        val callCounts = mutableMapOf<String, Int>()
        val apiKeys = mutableMapOf<String, String>()
        private fun key(id: String, action: String) = "$id:$action"
        override suspend fun send(apiKey: String, impressionId: String, action: String): Int {
            val k = key(impressionId, action)
            callCounts[k] = (callCounts[k] ?: 0) + 1
            apiKeys[k] = apiKey
            errors[k]?.let { throw it }
            return codes[k] ?: 200
        }
    }

    @Test
    fun `a 2xx delivers the beacon and removes it`() = runTest {
        val store = FakeStore()
        val sender = FakeSender().apply { codes["imp:seen"] = 200 }
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.queue("key", "imp", "seen")
        advanceUntilIdle()

        assertTrue("delivered beacon must be dropped", store.data.isEmpty())
        assertEquals(1, sender.callCounts["imp:seen"])
    }

    @Test
    fun `a permanent 4xx drops the beacon without retry`() = runTest {
        val store = FakeStore()
        val sender = FakeSender().apply { codes["imp:click"] = 400 }
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.queue("key", "imp", "click")
        advanceUntilIdle()

        assertTrue("4xx (except 408/429) is permanent → drop", store.data.isEmpty())
        assertEquals(1, sender.callCounts["imp:click"])
    }

    @Test
    fun `a 5xx keeps the beacon and records the attempt for backoff`() = runTest {
        val store = FakeStore()
        val sender = FakeSender().apply { codes["imp:seen"] = 503 }
        val engine = AdBeaconQueue(store, sender, clock = { 1_000L }, scope = this)

        engine.queue("key", "imp", "seen")
        advanceUntilIdle()

        assertEquals(1, store.data.size)
        assertEquals(1, store.data[0].retryCount)
        assertEquals(1_000L, store.data[0].lastAttemptTimestamp)
        assertEquals(1, sender.callCounts["imp:seen"]) // not hammered
    }

    @Test
    fun `a connectivity failure keeps the beacon for retry`() = runTest {
        val store = FakeStore()
        val sender = FakeSender().apply { errors["imp:seen"] = RuntimeException("offline") }
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.queue("key", "imp", "seen")
        advanceUntilIdle()

        assertEquals(1, store.data.size)
    }

    @Test
    fun `a duplicate beacon is enqueued and sent only once`() = runTest {
        val store = FakeStore()
        val sender = FakeSender().apply { codes["imp:seen"] = 503 } // keep it queued
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.queue("key", "imp", "seen")
        engine.queue("key", "imp", "seen") // duplicate
        advanceUntilIdle()

        assertEquals("same (impressionId, action) deduped", 1, store.data.size)
    }

    @Test
    fun `distinct actions on the same impression are independent`() = runTest {
        val store = FakeStore()
        val sender = FakeSender().apply { codes["imp:seen"] = 200; codes["imp:click"] = 200 }
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.queue("key", "imp", "seen")
        engine.queue("key", "imp", "click")
        advanceUntilIdle()

        assertTrue(store.data.isEmpty())
        assertEquals(1, sender.callCounts["imp:seen"])
        assertEquals(1, sender.callCounts["imp:click"])
    }

    @Test
    fun `trigger drains a beacon left by a prior session`() = runTest {
        val store = FakeStore(
            listOf(PendingBeacon("imp", "seen", retryCount = 0, lastAttemptTimestamp = 0L, apiKey = "key")),
        )
        val sender = FakeSender().apply { codes["imp:seen"] = 200 }
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.trigger()
        advanceUntilIdle()

        assertEquals(1, sender.callCounts["imp:seen"])
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun `a backed-off beacon is skipped until its delay elapses, then retried`() = runTest {
        var now = 0L
        val store = FakeStore()
        val sender = FakeSender().apply { codes["imp:seen"] = 503 }
        val engine = AdBeaconQueue(store, sender, clock = { now }, scope = this)

        engine.queue("key", "imp", "seen")
        advanceUntilIdle()
        assertEquals(1, store.data[0].retryCount) // attempt at now=0; backoff(1)=5000ms

        sender.codes["imp:seen"] = 200
        now = 4_999
        engine.trigger()
        advanceUntilIdle()
        assertEquals("still backed off → not attempted", 1, sender.callCounts["imp:seen"])

        now = 5_000
        engine.trigger()
        advanceUntilIdle()
        assertEquals("now eligible → retried", 2, sender.callCounts["imp:seen"])
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun `a blank impressionId is ignored`() = runTest {
        val store = FakeStore()
        val sender = FakeSender()
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.queue("key", "", "seen")
        advanceUntilIdle()

        assertTrue(store.data.isEmpty())
        assertTrue(sender.callCounts.isEmpty())
    }

    @Test
    fun `the queue is capped and drops the oldest on overflow`() = runTest {
        // 5xx for everything so entries stay queued and accumulate to the cap.
        val store = FakeStore()
        val sender = FakeSender()
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        repeat(MAX_PENDING_BEACONS) { sender.codes["imp_$it:seen"] = 503 }
        repeat(MAX_PENDING_BEACONS) { engine.queue("key", "imp_$it", "seen") }
        advanceUntilIdle()
        assertEquals(MAX_PENDING_BEACONS, store.data.size)

        sender.codes["newest:seen"] = 503
        engine.queue("key", "newest", "seen")
        advanceUntilIdle()

        assertEquals("cap holds", MAX_PENDING_BEACONS, store.data.size)
        assertTrue("the newest entry is never the one dropped", store.data.any { it.impressionId == "newest" })
        assertTrue("the oldest was dropped", store.data.none { it.impressionId == "imp_0" })
    }

    @Test
    fun `a drain pass persists once, not per delivered beacon`() = runTest {
        val store = FakeStore((0 until 5).map { PendingBeacon("imp_$it", "seen", apiKey = "key") })
        val sender = FakeSender() // default 200 → everything delivers
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.trigger()
        advanceUntilIdle()

        assertTrue(store.data.isEmpty())
        assertEquals(
            "one batched persist for a 5-beacon drain pass (was one per removal — the O(n²) shape)",
            1,
            store.saveCount,
        )
    }

    @Test
    fun `each beacon is sent with the api key that enqueued it`() = runTest {
        val store = FakeStore()
        val sender = FakeSender()
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.queue("provider-key", "provider-imp", "seen")
        engine.queue("imperative-key", "imperative-imp", "seen")
        advanceUntilIdle()

        assertEquals("provider-key", sender.apiKeys["provider-imp:seen"])
        assertEquals("imperative-key", sender.apiKeys["imperative-imp:seen"])
    }

    @Test
    fun `same impression and action under different api keys remain distinct`() = runTest {
        val store = FakeStore()
        val sender = FakeSender().apply { codes["shared:seen"] = 503 }
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.queue("provider-key", "shared", "seen")
        engine.queue("imperative-key", "shared", "seen")
        advanceUntilIdle()

        assertEquals(2, store.data.size)
        assertEquals(2, store.data.map(PendingBeacon::persistenceKey).toSet().size)
    }
}
