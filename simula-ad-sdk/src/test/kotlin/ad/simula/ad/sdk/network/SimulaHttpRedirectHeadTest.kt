package ad.simula.ad.sdk.network

import java.net.HttpURLConnection
import java.net.CookieManager
import java.net.InetAddress
import java.net.URL
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
                validateTarget = { _, _ -> },
            )
        }
        assertTrue(connection.entered.await(2L, TimeUnit.SECONDS))

        request.cancel()
        withTimeout(2_000L) { request.cancelAndJoin() }

        assertTrue(connection.disconnected.await(2L, TimeUnit.SECONDS))
    }

    @Test
    fun `redirect probes reject a process global cookie handler`() {
        val failure = runCatching {
            SimulaHttp.validateRedirectCookieIsolation(CookieManager())
        }.exceptionOrNull()

        assertTrue(failure is SimulaHttp.RedirectCookieIsolationException)
    }

    @Test
    fun `redirect probes admit only public resolved targets`() {
        SimulaHttp.validatePublicRedirectTarget("https://tracker.example/click", Long.MAX_VALUE) { _, _ ->
            arrayOf(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("2606:4700:4700::1111"),
            )
        }

        listOf(
            "127.0.0.1",
            "10.0.0.1",
            "169.254.169.254",
            "100.64.0.1",
            "fc00::1",
            "100::1",
            "64:ff9b:1::1",
            "64:ff9b::a00:1",
            "2001:2::1",
            "2001:db8::1",
            "3fff::1",
        ).forEach { address ->
            val failure = runCatching {
                SimulaHttp.validatePublicRedirectTarget("https://tracker.example/click", Long.MAX_VALUE) { _, _ ->
                    arrayOf(InetAddress.getByName(address))
                }
            }.exceptionOrNull()
            assertTrue(address, failure is SimulaHttp.RedirectTargetRejectedException)
        }

        listOf(
            "https://localhost/click",
            "https://sub.localhost/click",
            "https://user:secret@tracker.example/click",
            "https://tracker.example:0/click",
            "https://tracker.example:65536/click",
            "https://tracker.example:/click",
        ).forEach { target ->
            val failure = runCatching {
                SimulaHttp.validatePublicRedirectTarget(target, Long.MAX_VALUE) { _, _ ->
                    arrayOf(InetAddress.getByName("8.8.8.8"))
                }
            }.exceptionOrNull()
            assertTrue(target, failure is SimulaHttp.RedirectTargetRejectedException)
        }

        val mixedDnsFailure = runCatching {
            SimulaHttp.validatePublicRedirectTarget("https://tracker.example/click", Long.MAX_VALUE) { _, _ ->
                arrayOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("192.168.1.1"))
            }
        }.exceptionOrNull()
        assertTrue(mixedDnsFailure is SimulaHttp.RedirectTargetRejectedException)
    }

    @Test
    fun `blocked DNS returns at its absolute deadline`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resolver = BoundedDeadlineHostResolver(
            lookup = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                arrayOf(InetAddress.getByName("8.8.8.8"))
            },
        )
        val started = System.nanoTime()

        val failure = runCatching {
            resolver.resolve("blocked.example", started + TimeUnit.MILLISECONDS.toNanos(50))
        }.exceptionOrNull()

        release.countDown()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertTrue(failure is java.net.SocketTimeoutException)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000L)
    }

    @Test
    fun `resolver overload fails closed without adding workers`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lookups = AtomicInteger()
        val overloads = AtomicInteger()
        val resolver = BoundedDeadlineHostResolver(
            lookup = {
                lookups.incrementAndGet()
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                arrayOf(InetAddress.getByName("8.8.8.8"))
            },
            maxWorkers = 1,
            queueCapacity = 1,
            recordOverload = overloads::incrementAndGet,
        )
        val callers = Executors.newFixedThreadPool(2)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val pending = mutableListOf(callers.submit { runCatching { resolver.resolve("busy.example", deadline) } })
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        pending += callers.submit { runCatching { resolver.resolve("queued.example", deadline) } }
        val queueWaitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (resolver.queuedTaskCount() == 0 && System.nanoTime() < queueWaitDeadline) Thread.yield()
        assertEquals(1, resolver.queuedTaskCount())

        val failure = runCatching { resolver.resolve("overloaded.example", deadline) }.exceptionOrNull()

        release.countDown()
        pending.forEach { it.get(2, TimeUnit.SECONDS) }
        callers.shutdownNow()
        assertTrue(failure is RedirectResolverOverloadedException)
        assertEquals(2, lookups.get())
        assertEquals(1, overloads.get())
    }

    @Test
    fun `cancelled queued DNS work is purged and capacity recovers`() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queuedLookups = AtomicInteger()
        val resolver = BoundedDeadlineHostResolver(
            lookup = { host ->
                when (host) {
                    "blocked.example" -> {
                        blocked.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                    "queued.example" -> queuedLookups.incrementAndGet()
                }
                arrayOf(InetAddress.getByName("8.8.8.8"))
            },
            maxWorkers = 1,
            queueCapacity = 1,
        )
        val caller = Executors.newSingleThreadExecutor()
        val first = caller.submit {
            resolver.resolve("blocked.example", System.nanoTime() + TimeUnit.SECONDS.toNanos(5))
        }
        assertTrue(blocked.await(2, TimeUnit.SECONDS))

        val queuedFailure = runCatching {
            resolver.resolve("queued.example", System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50))
        }.exceptionOrNull()

        assertTrue(queuedFailure is java.net.SocketTimeoutException)
        assertEquals(0, resolver.queuedTaskCount())
        assertEquals(0, queuedLookups.get())
        release.countDown()
        first.get(2, TimeUnit.SECONDS)
        assertEquals(
            "8.8.8.8",
            resolver.resolve("recovered.example", System.nanoTime() + TimeUnit.SECONDS.toNanos(2)).single().hostAddress,
        )
        caller.shutdownNow()
    }

    @Test
    fun `blocked disconnect cannot block a subsequent abort`() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondDisconnected = CountDownLatch(1)
        val aborter = BoundedConnectionAborter(maxWorkers = 2, queueCapacity = 2)
        aborter.abort(DisconnectConnection {
            firstEntered.countDown()
            releaseFirst.await(5, TimeUnit.SECONDS)
        })
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

        aborter.abort(DisconnectConnection { secondDisconnected.countDown() })

        assertTrue(secondDisconnected.await(1, TimeUnit.SECONDS))
        releaseFirst.countDown()
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

    private class DisconnectConnection(
        private val onDisconnect: () -> Unit,
    ) : HttpURLConnection(URL("https://tracker.example/click")) {
        override fun connect() = Unit
        override fun disconnect() = onDisconnect()
        override fun usingProxy(): Boolean = false
    }
}
