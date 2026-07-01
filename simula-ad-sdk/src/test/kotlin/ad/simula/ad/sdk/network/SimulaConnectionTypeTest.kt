package ad.simula.ad.sdk.network

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the `X-Connection-Type` OpenRTB `device.connectiontype` mapping. Exercises the pure
 * [SimulaConnectionType.classify] / [SimulaConnectionType.generationForNetworkType] overloads so
 * no live [android.net.NetworkCapabilities] / [TelephonyManager] instance is needed.
 */
class SimulaConnectionTypeTest {

    @Test
    fun `wifi maps to 2`() {
        val value = SimulaConnectionType.classify(
            isWifi = true, isEthernet = false, isCellular = false, cellularGeneration = { fail() },
        )
        assertEquals(2, value)
    }

    @Test
    fun `ethernet maps to 1 (wired)`() {
        val value = SimulaConnectionType.classify(
            isWifi = false, isEthernet = true, isCellular = false, cellularGeneration = { fail() },
        )
        assertEquals(1, value)
    }

    @Test
    fun `no transport maps to 0 (unknown offline)`() {
        val value = SimulaConnectionType.classify(
            isWifi = false, isEthernet = false, isCellular = false, cellularGeneration = { fail() },
        )
        assertEquals(0, value)
    }

    @Test
    fun `cellular defers to the supplied generation`() {
        val value = SimulaConnectionType.classify(
            isWifi = false, isEthernet = false, isCellular = true, cellularGeneration = { 7 },
        )
        assertEquals(7, value)
    }

    @Test
    fun `wifi takes priority over cellular when both transports are reported`() {
        // A device can have both wifi and cellular capabilities up at once; wifi (the data path
        // actually used) must win and the cellular generation lookup must be skipped entirely.
        val value = SimulaConnectionType.classify(
            isWifi = true, isEthernet = false, isCellular = true, cellularGeneration = { fail() },
        )
        assertEquals(2, value)
    }

    @Test
    fun `generationForNetworkType maps 5G LTE 3G and 2G`() {
        assertEquals(7, SimulaConnectionType.generationForNetworkType(TelephonyManager.NETWORK_TYPE_NR))
        assertEquals(6, SimulaConnectionType.generationForNetworkType(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals(5, SimulaConnectionType.generationForNetworkType(TelephonyManager.NETWORK_TYPE_HSDPA))
        assertEquals(4, SimulaConnectionType.generationForNetworkType(TelephonyManager.NETWORK_TYPE_EDGE))
    }

    @Test
    fun `generationForNetworkType falls back to 3 (unknown gen) for an undetermined type`() {
        assertEquals(3, SimulaConnectionType.generationForNetworkType(TelephonyManager.NETWORK_TYPE_UNKNOWN))
    }

    /** Fails the test if the cellular-generation lookup runs when it shouldn't (wifi/ethernet/offline). */
    private fun fail(): Int = throw AssertionError("cellularGeneration() must not be invoked for a non-cellular transport")
}
