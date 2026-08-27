package ad.simula.ad.sdk.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStateDeliveryGateTest {

    private val audible = CreativeAudioState(muted = false, volume = 50)
    private val muted = CreativeAudioState(muted = true, volume = 0)

    @Test
    fun requiresAReadyCreativeDocument() {
        val gate = AudioStateDeliveryGate()

        assertFalse(gate.shouldDeliver(audible))
        assertFalse(gate.onPageReady(""))
        assertTrue(gate.onPageReady("page-a"))
        assertTrue(gate.shouldDeliver(audible))
    }

    @Test
    fun deduplicatesUntilThePageReloads() {
        val gate = AudioStateDeliveryGate()
        gate.onPageReady("page-a")

        assertTrue(gate.shouldDeliver(audible))
        assertFalse(gate.shouldDeliver(audible))
        assertTrue(gate.shouldDeliver(muted))

        assertTrue(gate.onPageReady("page-b"))
        assertTrue(gate.shouldDeliver(muted))
    }

    @Test
    fun duplicateReadySignalDoesNotRepeatTheInitialEvent() {
        val gate = AudioStateDeliveryGate()
        assertTrue(gate.onPageReady("page-a"))
        assertTrue(gate.shouldDeliver(audible))

        assertFalse(gate.onPageReady("page-a"))
        assertFalse(gate.shouldDeliver(audible))
    }

    @Test
    fun navigationStartDisarmsTheOldDocumentUntilANewPageIsReady() {
        val gate = AudioStateDeliveryGate()
        gate.onPageReady("page-a")
        assertTrue(gate.shouldDeliver(audible))

        gate.onPageStarted()

        assertFalse(gate.shouldDeliver(muted))
        assertFalse("a late ready signal from the old page stays rejected", gate.onPageReady("page-a"))
        assertFalse(gate.shouldDeliver(muted))
        assertTrue(gate.onPageReady("page-b"))
        assertTrue(gate.shouldDeliver(muted))
    }

    @Test
    fun closePermanentlySuppressesDelivery() {
        val gate = AudioStateDeliveryGate()
        gate.onPageReady("page-a")
        gate.close()
        gate.close()

        assertFalse(gate.onPageReady("page-b"))
        assertFalse(gate.shouldDeliver(audible))
    }

    @Test
    fun readyMessagesAreBoundedAndBoundToOneInstallation() {
        assertEquals("page-a", readyPageId("__simulaSdkPageReady:17:page-a", "17"))
        assertNull(readyPageId("__simulaSdkPageReady:18:page-a", "17"))
        assertNull(readyPageId("__simulaSdkPageReady:17:", "17"))
        assertNull(readyPageId("__simulaSdkPageReady:17:" + "x".repeat(256), "17"))
    }

    @Test
    fun cleanupAlwaysRunsBeforePooling() {
        val events = mutableListOf<String>()

        cleanupBeforePooling(
            cleanup = { events += "cleanup"; true },
            release = { events += "release" },
            discard = { events += "discard" },
        )

        assertEquals(listOf("cleanup", "release"), events)
    }

    @Test
    fun cleanupFailureDiscardsInsteadOfPoolingOrEscaping() {
        val events = mutableListOf<String>()

        cleanupBeforePooling(
            cleanup = {
                events += "cleanup"
                error("failure")
            },
            release = { events += "release" },
            discard = { events += "discard" },
        )

        assertEquals(listOf("cleanup", "discard"), events)
    }
}
