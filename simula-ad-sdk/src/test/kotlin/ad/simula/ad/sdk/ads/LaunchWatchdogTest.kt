package ad.simula.ad.sdk.ads

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dropped-launch watchdog: an unclaimed handoff (silently-dropped background
 * `startActivity` on Android 10+) must fire the cleanup; a claimed one must not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaunchWatchdogTest {

    @Test
    fun `an unclaimed launch fires the dropped callback after the timeout`() = runTest {
        var dropped = false
        backgroundScope.armLaunchWatchdog(timeoutMs = 3_000, isClaimed = { false }) {
            dropped = true
        }
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
        assertTrue(dropped)
    }

    @Test
    fun `a claimed launch never fires the dropped callback`() = runTest {
        var dropped = false
        backgroundScope.armLaunchWatchdog(timeoutMs = 3_000, isClaimed = { true }) {
            dropped = true
        }
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        assertFalse(dropped)
    }

    @Test
    fun `a launch claimed just before the timeout does not fire`() = runTest {
        var claimed = false
        var dropped = false
        backgroundScope.armLaunchWatchdog(timeoutMs = 3_000, isClaimed = { claimed }) {
            dropped = true
        }
        testScheduler.advanceTimeBy(2_999)
        testScheduler.runCurrent()
        claimed = true // the Activity's onCreate claims the handoff
        testScheduler.advanceTimeBy(2)
        testScheduler.runCurrent()
        assertFalse(dropped)
    }
}
