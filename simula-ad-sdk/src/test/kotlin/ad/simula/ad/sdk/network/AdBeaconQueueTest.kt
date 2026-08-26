package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.core.LaunchSettledGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val loadResults = ArrayDeque<DurableLoadResult<List<PendingBeacon>>>()
        val saveResults = ArrayDeque<DurableMutationResult>()
        var failLoads = false
        override fun load(): DurableLoadResult<List<PendingBeacon>> = if (failLoads) {
            DurableLoadResult.Failed
        } else {
            loadResults.removeFirstOrNull() ?: DurableLoadResult.Loaded(data)
        }
        override fun save(queue: List<PendingBeacon>): DurableMutationResult {
            val result = saveResults.removeFirstOrNull() ?: DurableMutationResult.Applied
            if (result == DurableMutationResult.Applied) data = queue.toList()
            return result
        }
    }

    /** Programmable sender: per-`impressionId:action` status code, or a thrown connectivity error. */
    private class FakeSender : BeaconSender {
        val codes = mutableMapOf<String, Int>()
        val errors = mutableMapOf<String, Throwable>()
        val callCounts = mutableMapOf<String, Int>()
        val sentMetadata = mutableMapOf<String, Map<String, String>?>()
        val sentBeacons = mutableListOf<PendingBeacon>()
        private fun key(id: String, action: String) = "$id:$action"
        override suspend fun send(beacon: PendingBeacon): Int {
            val k = key(beacon.impressionId, beacon.action)
            callCounts[k] = (callCounts[k] ?: 0) + 1
            sentMetadata[k] = beacon.metadata
            sentBeacons += beacon.copy()
            errors[k]?.let { throw it }
            return codes[k] ?: 200
        }
    }

    private class BlockingSender : BeaconSender {
        val firstCallStarted = CompletableDeferred<Unit>()
        val releaseFirstCall = CompletableDeferred<Unit>()
        val metadataSnapshots = mutableListOf<Map<String, String>?>()

        override suspend fun send(beacon: PendingBeacon): Int {
            metadataSnapshots += beacon.metadata
            if (metadataSnapshots.size == 1) {
                firstCallStarted.complete(Unit)
                releaseFirstCall.await()
            }
            return 200
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
    fun `failed enqueue persistence waits for coalesced recovery before delivery`() = runTest {
        val store = FakeStore().apply {
            saveResults += DurableMutationResult.Failed
            saveResults += DurableMutationResult.Applied
        }
        val sender = FakeSender()
        val sleepEntered = CompletableDeferred<Unit>()
        val releaseSleep = CompletableDeferred<Unit>()
        var persistenceOutcome: BeaconPersistenceOutcome? = null
        val engine = AdBeaconQueue(
            store,
            sender,
            clock = { 0L },
            scope = this,
            sleep = {
                sleepEntered.complete(Unit)
                releaseSleep.await()
            },
        )

        engine.queue("imp", "seen", onPersistenceComplete = { persistenceOutcome = it })
        sleepEntered.await()

        assertEquals(BeaconPersistenceOutcome.RetryableFailure, persistenceOutcome)
        assertTrue(store.data.isEmpty())
        assertTrue(sender.callCounts.isEmpty())

        releaseSleep.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, sender.callCounts["imp:seen"])
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun `successful enqueue reports durable persistence before network completion`() = runTest {
        val store = FakeStore()
        val sender = BlockingSender()
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)
        var persistenceOutcome: BeaconPersistenceOutcome? = null

        engine.queue(
            impressionId = "serve-1",
            action = "click",
            interactionId = "interaction-1",
            clickSource = ClickSources.STORE_PROMPT,
            onPersistenceComplete = { persistenceOutcome = it },
        )
        sender.firstCallStarted.await()

        assertEquals(BeaconPersistenceOutcome.Persisted, persistenceOutcome)
        sender.releaseFirstCall.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `failed queue load retries storage before delivery`() = runTest {
        val store = FakeStore(listOf(PendingBeacon("imp", "seen"))).apply {
            loadResults += DurableLoadResult.Failed
        }
        val sender = FakeSender()
        val sleepEntered = CompletableDeferred<Unit>()
        val releaseSleep = CompletableDeferred<Unit>()
        val engine = AdBeaconQueue(
            store,
            sender,
            clock = { 0L },
            scope = this,
            sleep = {
                sleepEntered.complete(Unit)
                releaseSleep.await()
            },
        )

        engine.trigger()
        sleepEntered.await()
        assertTrue(sender.callCounts.isEmpty())
        assertEquals(1, store.data.size)

        releaseSleep.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, sender.callCounts["imp:seen"])
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun `storage recovery coalesces action keys and bounds pending beacon memory`() = runTest {
        val store = FakeStore().apply { failLoads = true }
        val sender = FakeSender()
        val sleepEntered = CompletableDeferred<Unit>()
        val releaseSleep = CompletableDeferred<Unit>()
        var sleepCount = 0
        var overflowOutcome: BeaconPersistenceOutcome? = null
        val engine = AdBeaconQueue(
            store,
            sender,
            clock = { 0L },
            scope = this,
            sleep = {
                sleepCount++
                sleepEntered.complete(Unit)
                releaseSleep.await()
            },
            maxPendingEnqueues = 1,
        )

        engine.queue("imp", "seen", mapOf("a" to "one"))
        engine.queue("imp", "seen", mapOf("b" to "two"))
        engine.queue("overflow", "click", onPersistenceComplete = { overflowOutcome = it })
        sleepEntered.await()
        runCurrent()

        assertEquals("one recovery sleeper for every pending event", 1, sleepCount)
        assertTrue(sender.callCounts.isEmpty())
        assertEquals(BeaconPersistenceOutcome.Rejected, overflowOutcome)

        store.failLoads = false
        releaseSleep.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, sender.callCounts["imp:seen"])
        assertEquals(null, sender.callCounts["overflow:click"])
        assertEquals(mapOf("a" to "one", "b" to "two"), sender.sentMetadata["imp:seen"])
    }

    @Test
    fun `persistent storage recovery uses one exponential loop with capped delay`() = runTest {
        val store = FakeStore().apply { failLoads = true }
        val sender = FakeSender()
        val delays = mutableListOf<Long>()
        val engine = AdBeaconQueue(
            store,
            sender,
            clock = { 0L },
            scope = this,
            sleep = { delayMs ->
                delays += delayMs
                if (delays.size == 7) store.failLoads = false
            },
        )

        engine.queue("imp", "seen")
        advanceUntilIdle()

        assertEquals(listOf(100L, 200L, 400L, 800L, 1_600L, 3_200L, 5_000L), delays)
        assertEquals(1, sender.callCounts["imp:seen"])
    }

    @Test
    fun `storage recovery re-arms network backoff eligibility for recovered beacon`() = runTest {
        var now = 0L
        val store = FakeStore(
            listOf(PendingBeacon("imp", "seen", retryCount = 1, lastAttemptTimestamp = 0L)),
        ).apply { loadResults += DurableLoadResult.Failed }
        val sender = FakeSender()
        val releaseStorage = CompletableDeferred<Unit>()
        val delays = mutableListOf<Long>()
        val engine = AdBeaconQueue(
            store,
            sender,
            clock = { now },
            scope = this,
            sleep = { delayMs ->
                delays += delayMs
                if (delays.size == 1) releaseStorage.await() else delay(delayMs)
            },
        )

        engine.trigger()
        runCurrent()
        assertEquals(listOf(100L), delays)

        releaseStorage.complete(Unit)
        runCurrent()
        assertEquals(listOf(100L, 5_000L), delays)
        assertTrue(sender.callCounts.isEmpty())

        now = 5_000L
        advanceTimeBy(5_000L)
        advanceUntilIdle()
        assertEquals(1, sender.callCounts["imp:seen"])
    }

    @Test
    fun `failed delivery removal backs off under one processing claim without resending`() = runTest {
        val task = PendingBeacon("imp", "seen")
        val store = FakeStore(listOf(task)).apply {
            saveResults += DurableMutationResult.Failed
            saveResults += DurableMutationResult.Applied
        }
        val sender = FakeSender()
        val sleepEntered = CompletableDeferred<Unit>()
        val releaseSleep = CompletableDeferred<Unit>()
        val delays = mutableListOf<Long>()
        val engine = AdBeaconQueue(
            store,
            sender,
            clock = { 0L },
            scope = this,
            sleep = { delayMs ->
                delays += delayMs
                sleepEntered.complete(Unit)
                releaseSleep.await()
            },
        )

        engine.trigger()
        sleepEntered.await()
        assertEquals(1, sender.callCounts["imp:seen"])
        assertEquals(listOf(100L), delays)
        assertEquals(1, store.data.size)

        releaseSleep.complete(Unit)
        advanceUntilIdle()
        assertEquals("storage retry must not resend the network request", 1, sender.callCounts["imp:seen"])
        assertTrue(store.data.isEmpty())
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
    fun `startup trigger does not drain before launch settles`() = runTest {
        val store = FakeStore(listOf(PendingBeacon("imp", "seen")))
        val sender = FakeSender()
        val settled = CompletableDeferred<Unit>()
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.trigger(LaunchSettledGate { settled.await() })
        runCurrent()
        assertTrue(sender.callCounts.isEmpty())
        assertEquals(1, store.data.size)

        settled.complete(Unit)
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
    fun `startup trigger schedules the earliest backed-off beacon automatically`() = runTest {
        var now = 0L
        val store = FakeStore(
            listOf(
                PendingBeacon("early", "seen", retryCount = 1, lastAttemptTimestamp = 0L),
                PendingBeacon("late", "seen", retryCount = 2, lastAttemptTimestamp = 0L),
            ),
        )
        val sender = FakeSender()
        val engine = AdBeaconQueue(store, sender, clock = { now }, scope = this)

        engine.trigger()
        runCurrent()
        assertTrue(sender.callCounts.isEmpty())

        now = 5_000L
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, sender.callCounts["early:seen"])
        assertEquals(null, sender.callCounts["late:seen"])

        now = 10_000L
        advanceTimeBy(5_000L)
        advanceUntilIdle()
        assertEquals(1, sender.callCounts["late:seen"])
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

    @Test
    fun `persisted metadata propagates to sender after recovery`() = runTest {
        val metadata = mapOf("placement" to "feed")
        val store = FakeStore(listOf(PendingBeacon("imp", "seen", metadata = metadata)))
        val sender = FakeSender()
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.trigger()
        advanceUntilIdle()

        assertEquals(metadata, sender.sentMetadata["imp:seen"])
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun `duplicate merges metadata with incoming values winning and preserves retry state`() = runTest {
        val existing = PendingBeacon(
            impressionId = "imp",
            action = "seen",
            metadata = mapOf("a" to "old", "b" to "kept"),
            retryCount = 2,
            lastAttemptTimestamp = 1_000L,
        )
        val store = FakeStore(listOf(existing))
        val sender = FakeSender()
        val engine = AdBeaconQueue(store, sender, clock = { 1_000L }, scope = this)

        engine.queue("imp", "seen", mapOf("a" to "new", "c" to "added"))
        advanceUntilIdle()

        assertEquals(1, store.data.size)
        assertEquals(mapOf("a" to "new", "b" to "kept", "c" to "added"), store.data[0].metadata)
        assertEquals(2, store.data[0].retryCount)
        assertEquals(1_000L, store.data[0].lastAttemptTimestamp)
        assertTrue(sender.callCounts.isEmpty())
    }

    @Test
    fun `duplicate with identical metadata keeps the same persisted revision`() = runTest {
        val existing = PendingBeacon(
            impressionId = "imp",
            action = "seen",
            metadata = mapOf("placement" to "feed"),
            retryCount = 1,
            lastAttemptTimestamp = 1_000L,
        )
        val store = FakeStore(listOf(existing))
        val engine = AdBeaconQueue(store, FakeSender(), clock = { 1_000L }, scope = this)

        engine.queue("imp", "seen", mapOf("placement" to "feed"))
        advanceUntilIdle()

        assertEquals(existing.rowId, store.data.single().rowId)
        assertEquals(1, store.data.single().retryCount)
    }

    @Test
    fun `metadata merged during successful send is delivered then queue drains`() = runTest {
        val store = FakeStore()
        val sender = BlockingSender()
        val engine = AdBeaconQueue(store, sender, clock = { 0L }, scope = this)

        engine.queue("imp", "seen", mapOf("page_name" to "Search"))
        sender.firstCallStarted.await()
        engine.queue("imp", "seen", mapOf("surface" to "chat"))
        runCurrent()
        assertEquals(mapOf("page_name" to "Search", "surface" to "chat"), store.data.single().metadata)

        sender.releaseFirstCall.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf(
                mapOf("page_name" to "Search"),
                mapOf("page_name" to "Search", "surface" to "chat"),
            ),
            sender.metadataSnapshots,
        )
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun `legacy persisted beacon without metadata still decodes`() {
        val decoded = Json.decodeFromString<List<PendingBeacon>>(
            """[{"impressionId":"imp","action":"seen","retryCount":1,"lastAttemptTimestamp":42}]""",
        )

        assertEquals(1, decoded.size)
        assertNull(decoded[0].metadata)
        assertEquals(1, decoded[0].retryCount)
        assertEquals(42L, decoded[0].lastAttemptTimestamp)
    }

    @Test
    fun `persisted click retry retains event id and source`() = runTest {
        var now = 0L
        val store = FakeStore()
        val sender = FakeSender().apply { codes["serve-1:click"] = 500 }
        val retryScheduled = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        val engine = AdBeaconQueue(
            store,
            sender,
            clock = { now },
            scope = this,
            sleep = {
                retryScheduled.complete(Unit)
                releaseRetry.await()
            },
        )

        engine.queue(
            impressionId = "serve-1",
            action = "click",
            interactionId = "0f62cb3e-e63d-4e6a-87dd-2bfb84bd156d",
            clickSource = ClickSources.INSTALL_BANNER,
        )
        retryScheduled.await()
        val persistedRetry = store.data.single()

        sender.codes["serve-1:click"] = 200
        now = 5_000L
        releaseRetry.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, sender.sentBeacons.size)
        assertEquals(
            listOf(persistedRetry.interactionId, persistedRetry.interactionId),
            sender.sentBeacons.map { it.interactionId },
        )
        assertEquals(
            listOf(ClickSources.INSTALL_BANNER, ClickSources.INSTALL_BANNER),
            sender.sentBeacons.map { it.clickSource },
        )
        assertEquals(listOf(persistedRetry.rowId, persistedRetry.rowId), sender.sentBeacons.map { it.rowId })
    }

    @Test
    fun `click headers use exact contract names while shown headers stay unchanged`() {
        val clickHeaders = SimulaApiClient.impressionBeaconHeaders(
            apiKey = "key",
            action = "click",
            interactionId = "event-123",
            clickSource = "cta",
        )
        val shownHeaders = SimulaApiClient.impressionBeaconHeaders(
            apiKey = "key",
            action = "shown",
            interactionId = "event-123",
            clickSource = ClickSources.PRIMARY_CTA,
        )

        assertEquals("event-123", clickHeaders["X-Simula-Click-Event-Id"])
        assertEquals(ClickSources.PRIMARY_CTA, clickHeaders["X-Simula-Click-Source"])
        assertEquals(
            shownHeaders,
            clickHeaders - setOf("X-Simula-Click-Event-Id", "X-Simula-Click-Source"),
        )
        assertTrue("X-Simula-Click-Event-Id" !in shownHeaders)
        assertTrue("X-Simula-Click-Source" !in shownHeaders)
    }

    @Test
    fun `seen metadata body uses exact wire key`() {
        val json = Json
        val root = json.parseToJsonElement(
            json.encodeToString(ImpressionMetadataRequestBody(mapOf("placement" to "feed"))),
        ).jsonObject

        assertEquals(
            JsonObject(mapOf("placement" to JsonPrimitive("feed"))),
            root["metadata"],
        )
        assertTrue("extraParameters" !in root)
        assertTrue("extra_parameters" !in root)
    }

    @Test
    fun `metadata body exists only for nonempty seen metadata`() {
        assertEquals(
            mapOf("placement" to "feed"),
            impressionMetadataRequestBody("seen", mapOf("placement" to "feed"))?.metadata,
        )
        assertNull(impressionMetadataRequestBody("seen", emptyMap()))
        assertNull(impressionMetadataRequestBody("shown", mapOf("placement" to "feed")))
        assertNull(impressionMetadataRequestBody("click", mapOf("placement" to "feed")))
    }
}
