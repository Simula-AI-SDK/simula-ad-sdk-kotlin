package ad.simula.ad.sdk.ads

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The dropped-launch watchdog: an unclaimed handoff (silently-dropped background
 * `startActivity` on Android 10+) must fire the cleanup; a claimed one must not. The
 * cleanup runs on the main dispatcher with the claim re-checked there — a claim landing
 * between the (background) first check and the cleanup must win.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaunchWatchdogTest {

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

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

    @Test
    fun `a claim landing between the background check and the main cleanup wins`() = runTest {
        // Regression: the watchdog used to check once off-main and then tear down on main —
        // an Activity claiming in between had its live presentation torn down. The claim is
        // now re-checked on main (second check) and must win.
        val checks = AtomicInteger(0)
        var dropped = false
        backgroundScope.armLaunchWatchdog(
            timeoutMs = 3_000,
            isClaimed = { checks.incrementAndGet() >= 2 }, // false on the background check, true on the main re-check
        ) {
            dropped = true
        }
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
        assertEquals("claim must be checked twice (background + main re-check)", 2, checks.get())
        assertFalse(dropped)
    }
}
