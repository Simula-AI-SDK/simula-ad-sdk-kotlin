package ad.simula.ad.sdk.network

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class SimulaHttpPlainGetTest {
    @Test
    fun `plain impression GET is isolated bounded and header free`() = runTest {
        val connection = FakeConnection()

        assertTrue(
            SimulaHttp.requestPlainGet(
                url = "https://tracker.example/impression",
                resolver = resolver { arrayOf(InetAddress.getByName("8.8.8.8")) },
                openConnection = { connection },
            ),
        )

        assertEquals("GET", connection.requestMethod)
        assertTrue(connection.connectTimeout in 1..5_000)
        assertTrue(connection.readTimeout in 1..5_000)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertFalse(connection.defaultUseCaches)
        assertEquals(emptySet<String>(), connection.requestProperties.keys)
        assertFalse(connection.disconnected)
    }

    @Test
    fun `plain impression GET rejects local private and mixed DNS targets before connecting`() = runTest {
        val opened = mutableListOf<String>()
        val resolver: (String) -> Array<InetAddress> = { host ->
            when (host) {
                "public.example" -> arrayOf(
                    InetAddress.getByName("8.8.8.8"),
                    InetAddress.getByName("10.0.0.1"),
                )
                else -> arrayOf(InetAddress.getByName(host))
            }
        }

        listOf(
            "https://localhost/pixel",
            "https://127.0.0.1/pixel",
            "https://10.0.0.1/pixel",
            "https://169.254.169.254/pixel",
            "https://224.0.0.1/pixel",
            "https://240.0.0.1/pixel",
            "https://0.0.0.0/pixel",
            "https://[::1]/pixel",
            "https://[fc00::1]/pixel",
            "https://[fe80::1]/pixel",
            "https://[ff02::1]/pixel",
            "https://[::]/pixel",
            "https://public.example/pixel",
        ).forEach { target ->
            assertFalse(
                target,
                SimulaHttp.requestPlainGet(
                    url = target,
                    resolver = resolver(resolver),
                    openConnection = { opened += it; FakeConnection(it) },
                ),
            )
        }

        assertTrue(opened.isEmpty())

        assertFalse(
            SimulaHttp.requestPlainGet(
                url = "https://missing.example/pixel",
                resolver = resolver { throw java.net.UnknownHostException("unavailable") },
                openConnection = { opened += it; FakeConnection(it) },
            ),
        )
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `plain impression GET admits public DNS and rejects redirect to private before second GET`() = runTest {
        val opened = mutableListOf<String>()
        val initial = FakeConnection(
            target = "https://tracker.example/pixel",
            code = 302,
            location = "https://internal.example/pixel",
        )

        assertFalse(
            SimulaHttp.requestPlainGet(
                url = initial.url.toString(),
                resolver = resolver { host ->
                    arrayOf(
                        InetAddress.getByName(if (host == "tracker.example") "8.8.8.8" else "192.168.1.1"),
                    )
                },
                openConnection = { target ->
                    opened += target
                    initial
                },
            ),
        )

        assertEquals(listOf("https://tracker.example/pixel"), opened)
    }

    @Test
    fun `plain impression GET follows at most five validated public redirects`() = runTest {
        val opened = mutableListOf<String>()

        assertFalse(
            SimulaHttp.requestPlainGet(
                url = "https://tracker.example/0",
                resolver = resolver { arrayOf(InetAddress.getByName("8.8.8.8")) },
                openConnection = { target ->
                    opened += target
                    val hop = target.substringAfterLast('/').toInt()
                    FakeConnection(target, 302, "https://tracker.example/${hop + 1}")
                },
            ),
        )

        assertEquals(6, opened.size)
    }

    @Test
    fun `plain impression passes its absolute five second deadline to DNS`() = runTest {
        var observedDeadline = 0L

        assertFalse(
            SimulaHttp.requestPlainGet(
                url = "https://tracker.example/impression",
                clockNanos = { 100L },
                resolver = DeadlineHostResolver { _, deadline ->
                    observedDeadline = deadline
                    throw SocketTimeoutException("blocked")
                },
                openConnection = { error("DNS timeout must fail before connect") },
            ),
        )

        assertEquals(5_000_000_100L, observedDeadline)
    }

    private class FakeConnection(
        target: String = "https://tracker.example/impression",
        private val code: Int = 204,
        private val location: String? = null,
    ) : HttpURLConnection(URL(target)) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = code
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getHeaderField(name: String?): String? =
            location.takeIf { name.equals("Location", ignoreCase = true) }
    }

    private fun resolver(resolve: (String) -> Array<InetAddress>): DeadlineHostResolver =
        DeadlineHostResolver { host, _ -> resolve(host) }
}
