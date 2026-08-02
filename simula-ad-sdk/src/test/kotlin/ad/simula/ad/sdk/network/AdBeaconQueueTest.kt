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
        override fun load(): List<PendingBeacon> = data
        override fun save(queue: List<PendingBeacon>) { data = queue.toList() }
    }

    /** Programmable sender: per-`impressionId:action` status code, or a thrown connectivity error. */
    private class FakeSender : BeaconSender {
        val codes = mutableMapOf<String, Int>()
        val errors = mutableMapOf<String, Throwable>()
        val callCounts = mutableMapOf<String, Int>()
        private fun key(id: String, action: String) = "$id:$action"
        override suspend fun send(impressionId: String, action: String): Int {
            val k = key(impressionId, action)
            callCounts[k] = (callCounts[k] ?: 0) + 1
            errors[k]?.let { throw it }
            return codes[k] ?: 200
        }
    }

    @Test
    fun `a 2xx delivers the beacon and removes it`() = runTest {
        val store = FakeStore()
        val sender = FakeSender().apply { codes["imp:seen"] = 200 }
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.queue("imp", "seen")
        advanceUntilIdle()

        assertTrue("delivered beacon must be dropped", store.data.isEmpty())
        assertEquals(1, sender.callCounts["imp:seen"])
    }

    @Test
    fun `a permanent 4xx drops the beacon without retry`() = runTest {
        val store = FakeStore()
        val sender = FakeSender().apply { codes["imp:click"] = 400 }
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.queue("imp", "click")
        advanceUntilIdle()

        assertTrue("4xx (except 408/429) is permanent → drop", store.data.isEmpty())
        assertEquals(1, sender.callCounts["imp:click"])
    }

    @Test
    fun `a 5xx keeps the beacon and records the attempt for backoff`() = runTest {
        val store = FakeStore()
        val sender = FakeSender().apply { codes["imp:seen"] = 503 }
        val engine = AdBeaconQueue(store, sender, clock = { 1_000L }, scope = this)

        engine.queue("imp", "seen")
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

        engine.queue("imp", "seen")
        advanceUntilIdle()

        assertEquals(1, store.data.size)
    }

    @Test
    fun `a duplicate beacon is enqueued and sent only once`() = runTest {
        val store = FakeStore()
        val sender = FakeSender().apply { codes["imp:seen"] = 503 } // keep it queued
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.queue("imp", "seen")
        engine.queue("imp", "seen") // duplicate
        advanceUntilIdle()

        assertEquals("same (impressionId, action) deduped", 1, store.data.size)
    }

    @Test
    fun `distinct actions on the same impression are independent`() = runTest {
        val store = FakeStore()
        val sender = FakeSender().apply { codes["imp:seen"] = 200; codes["imp:click"] = 200 }
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.queue("imp", "seen")
        engine.queue("imp", "click")
        advanceUntilIdle()

        assertTrue(store.data.isEmpty())
        assertEquals(1, sender.callCounts["imp:seen"])
        assertEquals(1, sender.callCounts["imp:click"])
    }

    @Test
    fun `trigger drains a beacon left by a prior session`() = runTest {
        val store = FakeStore(listOf(PendingBeacon("imp", "seen", retryCount = 0, lastAttemptTimestamp = 0L)))
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

        engine.queue("imp", "seen")
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

        engine.queue("", "seen")
        advanceUntilIdle()

        assertTrue(store.data.isEmpty())
        assertTrue(sender.callCounts.isEmpty())
    }
}
