package ad.simula.ad.sdk.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityVisibilityStateTest {
    @Test
    fun `less than thirty minutes does not expire the session`() {
        var nowMs = 10L
        val state = ActivityVisibilityState(clock = { nowMs })
        val activity = Any()

        assertFalse(state.onActivityStarted(activity))
        assertTrue(state.onActivityStopped(activity, changingConfigurations = false))
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS - 1L

        assertFalse(state.onActivityStarted(activity))
        assertEquals(0L, state.sessionGeneration)
    }

    @Test
    fun `exactly thirty minutes expires the session`() {
        var nowMs = 100L
        val state = ActivityVisibilityState(clock = { nowMs })
        val activity = Any()

        state.onActivityStarted(activity)
        state.onActivityStopped(activity, changingConfigurations = false)
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS

        assertTrue(state.onActivityStarted(activity))
        assertEquals(1L, state.sessionGeneration)
        assertFalse(state.onActivityStarted(activity))
    }

    @Test
    fun `elapsed realtime jump beyond thirty minutes models deep sleep`() {
        var elapsedRealtimeMs = 5_000L
        val state = ActivityVisibilityState(clock = { elapsedRealtimeMs })
        val activity = Any()

        state.onActivityStarted(activity)
        state.onActivityStopped(activity, changingConfigurations = false)
        elapsedRealtimeMs += SESSION_BACKGROUND_EXPIRATION_MS + 12L * 60L * 60L * 1_000L

        assertTrue(state.onActivityStarted(activity))
        assertEquals(1L, state.sessionGeneration)
    }

    @Test
    fun `background process initialization expires on first foreground after thirty minutes`() {
        var nowMs = 2_000L
        val state = ActivityVisibilityState(clock = { nowMs })

        assertTrue(state.seedBackgroundedProcess())
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS

        assertTrue(state.onActivityStarted(Any()))
        assertEquals(1L, state.sessionGeneration)
    }

    @Test
    fun `repeated background initialization keeps the original interval`() {
        var nowMs = 2_000L
        val state = ActivityVisibilityState(clock = { nowMs })

        assertTrue(state.seedBackgroundedProcess())
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS - 1L
        assertFalse(state.seedBackgroundedProcess())
        nowMs++

        assertTrue(state.onActivityStarted(Any()))
        assertEquals(1L, state.sessionGeneration)
    }

    @Test
    fun `ordinary and sdk fullscreen activity transitions never establish background`() {
        var nowMs = 0L
        val state = ActivityVisibilityState(clock = { nowMs })
        val host = Any()
        val secondHost = Any()
        val sdkFullscreen = Any()

        state.onActivityStarted(host)
        state.onActivityStarted(secondHost)
        assertFalse(state.onActivityStopped(host, changingConfigurations = false))
        state.onActivityStarted(sdkFullscreen)
        assertFalse(state.onActivityStopped(secondHost, changingConfigurations = false))
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS * 2L
        assertFalse(state.onActivityStarted(host))
        assertFalse(state.onActivityStopped(sdkFullscreen, changingConfigurations = false))

        assertEquals(0L, state.sessionGeneration)
    }

    @Test
    fun `configuration stop does not establish background`() {
        var nowMs = 0L
        val state = ActivityVisibilityState(clock = { nowMs })
        val oldActivity = Any()
        val recreatedActivity = Any()

        state.onActivityStarted(oldActivity)
        assertFalse(state.onActivityStopped(oldActivity, changingConfigurations = true))
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS

        assertFalse(state.onActivityStarted(recreatedActivity))
        assertEquals(0L, state.sessionGeneration)
    }

    @Test
    fun `multiple started activities prevent a false background`() {
        var nowMs = 0L
        val state = ActivityVisibilityState(clock = { nowMs })
        val first = Any()
        val second = Any()

        state.onActivityStarted(first)
        state.onActivityStarted(second)
        assertFalse(state.onActivityStopped(first, changingConfigurations = false))
        assertEquals(1, state.startedActivityCount)
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS

        assertFalse(state.onActivityStarted(first))
        assertEquals(0L, state.sessionGeneration)
    }

    @Test
    fun `late registration seed participates in the first real background`() {
        var nowMs = 0L
        val state = ActivityVisibilityState(clock = { nowMs })
        val alreadyStarted = Any()

        state.seedStartedActivity(alreadyStarted)
        assertTrue(state.onActivityStopped(alreadyStarted, changingConfigurations = false))
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS

        assertTrue(state.onActivityStarted(alreadyStarted))
    }

    @Test
    fun `late registration without activity context observes the first stop`() {
        var nowMs = 0L
        val state = ActivityVisibilityState(clock = { nowMs })
        val alreadyStarted = Any()

        assertTrue(state.onActivityStopped(alreadyStarted, changingConfigurations = false))
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS

        assertTrue(state.onActivityStarted(alreadyStarted))
    }

    @Test
    fun `untracked visible host prevents overlay stop from starting background`() {
        var nowMs = 0L
        val state = ActivityVisibilityState(clock = { nowMs })
        val overlay = Any()

        state.seedUntrackedStartedActivity()
        state.onActivityStarted(overlay)
        assertFalse(
            state.onActivityStopped(
                overlay,
                changingConfigurations = false,
                processHasVisibleUi = true,
            ),
        )
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS

        assertFalse(state.onActivityStarted(Any()))
        assertEquals(0L, state.sessionGeneration)
    }

    @Test
    fun `ui hidden begins late registration background interval`() {
        var nowMs = 0L
        val state = ActivityVisibilityState(clock = { nowMs })
        val host = Any()

        state.seedUntrackedStartedActivity()
        assertFalse(
            state.onActivityStopped(
                host,
                changingConfigurations = false,
                processHasVisibleUi = true,
            ),
        )
        assertTrue(state.onUiHidden())
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS

        assertTrue(state.onActivityStarted(host))
        assertEquals(1L, state.sessionGeneration)
    }

    @Test
    fun `concrete activity seed retains uncertainty until aggregate ui hidden`() {
        var nowMs = 0L
        val state = ActivityVisibilityState(clock = { nowMs })
        val host = Any()

        state.seedUntrackedStartedActivity()
        state.seedStartedActivity(host)
        assertFalse(
            state.onActivityStopped(
                host,
                changingConfigurations = false,
                processHasVisibleUi = true,
            ),
        )
        assertTrue(state.onUiHidden())
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS

        assertTrue(state.onActivityStarted(host))
        assertEquals(1L, state.sessionGeneration)
    }

    @Test
    fun `ui hidden overrides stale tracked activity ordering`() {
        var nowMs = 0L
        val state = ActivityVisibilityState(clock = { nowMs })
        val host = Any()

        state.seedUntrackedStartedActivity()
        state.onActivityStarted(host)

        assertTrue(state.onUiHidden())
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS
        assertTrue(state.onActivityStarted(host))
    }

    @Test
    fun `phantom launch seed clears when last activity stops without visible ui`() {
        var nowMs = 0L
        val state = ActivityVisibilityState(clock = { nowMs })
        val host = Any()

        state.seedUntrackedStartedActivity()
        state.onActivityStarted(host)
        assertTrue(
            state.onActivityStopped(
                host,
                changingConfigurations = false,
                processHasVisibleUi = false,
            ),
        )
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS

        assertTrue(state.onActivityStarted(host))
        assertEquals(1L, state.sessionGeneration)
    }

    @Test
    fun `late uncertainty seed cannot erase recorded background`() {
        var nowMs = 0L
        val state = ActivityVisibilityState(clock = { nowMs })
        val host = Any()

        state.onActivityStarted(host)
        assertTrue(state.onActivityStopped(host, changingConfigurations = false))
        state.seedUntrackedStartedActivity()
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS

        assertTrue(state.onActivityStarted(host))
        assertEquals(1L, state.sessionGeneration)
    }

    @Test
    fun `concrete seed consumes an expired recorded background`() {
        var nowMs = 0L
        val state = ActivityVisibilityState(clock = { nowMs })
        val host = Any()

        state.onActivityStarted(host)
        state.onActivityStopped(host, changingConfigurations = false)
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS

        assertTrue(state.seedStartedActivity(host))
        assertEquals(1L, state.sessionGeneration)
    }

    @Test
    fun `untracked stop cannot background while another activity is started`() {
        val state = ActivityVisibilityState(clock = { 0L })
        val tracked = Any()

        state.onActivityStarted(tracked)

        assertFalse(state.onActivityStopped(Any(), changingConfigurations = false))
        assertEquals(1, state.startedActivityCount)
    }

    @Test
    fun `duplicate stop does not shorten the continuous background interval`() {
        var nowMs = 0L
        val state = ActivityVisibilityState(clock = { nowMs })
        val activity = Any()

        state.onActivityStarted(activity)
        assertTrue(state.onActivityStopped(activity, changingConfigurations = false))
        nowMs += SESSION_BACKGROUND_EXPIRATION_MS - 1L
        assertFalse(state.onActivityStopped(activity, changingConfigurations = false))
        nowMs++

        assertTrue(state.onActivityStarted(activity))
    }

    @Test
    fun `unfocused repeated seed does not replace resumed current activity`() {
        val state = CurrentActivityState<Any>()
        val resumed = Any()
        val merelyStarted = Any()

        state.onResumed(resumed)
        state.seed(merelyStarted, safeToPresent = false)

        assertTrue(state.current === resumed)
    }

    @Test
    fun `safe seed and resume publish current while destroy clears only its target`() {
        val state = CurrentActivityState<Any>()
        val focusedSeed = Any()
        val resumed = Any()

        state.seed(focusedSeed, safeToPresent = true)
        assertTrue(state.current === focusedSeed)
        state.onResumed(resumed)
        state.onDestroyed(focusedSeed)
        assertTrue(state.current === resumed)
        state.onDestroyed(resumed)
        assertTrue(state.current == null)
    }

    @Test
    fun `seed presentation safety requires focused live activity`() {
        assertTrue(isSafeCurrentActivitySeed(hasWindowFocus = true, isFinishing = false, isDestroyed = false))
        assertFalse(isSafeCurrentActivitySeed(hasWindowFocus = false, isFinishing = false, isDestroyed = false))
        assertFalse(isSafeCurrentActivitySeed(hasWindowFocus = true, isFinishing = true, isDestroyed = false))
        assertFalse(isSafeCurrentActivitySeed(hasWindowFocus = true, isFinishing = false, isDestroyed = true))
    }
}
