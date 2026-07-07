package ad.simula.ad.sdk.network

import android.media.AudioManager
import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pure signal mappers and the header assembly for [SimulaDeviceSignals]. The device
 * reads themselves need a live Android runtime, so only the pure logic is unit-tested here (Android
 * `static final int` constants are compile-time inlined, so no Robolectric is required).
 */
class SimulaDeviceSignalsTest {

    // ── volumePercent ─────────────────────────────────────────────────────────

    @Test
    fun `volume percent maps to 0-100`() {
        assertEquals(0, SimulaDeviceSignals.volumePercent(0, 15))
        assertEquals(100, SimulaDeviceSignals.volumePercent(15, 15))
        assertEquals(50, SimulaDeviceSignals.volumePercent(5, 10))
    }

    @Test
    fun `volume percent is null when unreadable`() {
        assertNull(SimulaDeviceSignals.volumePercent(null, 15))
        assertNull(SimulaDeviceSignals.volumePercent(5, null))
        assertNull(SimulaDeviceSignals.volumePercent(5, 0))
        assertNull(SimulaDeviceSignals.volumePercent(5, -1))
    }

    // ── batteryPercent ────────────────────────────────────────────────────────

    @Test
    fun `battery percent passes through valid range and rejects unknown`() {
        assertEquals(0, SimulaDeviceSignals.batteryPercent(0))
        assertEquals(87, SimulaDeviceSignals.batteryPercent(87))
        assertEquals(100, SimulaDeviceSignals.batteryPercent(100))
        assertNull(SimulaDeviceSignals.batteryPercent(-1))
        assertNull(SimulaDeviceSignals.batteryPercent(101))
        assertNull(SimulaDeviceSignals.batteryPercent(null))
    }

    // ── batteryStateLabel ─────────────────────────────────────────────────────

    @Test
    fun `battery state maps to stable labels`() {
        assertEquals("charging", SimulaDeviceSignals.batteryStateLabel(BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals("full", SimulaDeviceSignals.batteryStateLabel(BatteryManager.BATTERY_STATUS_FULL))
        // Discharging (on battery) is the only "unplugged" state; NOT_CHARGING means plugged in but
        // not accepting charge, so it maps to its own label rather than being misreported as unplugged.
        assertEquals("unplugged", SimulaDeviceSignals.batteryStateLabel(BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals("not_charging", SimulaDeviceSignals.batteryStateLabel(BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        assertEquals("unknown", SimulaDeviceSignals.batteryStateLabel(BatteryManager.BATTERY_STATUS_UNKNOWN))
        assertNull(SimulaDeviceSignals.batteryStateLabel(null))
        assertNull(SimulaDeviceSignals.batteryStateLabel(999))
    }

    // ── ringerModeLabel ───────────────────────────────────────────────────────

    @Test
    fun `ringer mode maps to stable labels`() {
        assertEquals("normal", SimulaDeviceSignals.ringerModeLabel(AudioManager.RINGER_MODE_NORMAL))
        assertEquals("vibrate", SimulaDeviceSignals.ringerModeLabel(AudioManager.RINGER_MODE_VIBRATE))
        assertEquals("silent", SimulaDeviceSignals.ringerModeLabel(AudioManager.RINGER_MODE_SILENT))
        assertNull(SimulaDeviceSignals.ringerModeLabel(null))
        assertNull(SimulaDeviceSignals.ringerModeLabel(999))
    }

    // ── buildHeaders ──────────────────────────────────────────────────────────

    @Test
    fun `build headers emits every available signal`() {
        val headers = SimulaDeviceSignals.buildHeaders(
            timezone = "America/Sao_Paulo",
            storageFreeBytes = 123_456L,
            memoryFreeBytes = 654_321L,
            batteryLevel = 87,
            batteryStatus = BatteryManager.BATTERY_STATUS_CHARGING,
            volumeCurrent = 5,
            volumeMax = 10,
            ringerMode = AudioManager.RINGER_MODE_NORMAL,
        )
        assertEquals("America/Sao_Paulo", headers["X-Timezone"])
        assertEquals("123456", headers["X-Storage-Free"])
        assertEquals("654321", headers["X-Memory-Free"])
        assertEquals("87", headers["X-Battery-Level"])
        assertEquals("charging", headers["X-Battery-State"])
        assertEquals("50", headers["X-Volume"])
        assertEquals("normal", headers["X-Ringer-Mode"])
    }

    @Test
    fun `build headers omits unavailable signals`() {
        val headers = SimulaDeviceSignals.buildHeaders(
            timezone = "  ",
            storageFreeBytes = -1L,
            memoryFreeBytes = null,
            batteryLevel = -1,
            batteryStatus = null,
            volumeCurrent = 5,
            volumeMax = 0,
            ringerMode = 999,
        )
        assertTrue(headers.isEmpty())
        assertFalse(headers.containsKey("X-Timezone"))
        assertFalse(headers.containsKey("X-Volume"))
    }
}
