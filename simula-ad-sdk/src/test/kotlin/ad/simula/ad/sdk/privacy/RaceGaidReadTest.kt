package ad.simula.ad.sdk.privacy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the [raceGaidRead] contract: only a REAL timeout (a wedged Play Services bind) is
 * reported via `onTimeout` — a fast, legitimate null (user opt-out, missing Play Services)
 * must NOT be. And the timeout must be real: the read runs as an abandonable orphan, so even
 * a genuinely BLOCKING (non-cancellable) reader is timed out — the previous
 * `withTimeoutOrNull { withContext { blocking } }` shape returned only after the blocking
 * call completed (i.e. never, on a wedged device) while passing tests that faked the wedge
 * with a cancellable suspension.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaceGaidReadTest {

    @Test
    fun `a fast null result passes through and is NOT reported as a timeout`() = runTest {
        var timeouts = 0
        val out = raceGaidRead(
            reader = { null },
            timeoutMs = 8_000,
            dispatcher = StandardTestDispatcher(testScheduler),
            orphanScope = backgroundScope,
            onTimeout = { timeouts++ },
        )
        assertNull(out)
        assertEquals("a legitimate null read must not emit timeout telemetry", 0, timeouts)
    }

    @Test
    fun `a real GAID value passes through with no timeout`() = runTest {
        var timeouts = 0
        val out = raceGaidRead(
            reader = { "gaid-123" },
            timeoutMs = 8_000,
            dispatcher = StandardTestDispatcher(testScheduler),
            orphanScope = backgroundScope,
            onTimeout = { timeouts++ },
        )
        assertEquals("gaid-123", out)
        assertEquals(0, timeouts)
    }

    @Test
    fun `a wedged (cancellable) read yields null and IS reported as a timeout`() = runTest {
        var timedOutMs: Long? = null
        val job = async {
            raceGaidRead(
                reader = { awaitCancellation() },
                timeoutMs = 8_000,
                dispatcher = StandardTestDispatcher(testScheduler),
                orphanScope = backgroundScope,
                onTimeout = { timedOutMs = it },
            )
        }
        testScheduler.advanceTimeBy(8_000)
        testScheduler.runCurrent()
        assertNull(job.await())
        assertEquals(8_000L, timedOutMs)
    }

    @Test
    fun `a genuinely blocking (non-cancellable) read is abandoned at the timeout`() {
        // WALL-CLOCK test (no virtual time): a wedged Play Services bind blocks its thread
        // and ignores cooperative cancellation — the read must STILL time out, leaving the
        // blocked orphan behind. This is the production scenario the in-test-cancellable
        // fake above cannot represent.
        var timedOutMs: Long? = null
        val start = System.nanoTime()
        val out = runBlocking {
            raceGaidRead(
                reader = { Thread.sleep(10_000); "late" }, // blocking: resumes after 10 s, if ever awaited
                timeoutMs = 150,
                dispatcher = Dispatchers.IO,
                orphanScope = CoroutineScope(Dispatchers.IO), // abandoned orphan finishes harmlessly
                onTimeout = { timedOutMs = it },
            )
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertNull(out)
        assertEquals(150L, timedOutMs)
        assertTrue(
            "took ${elapsedMs}ms — the blocking read was NOT abandoned at the 150ms timeout",
            elapsedMs < 5_000,
        )
    }

    @Test
    fun `PROOF the old withContext shape cannot abandon a blocking read (do not regress to it)`() {
        // The pre-orphan implementation: withTimeoutOrNull { withContext(IO) { blocking } }.
        // Cancellation is cooperative, so it resumes only when the blocking call completes —
        // i.e. it "times out" at the read's own completion time, not at timeoutMs.
        val start = System.nanoTime()
        runBlocking {
            oldShapeRaceGaidRead(reader = { Thread.sleep(2_000); "late" }, timeoutMs = 150)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(
            "old shape returned after ${elapsedMs}ms — it waits for the blocking read, not the timeout",
            elapsedMs >= 1_800,
        )
    }

    /** The inert pre-orphan shape, kept only to prove (above) why it must never come back. */
    private suspend fun oldShapeRaceGaidRead(reader: suspend () -> String?, timeoutMs: Long): String? =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.withContext(Dispatchers.IO) { reader() }
        }
}
