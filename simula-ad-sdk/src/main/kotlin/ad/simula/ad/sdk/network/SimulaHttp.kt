package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException

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
        // Telemetry batches flow through here too; they pass false so a network event isn't
        // recorded for the very request that delivers telemetry (infinite-loop guard).
        instrument: Boolean = true,
    ): Response = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val reqBytes = body?.toByteArray(Charsets.UTF_8)
        try {
            val conn = open(url, method, headers)
            if (reqBytes != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(reqBytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            // Read the full body (counting bytes for telemetry) then decode; closing via use
            // returns the connection to the keep-alive pool. We deliberately do NOT call
            // disconnect() — that closes the socket and forces a fresh TLS handshake on the
            // next same-host request.
            val raw = decode(conn, stream).use { it.readBytes() }
            val text = String(raw, Charsets.UTF_8)
            if (instrument) {
                Telemetry.recordNetwork(
                    path = pathOf(url),
                    method = method,
                    statusCode = code,
                    durationMs = elapsedMs(started),
                    requestBytes = (reqBytes?.size ?: 0).toLong(),
                    responseBytes = raw.size.toLong(),
                    failureClass = httpFailureClass(code),
                )
            }
            Response(code, text)
        } catch (e: Exception) {
            if (instrument) {
                Telemetry.recordNetwork(
                    path = pathOf(url),
                    method = method,
                    statusCode = null,
                    durationMs = elapsedMs(started),
                    requestBytes = (reqBytes?.size ?: 0).toLong(),
                    responseBytes = 0L,
                    failureClass = failureClassOf(e),
                )
            }
            throw e
        }
    }

    /**
     * Download raw bytes via GET (used by the image pipeline). Throws [IOException]
     * on a non-2xx response so the caller can treat it as a decode failure.
     */
    suspend fun requestBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            val conn = open(url, "GET", emptyMap())
            val code = conn.responseCode
            if (code !in 200..299) {
                // Drain + close the error body so the connection can be reused.
                conn.errorStream?.use { it.readBytes() }
                Telemetry.recordNetwork(hostOf(url), "GET", code, elapsedMs(started), 0L, 0L, httpFailureClass(code))
                throw HttpStatusException(code, url)
            }
            val bytes = decode(conn, conn.inputStream).use { it.readBytes() }
            Telemetry.recordNetwork(hostOf(url), "GET", code, elapsedMs(started), 0L, bytes.size.toLong(), null)
            bytes
        } catch (e: Exception) {
            // The non-2xx branch above already recorded its HTTP event; only record genuine
            // connectivity failures here so a single request yields a single network event.
            if (e !is HttpStatusException) {
                Telemetry.recordNetwork(hostOf(url), "GET", null, elapsedMs(started), 0L, 0L, failureClassOf(e))
            }
            throw e
        }
    }

    /** Non-2xx response from [requestBytes]; an [IOException] so existing callers treat it as a fetch failure. */
    private class HttpStatusException(statusCode: Int, url: String) : IOException("HTTP $statusCode for $url")

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    /** Request path only (no scheme/host/query) so telemetry carries no PII-bearing query params. */
    private fun pathOf(url: String): String =
        try { URI(url).path?.takeIf { it.isNotEmpty() } ?: url } catch (_: Exception) { url }

    /** Host of a CDN/asset URL — avoids the high-cardinality per-asset path. */
    private fun hostOf(url: String): String =
        try { URI(url).host ?: "cdn" } catch (_: Exception) { "cdn" }

    private fun httpFailureClass(code: Int): String? = if (code in 200..399) null else "http_$code"

    private fun failureClassOf(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "timeout"
        is UnknownHostException -> "dns"
        is SSLException -> "tls"
        is IOException -> "connection"
        else -> "unknown"
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
            // Custom UA on every native request (PRD). Set before caller headers so a
            // caller could still override it; null (pre-init) leaves the platform default.
            SimulaUserAgent.value?.let { setRequestProperty("User-Agent", it) }
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun decode(conn: HttpURLConnection, stream: InputStream): InputStream =
        if (conn.contentEncoding?.equals("gzip", ignoreCase = true) == true) GZIPInputStream(stream) else stream
}
