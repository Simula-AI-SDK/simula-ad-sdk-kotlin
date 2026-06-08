package ad.simula.ad.sdk.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier-1 tests for [RewardVerificationQueue] — the draining engine behind the rewarded
 * SSV verification. Run with deterministic virtual time (StandardTestDispatcher) and
 * in-memory fakes, so the routing / dropping / stranding / backoff behavior is exercised
 * without Android, the network, or wall-clock timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RewardVerificationQueueTest {

    // ── Fakes ────────────────────────────────────────────────────────────────

    private class FakeStore(initial: List<PendingVerification> = emptyList()) : VerificationStore {
        var data: List<PendingVerification> = initial.toList()
        override fun load(): List<PendingVerification> = data
        override fun save(queue: List<PendingVerification>) { data = queue.toList() }
    }

    /**
     * Programmable verifier: per-`serveId` token (success) or error (throw), with an
     * optional gate so a test can hold a verify "in flight" while it enqueues more work.
     * Single-threaded test dispatcher → plain maps are safe.
     */
    private class FakeVerifier : RewardVerifier {
        val tokens = mutableMapOf<String, String?>()
        val errors = mutableMapOf<String, Throwable>()
        val callCounts = mutableMapOf<String, Int>()
        private val release = mutableMapOf<String, CompletableDeferred<Unit>>()
        val entered = mutableMapOf<String, CompletableDeferred<Unit>>()

        /** Make verify(serveId) block until [release] is called. */
        fun gate(serveId: String) {
            release[serveId] = CompletableDeferred()
            entered[serveId] = CompletableDeferred()
        }

        fun release(serveId: String) {
            release[serveId]?.complete(Unit)
        }

        override suspend fun verify(serveId: String, sessionId: String, elapsedPlayTime: Double): String? {
            callCounts[serveId] = (callCounts[serveId] ?: 0) + 1
            entered[serveId]?.complete(Unit)
            release[serveId]?.await()
            errors[serveId]?.let { throw it }
            return tokens[serveId]
        }
    }

    // ── Single-task outcomes ───────────────────────────────────────────────────

    @Test
    fun `success delivers the token and removes the task`() = runTest {
        val store = FakeStore()
        val verifier = FakeVerifier().apply { tokens["A"] = "tokA" }
        val engine = RewardVerificationQueue(store, verifier, clock = { 0L }, scope = this)

        var received: Result<String?>? = null
        engine.queue("A", "sess", 5.0) { received = it }
        advanceUntilIdle()

        assertEquals("tokA", received?.getOrNull())
        assertTrue(store.data.isEmpty())
        assertEquals(1, verifier.callCounts["A"])
    }

    @Test
    fun `permanent 4xx delivers failure and drops the task (no retry)`() = runTest {
        val store = FakeStore()
        val verifier = FakeVerifier().apply { errors["A"] = Exception("HTTP error! status: 400") }
        val engine = RewardVerificationQueue(store, verifier, clock = { 0L }, scope = this)

        var received: Result<String?>? = null
        engine.queue("A", "sess", 5.0) { received = it }
        advanceUntilIdle()

        assertTrue(received!!.isFailure)
        assertTrue("permanent error must drop the task", store.data.isEmpty())
    }

    @Test
    fun `retryable error keeps the task and records the attempt`() = runTest {
        val store = FakeStore()
        val verifier = FakeVerifier().apply { errors["A"] = Exception("HTTP error! status: 500") }
        val engine = RewardVerificationQueue(store, verifier, clock = { 1_000L }, scope = this)

        var received: Result<String?>? = null
        engine.queue("A", "sess", 5.0) { received = it }
        advanceUntilIdle()

        assertTrue(received!!.isFailure)
        assertEquals(1, store.data.size)
        assertEquals(1, store.data[0].retryCount)
        assertEquals(1_000L, store.data[0].lastAttemptTimestamp)
        assertEquals(1, verifier.callCounts["A"]) // not hammered
    }

    // ── Routing / dropping / stranding (the core regression guards) ──────────────

    @Test
    fun `a verification enqueued during an in-flight drain is processed and routed to its own caller`() = runTest {
        val store = FakeStore()
        val verifier = FakeVerifier().apply {
            tokens["A"] = "tokA"
            tokens["B"] = "tokB"
            gate("A") // hold A in flight so B is enqueued mid-drain
        }
        val engine = RewardVerificationQueue(store, verifier, clock = { 0L }, scope = this)

        var rA: Result<String?>? = null
        var rB: Result<String?>? = null
        engine.queue("A", "sess", 5.0) { rA = it }
        engine.queue("B", "sess", 5.0) { rB = it }
        advanceUntilIdle()

        // A is in flight; B was enqueued while the drain held the processing claim.
        assertTrue(verifier.entered["A"]!!.isCompleted)
        assertNull("A's callback must not fire until release", rA)
        assertNull("B must not be dropped, but also not yet delivered", rB)
        assertEquals(2, store.data.size)

        verifier.release("A")
        advanceUntilIdle()

        // Each callback gets ITS OWN result — never crossed (the misrouting bug).
        assertEquals("tokA", rA?.getOrNull())
        assertEquals("tokB", rB?.getOrNull())
        assertEquals(1, verifier.callCounts["A"])
        assertEquals(1, verifier.callCounts["B"])
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun `callback is delivered exactly once even across an extra trigger`() = runTest {
        val store = FakeStore()
        val verifier = FakeVerifier().apply { tokens["A"] = "tokA" }
        val engine = RewardVerificationQueue(store, verifier, clock = { 0L }, scope = this)

        var count = 0
        engine.queue("A", "sess", 5.0) { count++ }
        advanceUntilIdle()
        engine.trigger() // task already gone; callback already removed (one-shot)
        advanceUntilIdle()

        assertEquals(1, count)
    }

    @Test
    fun `a throwing listener does not derail the drain`() = runTest {
        val store = FakeStore()
        val verifier = FakeVerifier().apply { tokens["A"] = "tokA"; tokens["B"] = "tokB" }
        val engine = RewardVerificationQueue(store, verifier, clock = { 0L }, scope = this)

        var rB: Result<String?>? = null
        engine.queue("A", "sess", 5.0) { throw RuntimeException("listener boom") }
        engine.queue("B", "sess", 5.0) { rB = it }
        advanceUntilIdle()

        // A's task was still verified+removed despite its callback throwing, and B drained.
        assertEquals("tokB", rB?.getOrNull())
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun `a duplicate serveId is enqueued and verified only once`() = runTest {
        val store = FakeStore()
        val verifier = FakeVerifier().apply { tokens["A"] = "tokA"; gate("A") }
        val engine = RewardVerificationQueue(store, verifier, clock = { 0L }, scope = this)

        engine.queue("A", "sess", 5.0)
        engine.queue("A", "sess", 5.0) // duplicate while the first is in flight
        advanceUntilIdle()
        assertEquals(1, store.data.size)

        verifier.release("A")
        advanceUntilIdle()
        assertEquals(1, verifier.callCounts["A"])
        assertTrue(store.data.isEmpty())
    }

    // ── Recovery + backoff timing (clock-driven) ────────────────────────────────

    @Test
    fun `trigger drains a task left in the store by a prior session`() = runTest {
        val store = FakeStore(listOf(PendingVerification("A", "sess", 5.0, retryCount = 0, lastAttemptTimestamp = 0L)))
        val verifier = FakeVerifier().apply { tokens["A"] = "tokA" }
        val engine = RewardVerificationQueue(store, verifier, clock = { 0L }, scope = this)

        engine.trigger() // the app-launch recovery path (Fix C)
        advanceUntilIdle()

        assertEquals(1, verifier.callCounts["A"])
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun `a backed-off task is skipped until its delay elapses, then retried`() = runTest {
        var now = 0L
        val store = FakeStore()
        val verifier = FakeVerifier().apply { errors["A"] = Exception("HTTP error! status: 500") }
        val engine = RewardVerificationQueue(store, verifier, clock = { now }, scope = this)

        engine.queue("A", "sess", 5.0)
        advanceUntilIdle()
        assertEquals(1, verifier.callCounts["A"])
        assertEquals(1, store.data[0].retryCount) // attempt recorded at now=0; backoff(1)=5000ms

        // Make a retry succeed, but stay just under the backoff window.
        verifier.errors.remove("A")
        verifier.tokens["A"] = "tokA"
        now = 4_999
        engine.trigger()
        advanceUntilIdle()
        assertEquals("still backed off → not attempted", 1, verifier.callCounts["A"])

        now = 5_000
        engine.trigger()
        advanceUntilIdle()
        assertEquals("now eligible → retried", 2, verifier.callCounts["A"])
        assertTrue(store.data.isEmpty())
    }
}
