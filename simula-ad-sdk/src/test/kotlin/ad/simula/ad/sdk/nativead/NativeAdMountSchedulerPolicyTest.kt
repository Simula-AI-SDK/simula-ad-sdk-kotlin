package ad.simula.ad.sdk.nativead

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeAdMountSchedulerPolicyTest {

    @Test
    fun `each admission tick returns at most one eligible mount`() {
        val queue = NativeMountAdmissionQueue<String>()
        queue.add("first")
        queue.add("second")
        queue.add("third")

        assertEquals("first", queue.takeNext { true })
        assertEquals("second", queue.takeNext { true })
        assertEquals("third", queue.takeNext { true })
        assertNull(queue.takeNext { true })
    }

    @Test
    fun `cancelled mounts are skipped without consuming an admission`() {
        val queue = NativeMountAdmissionQueue<Pair<String, Boolean>>()
        queue.add("left-composition" to false)
        queue.add("visible-slot" to true)
        queue.add("next-slot" to true)

        assertEquals("visible-slot", queue.takeNext { it.second }?.first)
        assertEquals("next-slot", queue.takeNext { it.second }?.first)
        assertNull(queue.takeNext { it.second })
    }

    @Test
    fun `scheduler failure can drain all pending mounts`() {
        val queue = NativeMountAdmissionQueue<String>()
        queue.add("first")
        queue.add("second")

        assertEquals(listOf("first", "second"), queue.drain())
        assertNull(queue.takeNext { true })
    }
}
