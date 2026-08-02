package ad.simula.ad.sdk.provider

import ad.simula.ad.sdk.privacy.raceGaidRead
import ad.simula.ad.sdk.privacy.GAID_READ_TIMEOUT_MS
import ad.simula.ad.sdk.telemetry.TELEMETRY_READY_TIMEOUT_MS
import ad.simula.ad.sdk.telemetry.awaitTelemetryReady
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
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
    fun `telemetry and GAID bounds leave setup headroom inside the outer gate`() {
        val headroom = STARTUP_GATE_TIMEOUT_MS - TELEMETRY_READY_TIMEOUT_MS - GAID_READ_TIMEOUT_MS

        assertTrue(headroom >= MIN_STARTUP_GATE_HEADROOM_MS)
        assertEquals(3_000L, headroom)
    }

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

    @Test
    fun `a wedged telemetry ready releases the owning gate once without repeated waits`() = runTest {
        val telemetryReady = CompletableDeferred<Unit>()
        val ownerGate = CompletableDeferred<Unit>()
        backgroundScope.launch {
            try {
                awaitTelemetryReady(telemetryReady, timeoutMs = 5_000)
            } finally {
                ownerGate.complete(Unit)
            }
        }

        assertTrue(awaitStartupGate(ownerGate, timeoutMs = 15_000))
        assertEquals(5_000L, currentTime)
        assertFalse(telemetryReady.isCancelled)

        assertTrue(awaitStartupGate(ownerGate, timeoutMs = 15_000))
        assertEquals("the completed owner gate must not impose another timeout", 5_000L, currentTime)
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
