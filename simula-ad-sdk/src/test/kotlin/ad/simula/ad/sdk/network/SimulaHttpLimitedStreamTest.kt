package ad.simula.ad.sdk.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Verifies the response-body cap that stops an unbounded HTTP read from OOM-crashing the host
 * ([SimulaHttp.LimitedInputStream]) — the headline fix from the 6/18 Android audit. The cap counts
 * post-decode bytes (what `readBytes()` buffers into memory), so a small gzip-bombed body is caught too.
 */
class SimulaHttpLimitedStreamTest {

    private fun readCapped(size: Int, max: Long): ByteArray =
        SimulaHttp.LimitedInputStream(ByteArrayInputStream(ByteArray(size) { it.toByte() }), max).readBytes()

    @Test
    fun `reads a body under the cap unchanged`() {
        val data = ByteArray(100) { it.toByte() }
        val out = SimulaHttp.LimitedInputStream(ByteArrayInputStream(data), max = 1_000).readBytes()
        assertArrayEquals(data, out)
    }

    @Test
    fun `reads a body exactly at the cap`() {
        assertEquals(1_000, readCapped(size = 1_000, max = 1_000).size)
    }

    @Test(expected = IOException::class)
    fun `throws once the body exceeds the cap`() {
        readCapped(size = 2_000, max = 1_000)
    }

    @Test(expected = IOException::class)
    fun `throws on a single-byte read past the cap`() {
        val stream = SimulaHttp.LimitedInputStream(ByteArrayInputStream(ByteArray(10)), max = 4)
        repeat(10) { stream.read() } // the 5th byte exceeds the 4-byte cap
    }
}
