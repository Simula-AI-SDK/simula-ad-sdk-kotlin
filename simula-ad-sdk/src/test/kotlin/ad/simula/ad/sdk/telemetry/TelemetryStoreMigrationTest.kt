package ad.simula.ad.sdk.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryStoreMigrationTest {
    @Test
    fun `ignored migration insert succeeds only when exact event id exists`() {
        assertTrue(telemetryMigrationInsertSucceeded(-1L, exactEventExists = true))
        assertFalse(telemetryMigrationInsertSucceeded(-1L, exactEventExists = false))
        assertTrue(telemetryMigrationInsertSucceeded(1L, exactEventExists = false))
    }
}
