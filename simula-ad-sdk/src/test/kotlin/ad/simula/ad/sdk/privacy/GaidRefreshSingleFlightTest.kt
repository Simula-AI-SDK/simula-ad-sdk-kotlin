package ad.simula.ad.sdk.privacy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GaidRefreshSingleFlightTest {

    @Test
    fun `concurrent callers share one read and waiter cancellation does not cancel it`() = runTest {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        var reads = 0
        val refresh = GaidRefreshSingleFlight(
            scope = backgroundScope,
            currentGeneration = { 0L },
            refreshGeneration = {
                reads++
                readStarted.complete(Unit)
                releaseRead.await()
            },
        )

        val abandonedWaiter = async { refresh.refresh() }
        readStarted.await()
        val remainingWaiter = async { refresh.refresh() }
        runCurrent()

        assertEquals(1, reads)
        abandonedWaiter.cancel()
        runCurrent()
        assertFalse(remainingWaiter.isCompleted)

        releaseRead.complete(Unit)
        remainingWaiter.await()

        assertEquals(1, reads)
        assertTrue(abandonedWaiter.isCancelled)
    }

    @Test
    fun `privacy generation change starts one follow-up read`() = runTest {
        var generation = 0L
        val firstRelease = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val generationsRead = mutableListOf<Long>()
        val refresh = GaidRefreshSingleFlight(
            scope = backgroundScope,
            currentGeneration = { generation },
            refreshGeneration = { readGeneration ->
                generationsRead += readGeneration
                if (readGeneration == 0L) firstRelease.await() else secondRelease.await()
            },
        )

        val first = async { refresh.refresh() }
        runCurrent()
        generation = 1L
        val second = async { refresh.refresh() }
        runCurrent()
        assertEquals(listOf(0L), generationsRead)

        firstRelease.complete(Unit)
        runCurrent()
        assertEquals(listOf(0L, 1L), generationsRead)

        secondRelease.complete(Unit)
        first.await()
        second.await()

        assertEquals(listOf(0L, 1L), generationsRead)
    }

    @Test
    fun `late result is rejected after advertising id collection is disabled`() {
        val enabled = SimulaPrivacyConfig(enableAdvertisingId = true, coppaApplies = false)
        val disabled = enabled.copy(enableAdvertisingId = false)

        assertTrue(isGaidResultCurrent(4L, 4L, enabled))
        assertFalse(isGaidResultCurrent(4L, 5L, enabled))
        assertFalse(isGaidResultCurrent(4L, 4L, disabled))
        assertFalse(isGaidResultCurrent(4L, 4L, enabled.copy(coppaApplies = true)))
    }
}
