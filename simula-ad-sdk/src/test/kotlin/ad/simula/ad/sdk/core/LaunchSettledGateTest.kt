package ad.simula.ad.sdk.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchSettledGateTest {
    @Test
    fun `gate opens once after the five second quiet window and stays open`() = runTest {
        val gate = DelayedLaunchSettledGate(scope = this)
        val first = async { gate.awaitSettled() }

        runCurrent()
        assertFalse(first.isCompleted)
        advanceTimeBy(LAUNCH_QUIET_WINDOW_MS - 1L)
        runCurrent()
        assertFalse(first.isCompleted)

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(first.isCompleted)

        val late = async { gate.awaitSettled() }
        runCurrent()
        assertTrue("late calls must not start a second delay", late.isCompleted)
    }
}
