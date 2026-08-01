package ad.simula.ad.sdk.provider

import ad.simula.ad.sdk.privacy.raceGaidRead
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The startup-gate fail-open: a wedged startup must never deadlock ad loads. */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulaSessionStoreGateTest {

    @Test
    fun `a completed gate passes immediately`() = runTest {
        val gate = CompletableDeferred(Unit)
        assertTrue(awaitStartupGate(gate, timeoutMs = 60_000))
    }

    @Test
    fun `a null gate passes`() = runTest {
        assertTrue(awaitStartupGate(null, timeoutMs = 60_000))
    }

    @Test
    fun `a wedged gate fails open after the timeout instead of hanging`() = runTest {
        val gate = CompletableDeferred<Unit>() // never completed: the wedged-startup case
        // runTest's virtual clock advances through the timeout — the call returns false
        // rather than suspending forever.
        assertFalse(awaitStartupGate(gate, timeoutMs = 5_000))
    }

    @Test
    fun `a late-completing gate within the timeout still passes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        backgroundScope.launch { delay(1_000); gate.complete(Unit) }
        assertTrue(awaitStartupGate(gate, timeoutMs = 5_000))
    }
}

/** The GAID read race: a wedged Play Services bind must time out, not park the caller. */
@OptIn(ExperimentalCoroutinesApi::class)
class GaidReadRaceTest {

    @Test
    fun `a normal read returns its value`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertEquals("gaid-123", raceGaidRead(reader = { "gaid-123" }, timeoutMs = 60_000, dispatcher = dispatcher))
    }

    @Test
    fun `an unavailable read returns null without a timeout wait`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertNull(raceGaidRead(reader = { null }, timeoutMs = 60_000, dispatcher = dispatcher))
    }

    @Test
    fun `a wedged read times out to null instead of hanging`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val id = raceGaidRead(
            reader = { delay(Long.MAX_VALUE); "never" },
            timeoutMs = 5_000,
            dispatcher = dispatcher,
        )
        assertNull(id)
    }
}
