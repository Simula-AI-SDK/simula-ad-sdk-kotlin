package ad.simula.ad.sdk.privacy

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the [raceGaidRead] contract: only a REAL timeout (a wedged Play Services bind) is
 * reported via `onTimeout` — a fast, legitimate null (user opt-out, missing Play Services)
 * must NOT be. The old code emitted `privacy:gaid_read_timeout` for any null result, so
 * every opted-out device produced false error telemetry once per refresh window.
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
            onTimeout = { timeouts++ },
        )
        assertEquals("gaid-123", out)
        assertEquals(0, timeouts)
    }

    @Test
    fun `a wedged read yields null and IS reported as a timeout`() = runTest {
        var timedOutMs: Long? = null
        val job = async {
            raceGaidRead(
                reader = { awaitCancellation() }, // a wedged Play Services bind never returns
                timeoutMs = 8_000,
                dispatcher = StandardTestDispatcher(testScheduler),
                onTimeout = { timedOutMs = it },
            )
        }
        testScheduler.advanceTimeBy(8_000)
        testScheduler.runCurrent()
        assertNull(job.await())
        assertEquals(8_000L, timedOutMs)
    }
}
