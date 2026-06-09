package ad.simula.ad.sdk.telemetry

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier-1 tests for [TelemetryManager] — the batching/dedup/backoff engine behind in-house
 * SDK telemetry. Deterministic virtual time (StandardTestDispatcher) + in-memory fakes, so
 * flush triggering, error aggregation, durability, retry/backoff, sampling, the kill-switch,
 * and consent-gated PII are exercised without Android, the network, or wall-clock timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryManagerTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Fakes ────────────────────────────────────────────────────────────────

    private class FakeStore(initial: List<TelemetryEvent> = emptyList()) : TelemetryStore {
        var data: List<TelemetryEvent> = initial.toList()
        override fun load(): List<TelemetryEvent> = data
        override fun save(events: List<TelemetryEvent>) { data = events.toList() }
    }

    /** Records decoded batches; replays queued acks then falls back to [defaultAck]. Optional
     * one-shot gate so a test can hold the first send in flight while it enqueues more work. */
    private inner class FakeSender : TelemetrySender {
        val batches = mutableListOf<TelemetryEnvelope>()
        var defaultAck = TelemetryAck.ACCEPTED
        private val acks = ArrayDeque<TelemetryAck>()
        private var gate: CompletableDeferred<Unit>? = null

        fun enqueueAcks(vararg a: TelemetryAck) = acks.addAll(a)
        fun gateFirst() { gate = CompletableDeferred() }
        fun release() { gate?.complete(Unit); gate = null }

        override suspend fun send(body: String): TelemetryAck {
            gate?.await()
            batches.add(json.decodeFromString<TelemetryEnvelope>(body))
            return if (acks.isNotEmpty()) acks.removeFirst() else defaultAck
        }
    }

    private fun build(
        scope: CoroutineScope,
        store: TelemetryStore,
        sender: TelemetrySender,
        enabled: Boolean = true,
        sampleRate: Double = 1.0,
        random: () -> Double = { 0.0 },
        sessionId: String? = "sess",
        ppid: String? = null,
        gaid: String? = null,
    ) = TelemetryManager(
        ctx = TelemetryContext(sdkVersion = "9.9", osVersion = "14", deviceModel = "Test Pixel", hostAppId = "com.test", devMode = true),
        store = store,
        sender = sender,
        sessionIdProvider = { sessionId },
        primaryUserIdProvider = { ppid },
        advertisingIdProvider = { gaid },
        enabled = enabled,
        sampleRate = sampleRate,
        clock = { 1_000L },
        scope = scope,
        random = random,
    )

    private fun List<TelemetryEnvelope>.allEvents() = flatMap { it.events }

    // ── Batching + flush ─────────────────────────────────────────────────────

    @Test
    fun `sub-threshold perf events flush on the timer and clear the buffer`() = runTest {
        val store = FakeStore()
        val sender = FakeSender()
        val m = build(this, store, sender)

        repeat(3) { m.recordNetwork("/load/interstitial", "POST", 200, durationMs = 12, requestBytes = 0, responseBytes = 100, failureClass = null) }
        advanceUntilIdle() // advances through the 30s flush timer

        val net = sender.batches.allEvents().filter { it.type == TYPE_NETWORK }
        assertEquals(3, net.size)
        assertTrue(net.all { it.name == "POST /load/interstitial" && it.durationMs == 12L })
        assertTrue("buffer must be cleared after a 2xx", store.data.isEmpty())
    }

    @Test
    fun `the telemetry envelope carries device context and the live session id`() = runTest {
        val sender = FakeSender()
        val m = build(this, FakeStore(), sender, sessionId = "sess-42")
        m.recordError("api:boom", "boom", "msg")
        advanceUntilIdle()

        val env = sender.batches.first()
        assertEquals("9.9", env.sdkVersion)
        assertEquals("android", env.platform)
        assertEquals("com.test", env.hostAppId)
        assertEquals("sess-42", env.sessionId)
    }

    // ── Error dedup + eager flush ──────────────────────────────────────────────

    @Test
    fun `identical errors aggregate by signature with no occurrence lost`() = runTest {
        val store = FakeStore()
        val sender = FakeSender().apply { gateFirst() } // hold the first send so #2/#3 pile up
        val m = build(this, store, sender)

        repeat(3) { m.recordError("api:decode", "decode", "bad json") }
        advanceUntilIdle() // all three records enqueued; first send parked on the gate

        sender.release()
        advanceUntilIdle()

        val errors = sender.batches.allEvents().filter { it.type == TYPE_ERROR }
        assertTrue("only one distinct error signature emitted", errors.all { it.name == "api:decode" })
        assertEquals("all 3 occurrences accounted for", 3, errors.sumOf { it.count ?: 0 })
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun `an error triggers an eager flush`() = runTest {
        val sender = FakeSender()
        val m = build(this, FakeStore(), sender)
        m.recordError("api:fatal", "fatal", "x")
        advanceUntilIdle()
        assertTrue(sender.batches.allEvents().any { it.type == TYPE_ERROR && it.name == "api:fatal" })
    }

    // ── Durability + recovery ──────────────────────────────────────────────────

    @Test
    fun `start recovers a buffer left by a prior process and delivers it`() = runTest {
        val seeded = TelemetryEvent(type = TYPE_NETWORK, name = "GET /catalog", eventId = "id-prev", timestamp = 1L, durationMs = 7)
        val store = FakeStore(listOf(seeded))
        val sender = FakeSender()
        val m = build(this, store, sender)

        m.start()
        advanceUntilIdle()

        assertTrue(sender.batches.allEvents().any { it.eventId == "id-prev" })
        assertTrue(store.data.isEmpty())
    }

    // ── Retry + backoff ─────────────────────────────────────────────────────────

    @Test
    fun `a failed batch is retried with backoff and the same events resent until accepted`() = runTest {
        val store = FakeStore()
        val sender = FakeSender().apply { enqueueAcks(TelemetryAck.RETRY, TelemetryAck.RETRY, TelemetryAck.ACCEPTED) }
        val m = build(this, store, sender)

        m.recordError("api:net", "net", "timeout")
        advanceUntilIdle()

        assertEquals("1 initial attempt + 2 retries", 3, sender.batches.size)
        // The same handled error is resent every attempt (idempotent retry).
        assertTrue(sender.batches.all { env -> env.events.any { it.type == TYPE_ERROR && it.name == "api:net" } })
        assertTrue("cleared once accepted", store.data.isEmpty())
    }

    @Test
    fun `a permanent 4xx drops the batch without retrying`() = runTest {
        val store = FakeStore()
        val sender = FakeSender().apply { defaultAck = TelemetryAck.DROP }
        val m = build(this, store, sender)

        m.recordError("api:bad", "bad", "x")
        advanceUntilIdle()

        assertEquals("no retry on a permanent error", 1, sender.batches.size)
        assertTrue(store.data.isEmpty())
    }

    // ── Sampling + kill-switch ──────────────────────────────────────────────────

    @Test
    fun `sampling drops perf but never errors`() = runTest {
        val sender = FakeSender()
        // random() 0.9 >= sampleRate 0.5 → this session is NOT sampled in for perf.
        val m = build(this, FakeStore(), sender, sampleRate = 0.5, random = { 0.9 })

        m.recordNetwork("/load", "POST", 200, 5, 0, 10, null)
        m.recordError("api:err", "err", "x")
        advanceUntilIdle()

        val events = sender.batches.allEvents()
        assertTrue("perf suppressed by sampling", events.none { it.type == TYPE_NETWORK })
        assertTrue("errors always sent", events.any { it.type == TYPE_ERROR })
    }

    @Test
    fun `the server kill-switch makes all recording a no-op`() = runTest {
        val store = FakeStore()
        val sender = FakeSender()
        val m = build(this, store, sender)
        m.applyServerConfig(enabled = false, sampleRate = 1.0)

        m.recordNetwork("/load", "POST", 200, 5, 0, 10, null)
        m.recordError("api:err", "err", "x")
        advanceUntilIdle()

        assertTrue(sender.batches.isEmpty())
        assertTrue(store.data.isEmpty())
    }

    // ── Consent-gated PII ───────────────────────────────────────────────────────

    @Test
    fun `PII is included when the providers supply it and omitted when they don't`() = runTest {
        val withPii = FakeSender()
        build(this, FakeStore(), withPii, ppid = "user-1", gaid = "gaid-1").recordError("e", "c", "m")
        advanceUntilIdle()
        with(withPii.batches.first()) {
            assertEquals("user-1", primaryUserId)
            assertEquals("gaid-1", advertisingId)
        }

        val noPii = FakeSender()
        build(this, FakeStore(), noPii, ppid = null, gaid = null).recordError("e", "c", "m")
        advanceUntilIdle()
        with(noPii.batches.first()) {
            assertNull(primaryUserId)
            assertNull(advertisingId)
        }
    }
}
