package ad.simula.ad.sdk.nativead

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier-0 pure-logic tests for the native-ad viewability dwell (≥threshold for ≥1 continuous second,
 * with hysteresis around the threshold), independent of Compose/lifecycle. Times are fed explicitly
 * so the 1s window is exercised without real delays.
 */
class ViewabilityDwellTest {

    private fun dwell(enter: Float = 0.5f, min: Long = 1_000L) = ViewabilityDwell(enter, min)

    @Test
    fun `fires after one continuous second at or above the threshold`() {
        val d = dwell()
        assertFalse(d.sample(0.6f, 0L))      // arms the dwell at t=0
        assertFalse(d.sample(0.6f, 500L))    // 0.5s in
        assertFalse(d.sample(0.6f, 999L))    // just shy of 1s
        assertTrue(d.sample(0.6f, 1_000L))   // crosses exactly at 1s
    }

    @Test
    fun `enters at exactly the threshold and the exit floor is inclusive`() {
        val d = dwell()
        assertFalse(d.sample(0.5f, 0L))      // exactly 50% enters
        assertFalse(d.sample(0.4f, 500L))    // exactly 40% == exit floor (0.5*0.8) → stays viewable
        assertTrue(d.sample(0.4f, 1_000L))
    }

    @Test
    fun `never fires below the enter threshold`() {
        val d = dwell()
        assertFalse(d.sample(0.49f, 0L))
        assertFalse(d.sample(0.49f, 5_000L))
    }

    @Test
    fun `dwell resets when visibility drops below the exit band`() {
        val d = dwell()
        assertFalse(d.sample(0.6f, 0L))      // arm
        assertFalse(d.sample(0.6f, 800L))
        assertFalse(d.sample(0.2f, 900L))    // well below exit (0.4) → reset
        assertFalse(d.sample(0.6f, 1_500L))  // re-arm at 1500
        assertFalse(d.sample(0.6f, 2_499L))  // only ~0.999s since re-arm
        assertTrue(d.sample(0.6f, 2_500L))   // a full 1s since the re-arm
    }

    @Test
    fun `hysteresis keeps the dwell alive on jitter between the exit and enter thresholds`() {
        val d = dwell()
        assertFalse(d.sample(0.55f, 0L))     // enter (>=0.5) arms
        // Sub-pixel jitter parks it at 0.45 — below enter (0.5) but above exit (0.4): stays viewable,
        // dwell is NOT reset. This is the case the old edge-triggered gate kept resetting.
        assertFalse(d.sample(0.45f, 500L))
        assertTrue(d.sample(0.45f, 1_000L))  // dwell survived the jitter → fires at 1s
    }

    @Test
    fun `a dip just below the exit floor while viewable still resets the dwell`() {
        val d = dwell()
        assertFalse(d.sample(0.55f, 0L))     // arm
        assertFalse(d.sample(0.39f, 500L))   // below exit (0.4) → reset
        assertFalse(d.sample(0.55f, 1_400L)) // re-arm at 1400
        assertFalse(d.sample(0.55f, 2_399L))
        assertTrue(d.sample(0.55f, 2_400L))  // 1s after the re-arm
    }

    @Test
    fun `keeps reporting true after crossing (caller is responsible for firing once)`() {
        val d = dwell()
        d.sample(0.6f, 0L)
        assertTrue(d.sample(0.6f, 1_000L))
        // The composable stops sampling after the first true; the machine itself keeps returning true.
        assertTrue(d.sample(0.6f, 1_200L))
    }
}
