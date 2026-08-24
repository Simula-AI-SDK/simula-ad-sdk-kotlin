package ad.simula.ad.sdk.telemetry

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteTelemetryStoreInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    @Test
    fun duplicateLegacyEventIsVerifiedAndMigrationClearsPreferences() {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        context.deleteDatabase(DATABASE_NAME)
        prefs.edit().clear().commit()
        try {
            val now = System.currentTimeMillis()
            val duplicate = TelemetryEvent("error", "existing", "event-1", now)
            val newEvent = TelemetryEvent("operation", "new", "event-2", now + 1L)
            assertEquals(true, SqliteTelemetryStore(context, json).save(listOf(duplicate)))
            prefs.edit().putString(LEGACY_KEY, json.encodeToString(listOf(duplicate, newEvent))).commit()

            val migrated = SqliteTelemetryStore(context, json).load()

            assertEquals(setOf("event-1", "event-2"), migrated.map { it.eventId }.toSet())
            assertFalse(prefs.contains(LEGACY_KEY))
        } finally {
            prefs.edit().clear().commit()
            context.deleteDatabase(DATABASE_NAME)
        }
    }

    private companion object {
        const val DATABASE_NAME = "simula_ad_sdk_telemetry.db"
        const val LEGACY_PREFS = "simula_ad_sdk_telemetry_prefs"
        const val LEGACY_KEY = "pending_telemetry_events"
    }
}
