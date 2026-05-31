package ad.simula.ad.sdk.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Minimal native HTTP layer built on [HttpURLConnection].
 *
 * Replaces the OkHttp dependency: zero third-party libraries, a tight 10s
 * connect/read timeout, and fail-fast offline behavior (mirrors the Swift SDK's
 * URLSession config with `waitsForConnectivity = false`). All calls run on
 * [Dispatchers.IO].
 */
internal object SimulaHttp {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    data class Response(val code: Int, val body: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    /**
     * Perform an HTTP request and read the response body as a UTF-8 string.
     *
     * Does not throw on non-2xx — inspect [Response.code]/[Response.isSuccessful].
     * Throws only on connectivity failures (e.g. [java.net.UnknownHostException],
     * [java.net.SocketTimeoutException]) so callers fail fast when offline.
     */
    suspend fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Response = withContext(Dispatchers.IO) {
        val conn = open(url, method, headers)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = decode(conn, stream).bufferedReader(Charsets.UTF_8).use { it.readText() }
            Response(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Download raw bytes via GET (used by the image pipeline). Throws [IOException]
     * on a non-2xx response so the caller can treat it as a decode failure.
     */
    suspend fun requestBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = open(url, "GET", emptyMap())
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            decode(conn, conn.inputStream).use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, method: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            // Advertise gzip explicitly. Setting the header ourselves disables
            // HttpURLConnection's transparent decompression, so we gunzip in decode().
            setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun decode(conn: HttpURLConnection, stream: InputStream): InputStream =
        if (conn.contentEncoding?.equals("gzip", ignoreCase = true) == true) GZIPInputStream(stream) else stream
}
