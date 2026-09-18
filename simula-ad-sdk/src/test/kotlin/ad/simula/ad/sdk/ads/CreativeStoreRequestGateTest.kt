package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.network.ClickInteractionGate
import ad.simula.ad.sdk.network.ClickPersistenceHandoff
import ad.simula.ad.sdk.network.ClickSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreativeStoreRequestGateTest {
    @Test
    fun `dismiss cancels pending handoff and permanently suppresses opening`() {
        val clickGate = ClickInteractionGate(clockMs = { 1L }, idFactory = { "store-click" })
        val claim = requireNotNull(clickGate.claim(ClickSources.PRIMARY_CTA))
        val handoff = ClickPersistenceHandoff(claim) {}
        val gate = CreativeStoreRequestGate()

        assertTrue(gate.canRequest())
        assertTrue(gate.track(handoff))
        gate.dismiss()

        assertTrue(handoff.isTerminal())
        assertFalse(gate.canRequest())
        assertFalse(gate.canOpen())
    }
}
