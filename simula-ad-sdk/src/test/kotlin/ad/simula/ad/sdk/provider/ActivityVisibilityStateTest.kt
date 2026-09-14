package ad.simula.ad.sdk.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityVisibilityStateTest {
    @Test
    fun `genuine background then foreground emits once`() {
        val state = ActivityVisibilityState()
        val activity = Any()

        assertFalse(state.onActivityStarted(activity))
        val generation = state.onActivityStopped(activity, changingConfigurations = false)
        assertNotNull(generation)
        assertTrue(state.settleBackground(generation ?: error("missing generation")))

        assertTrue(state.onActivityStarted(activity))
        assertFalse(state.onActivityStarted(activity))
    }

    @Test
    fun `ordinary and sdk fullscreen activity transitions do not emit foreground`() {
        val state = ActivityVisibilityState()
        val host = Any()
        val secondHost = Any()
        val sdkFullscreen = Any()

        state.onActivityStarted(host)
        val hostTransition = state.onActivityStopped(host, changingConfigurations = false)
        assertNotNull(hostTransition)
        assertFalse(state.onActivityStarted(secondHost))
        assertFalse(state.settleBackground(hostTransition ?: error("missing generation")))

        val sdkTransition = state.onActivityStopped(secondHost, changingConfigurations = false)
        assertNotNull(sdkTransition)
        assertFalse(state.onActivityStarted(sdkFullscreen))
        assertFalse(state.settleBackground(sdkTransition ?: error("missing generation")))

        val returnTransition = state.onActivityStopped(sdkFullscreen, changingConfigurations = false)
        assertNotNull(returnTransition)
        assertFalse(state.onActivityStarted(host))
        assertFalse(state.settleBackground(returnTransition ?: error("missing generation")))
    }

    @Test
    fun `configuration stop does not establish background`() {
        val state = ActivityVisibilityState()
        val oldActivity = Any()
        val recreatedActivity = Any()

        state.onActivityStarted(oldActivity)
        assertNull(state.onActivityStopped(oldActivity, changingConfigurations = true))
        assertFalse(state.onActivityStarted(recreatedActivity))
    }

    @Test
    fun `multiple started activities prevent a false background`() {
        val state = ActivityVisibilityState()
        val first = Any()
        val second = Any()

        state.onActivityStarted(first)
        state.onActivityStarted(second)
        assertNull(state.onActivityStopped(first, changingConfigurations = false))
        assertEquals(1, state.startedActivityCount)
        assertFalse(state.onActivityStarted(first))
    }

    @Test
    fun `late registration seed participates in the first real background`() {
        val state = ActivityVisibilityState()
        val alreadyStarted = Any()

        state.seedStartedActivity(alreadyStarted)
        val generation = state.onActivityStopped(alreadyStarted, changingConfigurations = false)
        assertNotNull(generation)
        assertTrue(state.settleBackground(generation ?: error("missing generation")))
        assertTrue(state.onActivityStarted(alreadyStarted))
    }

    @Test
    fun `late registration without activity context observes first stop as background`() {
        val state = ActivityVisibilityState()
        val alreadyStarted = Any()

        val generation = state.onActivityStopped(alreadyStarted, changingConfigurations = false)

        assertNotNull(generation)
        assertTrue(state.settleBackground(generation ?: error("missing generation")))
        assertTrue(state.onActivityStarted(alreadyStarted))
    }

    @Test
    fun `untracked stop cannot background while another activity is started`() {
        val state = ActivityVisibilityState()
        val tracked = Any()

        state.onActivityStarted(tracked)

        assertNull(state.onActivityStopped(Any(), changingConfigurations = false))
        assertEquals(1, state.startedActivityCount)
    }

    @Test
    fun `late untracked stop cannot background while process UI remains visible`() {
        val state = ActivityVisibilityState()
        val generation = state.onActivityStopped(Any(), changingConfigurations = false)

        assertNotNull(generation)
        assertFalse(
            state.settleBackground(
                generation ?: error("missing generation"),
                processHasVisibleUi = true,
            ),
        )
        assertFalse(state.onActivityStarted(Any()))
    }
}
