package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.network.ClickInteractionGate
import ad.simula.ad.sdk.network.ClickPersistenceHandoff
import ad.simula.ad.sdk.network.ClickPersistencePart
import ad.simula.ad.sdk.network.ClickSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenClickHandoffPolicyTest {
    @Test
    fun `dismissal requires unlocked close and no pending click`() {
        assertFalse(canDismissFullscreen(dismissUnlocked = false, hasPendingClick = false))
        assertFalse(canDismissFullscreen(dismissUnlocked = false, hasPendingClick = true))
        assertFalse(canDismissFullscreen(dismissUnlocked = true, hasPendingClick = true))
        assertTrue(canDismissFullscreen(dismissUnlocked = true, hasPendingClick = false))
    }

    @Test
    fun `dismissal stays blocked until persisted click handoff reaches route`() {
        val gate = ClickInteractionGate(idFactory = { "interaction" })
        val handoff = ClickPersistenceHandoff(
            requireNotNull(gate.claim(ClickSources.INSTALL_BANNER)),
        ) {}

        assertFalse(canDismissFullscreen(dismissUnlocked = true, hasPendingClick = gate.hasPendingClaim()))
        handoff.complete(ClickPersistencePart.TELEMETRY)
        handoff.complete(ClickPersistencePart.BEACON)
        assertFalse(canDismissFullscreen(dismissUnlocked = true, hasPendingClick = gate.hasPendingClaim()))

        assertTrue(handoff.handoff { true })
        assertTrue(canDismissFullscreen(dismissUnlocked = true, hasPendingClick = gate.hasPendingClaim()))
    }
}
