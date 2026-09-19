package ad.simula.ad.sdk.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardedCreativeSourceTest {
    @Test
    fun `rendered HTML is accepted`() {
        assertEquals(
            RewardedCreativeSource.Html("<html>primary</html>"),
            rewardedCreativeSource(renderedHtml = "<html>primary</html>"),
        )
    }

    @Test
    fun `missing or blank HTML has no playable source`() {
        assertNull(rewardedCreativeSource(null))
        assertNull(rewardedCreativeSource(" "))
    }

    @Test
    fun `creative commit requires armed expected source without main frame failure`() {
        val html = RewardedCreativeSource.Html("<html/>")

        assertFalse(isQualifiedRewardedCreativeCommit(html, false, false, "about:blank"))
        assertFalse(isQualifiedRewardedCreativeCommit(html, true, true, "about:blank"))
        assertFalse(isQualifiedRewardedCreativeCommit(html, true, false, "about:blank"))
    }

    @Test
    fun `HTML readiness requires current installation page ready before visual state`() {
        val gate = RewardedHtmlReadinessGate()

        assertNull(gate.onPageReady())
        gate.arm()
        val stale = requireNotNull(gate.onPageReady())
        gate.onPageStarted()
        assertFalse(gate.acceptVisualState(stale))

        val current = requireNotNull(gate.onPageReady())
        assertTrue(gate.acceptVisualState(current))
        assertFalse(gate.acceptVisualState(current))

        gate.arm()
        gate.onPageStarted()
        gate.onPageStarted()
        assertNull(gate.onPageReady())
    }

    @Test
    fun `HTML readiness terminal state rejects late visual callbacks`() {
        val gate = RewardedHtmlReadinessGate()
        gate.arm()
        val request = requireNotNull(gate.onPageReady())

        gate.terminate()

        assertFalse(gate.acceptVisualState(request))
        assertNull(gate.onPageReady())
    }

    @Test
    fun `creative timeout retains remaining foreground budget`() {
        val budget = ForegroundTimeoutBudget(totalMs = 10_000L)

        assertEquals(10_000L, budget.resume(nowMs = 1_000L))
        budget.pause(nowMs = 4_000L)
        assertEquals(7_000L, budget.resume(nowMs = 9_000L))
        budget.pause(nowMs = 10_500L)
        assertEquals(5_500L, budget.resume(nowMs = 20_000L))

        budget.complete()
        assertEquals(0L, budget.resume(nowMs = 21_000L))
    }
}
