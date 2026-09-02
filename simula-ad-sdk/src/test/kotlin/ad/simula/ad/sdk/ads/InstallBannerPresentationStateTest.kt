package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.OverlayTiming
import ad.simula.ad.sdk.model.SkOverlayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallBannerPresentationStateTest {
    @Test
    fun `replacement owner observes on-click visibility from retained presentation`() {
        val state = state(OverlayTiming.ON_CLICK)
        val firstOwnerSnapshot = state.snapshot

        assertTrue(state.onPrimaryCtaAdmitted())
        val replacementOwnerSnapshot = state.snapshot

        assertFalse(firstOwnerSnapshot.visible)
        assertTrue(replacementOwnerSnapshot.visible)
        assertEquals(InstallBannerPhase.VISIBLE, replacementOwnerSnapshot.phase)
    }

    @Test
    fun `visible during-play banner survives recreation and starts only once`() {
        val state = state(OverlayTiming.DURING_PLAY)

        assertTrue(state.start())
        assertTrue(state.snapshot.visible)
        val replacementOwner = state

        assertTrue(replacementOwner.snapshot.visible)
        assertFalse(replacementOwner.start())
        assertEquals(InstallBannerPhase.VISIBLE, replacementOwner.snapshot.phase)
    }

    @Test
    fun `during-play preserves its configured deadline across recreation`() {
        var now = 100L
        val state = state(OverlayTiming.DURING_PLAY, delaySeconds = 2, clockMs = { now })

        assertTrue(state.start())
        assertEquals(InstallBannerPhase.SCHEDULED, state.snapshot.phase)
        assertEquals(2_100L, state.snapshot.deadlineMs)
        now = 1_100L
        assertFalse(state.start())
        assertEquals(1_000L, state.delayedRemainingMs())
        now = 2_100L
        assertTrue(state.onDelayedDeadlineReached())
        assertTrue(state.snapshot.visible)
    }

    @Test
    fun `dismissal is terminal across replacement owner`() {
        val state = state(OverlayTiming.DURING_PLAY)
        state.start()

        assertTrue(state.dismiss())
        val replacementOwner = state

        assertEquals(InstallBannerPhase.DISMISSED, replacementOwner.snapshot.phase)
        assertFalse(replacementOwner.snapshot.visible)
        assertFalse(replacementOwner.start())
    }

    @Test
    fun `delayed banner retains original deadline and remaining time`() {
        var now = 1_000L
        val state = state(OverlayTiming.DELAYED, delaySeconds = 5, clockMs = { now })

        assertTrue(state.start())
        assertEquals(6_000L, state.snapshot.deadlineMs)
        assertEquals(5_000L, state.delayedRemainingMs())

        now = 3_500L
        val replacementOwner = state
        assertFalse(replacementOwner.start())
        assertEquals(2_500L, replacementOwner.delayedRemainingMs())
        assertFalse(replacementOwner.onDelayedDeadlineReached())

        now = 6_000L
        assertTrue(replacementOwner.onDelayedDeadlineReached())
        assertTrue(replacementOwner.snapshot.visible)
        assertEquals(null, replacementOwner.delayedRemainingMs())
    }

    @Test
    fun `dismissed delayed banner cannot reschedule after recreation`() {
        var now = 10L
        val state = state(OverlayTiming.DELAYED, delaySeconds = 1, clockMs = { now })
        state.start()
        now = 1_010L
        state.onDelayedDeadlineReached()

        assertTrue(state.dismiss())
        val replacementOwner = state

        assertFalse(replacementOwner.start())
        assertFalse(replacementOwner.onDelayedDeadlineReached())
        assertEquals(null, replacementOwner.delayedRemainingMs())
        assertEquals(InstallBannerPhase.DISMISSED, replacementOwner.snapshot.phase)
    }

    @Test
    fun `dismissal suppresses scheduled and future on-click banners`() {
        val delayed = state(OverlayTiming.DELAYED, delaySeconds = 1)
        assertTrue(delayed.start())
        assertTrue(delayed.dismiss())
        assertFalse(delayed.onDelayedDeadlineReached())
        assertEquals(InstallBannerPhase.DISMISSED, delayed.snapshot.phase)

        val onClick = state(OverlayTiming.ON_CLICK)
        assertTrue(onClick.dismiss())
        assertFalse(onClick.onPrimaryCtaAdmitted())
        assertEquals(InstallBannerPhase.DISMISSED, onClick.snapshot.phase)
    }

    @Test
    fun `failed external route does not hide admitted on-click banner`() {
        val state = state(OverlayTiming.ON_CLICK)

        assertTrue(state.onPrimaryCtaAdmitted())
        val externalRouteOpened = false

        assertFalse(externalRouteOpened)
        assertTrue(state.snapshot.visible)
    }

    @Test
    fun `repeated events never create duplicate transitions`() {
        val state = state(OverlayTiming.ON_CLICK)

        assertFalse(state.start())
        assertTrue(state.onPrimaryCtaAdmitted())
        assertFalse(state.onPrimaryCtaAdmitted())
        assertFalse(state.start())
        assertTrue(state.dismiss())
        assertFalse(state.dismiss())
        assertFalse(state.onPrimaryCtaAdmitted())
        assertEquals(InstallBannerPhase.DISMISSED, state.snapshot.phase)
    }

    @Test
    fun `missing and disabled configs remain inert`() {
        val missing = InstallBannerPresentationState(config = null, clockMs = { 0L })
        val disabled = InstallBannerPresentationState(
            config = SkOverlayConfig(enabled = false, timing = OverlayTiming.DELAYED, delaySeconds = 1),
            clockMs = { 0L },
        )

        listOf(missing, disabled).forEach { state ->
            assertFalse(state.start())
            assertFalse(state.onPrimaryCtaAdmitted())
            assertFalse(state.onDelayedDeadlineReached())
            assertFalse(state.dismiss())
            assertEquals(InstallBannerPhase.HIDDEN, state.snapshot.phase)
        }
    }

    @Test
    fun `zero-delay delayed banner becomes visible immediately`() {
        val state = state(OverlayTiming.DELAYED, delaySeconds = 0)

        assertTrue(state.start())
        assertTrue(state.snapshot.visible)
        assertEquals(null, state.delayedRemainingMs())
    }

    private fun state(
        timing: OverlayTiming,
        delaySeconds: Int = 0,
        clockMs: () -> Long = { 0L },
    ) = InstallBannerPresentationState(
        config = SkOverlayConfig(
            enabled = true,
            timing = timing,
            delaySeconds = delaySeconds,
        ),
        clockMs = clockMs,
    )
}
