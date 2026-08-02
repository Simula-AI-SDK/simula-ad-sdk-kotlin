package ad.simula.ad.sdk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryLateInitBufferTest {
    @Test
    fun `latest pre-init server directive wins and drains once`() {
        val buffer = TelemetryLateInitBuffer()
        buffer.updateServerDirective(enabled = true, sampleRate = 0.8)
        buffer.updateServerDirective(enabled = false, sampleRate = 0.2)

        val first = buffer.drainForInitialization()
        assertEquals(TelemetryServerDirective(false, 0.2), first.serverDirective)
        assertNull(buffer.drainForInitialization().serverDirective)
    }

    @Test
    fun `missing provider context coalesces before init and is consumed once`() {
        val buffer = TelemetryLateInitBuffer()
        repeat(10) { buffer.requestMissingProviderContext() }

        assertTrue(buffer.drainForInitialization().reportMissingProviderContext)
        buffer.requestMissingProviderContext()
        assertFalse(buffer.drainForInitialization().reportMissingProviderContext)
    }

    @Test
    fun `missing provider context can be consumed immediately after init`() {
        val buffer = TelemetryLateInitBuffer()

        buffer.requestMissingProviderContext()
        assertTrue(buffer.consumeMissingProviderContext())
        assertFalse(buffer.consumeMissingProviderContext())
    }
}
