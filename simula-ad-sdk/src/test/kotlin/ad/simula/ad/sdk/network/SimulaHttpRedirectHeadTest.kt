package ad.simula.ad.sdk.network

import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulaHttpRedirectHeadTest {

    @Test
    fun `redirect HEAD uses isolated public headers`() {
        val connection = FakeHttpURLConnection()

        SimulaHttp.configureRedirectHeadConnection(connection, 1_500, "WebView UA")

        assertEquals("HEAD", connection.requestMethod)
        assertEquals(1_500, connection.connectTimeout)
        assertEquals(1_500, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals("*/*", connection.getRequestProperty("Accept"))
        assertEquals("WebView UA", connection.getRequestProperty("User-Agent"))
        assertNull(connection.getRequestProperty("Authorization"))
        assertNull(connection.getRequestProperty("Cookie"))
        assertNull(connection.getRequestProperty("X-Device-Id"))
        assertNull(connection.getRequestProperty("X-Connection-Type"))
    }

    @Test
    fun `cancellation disconnects a blocking redirect connection`() = runBlocking {
        val connection = BlockingHttpURLConnection()
        val request = launch(Dispatchers.Default) {
            SimulaHttp.requestRedirectHead(
                url = "https://tracker.example/click",
                timeoutMs = 10_000L,
                userAgent = null,
                openConnection = { connection },
            )
        }
        assertTrue(connection.entered.await(2L, TimeUnit.SECONDS))

        request.cancel()
        withTimeout(2_000L) { request.cancelAndJoin() }

        assertTrue(connection.disconnected.await(2L, TimeUnit.SECONDS))
    }

    private class FakeHttpURLConnection : HttpURLConnection(URL("https://tracker.example/click")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
    }

    private class BlockingHttpURLConnection : HttpURLConnection(URL("https://tracker.example/click")) {
        val entered = CountDownLatch(1)
        val disconnected = CountDownLatch(1)

        override fun connect() {
            entered.countDown()
            if (!disconnected.await(2L, TimeUnit.SECONDS)) throw IOException("disconnect was not called")
            throw IOException("cancelled")
        }

        override fun disconnect() {
            disconnected.countDown()
        }

        override fun usingProxy(): Boolean = false
    }
}
