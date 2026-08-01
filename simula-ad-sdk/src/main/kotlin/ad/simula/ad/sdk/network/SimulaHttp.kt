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

    // Response-body caps so a misconfigured/hostile backend or CDN (or a gzip bomb) can't OOM the
    // host: readBytes() would otherwise buffer the entire response into a single array. Caps are
    // generous vs real payloads (JSON is KB-sized; creatives are small) but well below crash range.
    private const val MAX_JSON_BYTES = 10L * 1024 * 1024 // 10 MB
    private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024 // 64 MB

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
            // Cap the DECODED (post-gunzip) stream so a huge or gzip-bombed body throws instead of
            // exhausting the heap. The IOException is classified + rethrown by the catch below.
            val raw = LimitedInputStream(decode(conn, stream), MAX_JSON_BYTES).use { it.readBytes() }
            val text = String(raw, Charsets.UTF_8)
            if (instrument) {
                recordFirstPartyNetwork(
                    url, method, code, elapsedMs(started), (reqBytes?.size ?: 0).toLong(),
                    raw.size.toLong(), httpFailureClass(code),
                )
            }
            Response(code, text)
        } catch (e: Exception) {
            if (instrument) {
                recordFirstPartyNetwork(
                    url, method, null, elapsedMs(started), (reqBytes?.size ?: 0).toLong(),
                    0L, failureClassOf(e),
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
                Telemetry.recordNetwork(assetTelemetryLabel(url), "GET", code, elapsedMs(started), 0L, 0L, httpFailureClass(code))
                throw HttpStatusException(code, url)
            }
            val bytes = LimitedInputStream(decode(conn, conn.inputStream), MAX_IMAGE_BYTES).use { it.readBytes() }
            Telemetry.recordNetwork(assetTelemetryLabel(url), "GET", code, elapsedMs(started), 0L, bytes.size.toLong(), null)
            bytes
        } catch (e: Exception) {
            // The non-2xx branch above already recorded its HTTP event; only record genuine
            // connectivity failures here so a single request yields a single network event.
            if (e !is HttpStatusException) {
                Telemetry.recordNetwork(assetTelemetryLabel(url), "GET", null, elapsedMs(started), 0L, 0L, failureClassOf(e))
            }
            throw e
        }
    }

    /** Non-2xx response from [requestBytes]; an [IOException] so existing callers treat it as a fetch failure. */
    private class HttpStatusException(statusCode: Int, url: String) : IOException("HTTP $statusCode for $url")

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    private fun recordFirstPartyNetwork(
        url: String,
        method: String,
        statusCode: Int?,
        durationMs: Long,
        requestBytes: Long,
        responseBytes: Long,
        failureClass: String?,
    ) {
        val route = normalizeFirstPartyRoute(url) ?: return
        Telemetry.recordNetwork(route, method, statusCode, durationMs, requestBytes, responseBytes, failureClass)
    }

    /**
     * Contract-v3 first-party route normalization. Only registered static routes and the approved
     * dynamic templates are emitted. Telemetry delivery and PPID updates are omitted; every other
     * path becomes a fixed `/unknown`, so malformed/new routes can never leak identifiers.
     */
    internal fun normalizeFirstPartyRoute(url: String): String? {
        val path = runCatching { URI(url).path }
            .getOrNull()
            ?.takeIf { it.startsWith('/') }
            ?: return "/unknown"
        val segments = path.split('/').filter { it.isNotEmpty() }

        if (segments.firstOrNull() == "telemetry" ||
            (segments.size >= 2 && segments[0] == "v1" && segments[1] == "telemetry")
        ) return null
        if ("ppid" in segments) return null

        if (path in REGISTERED_STATIC_ROUTES) return path
        if (segments.size == 3 && segments[0] == "impressions" &&
            segments[1].isNotBlank() && segments[2] in REGISTERED_IMPRESSION_ACTIONS
        ) return "/impressions/:id/${segments[2]}"
        if (segments.size == 3 && segments[0] == "load" && segments[1] == "fallbacks" &&
            segments[2].isNotBlank()
        ) return "/load/fallbacks/:id"
        return "/unknown"
    }

    /** Asset requests retain one approved low-cardinality CDN label, never a path or host. */
    internal fun assetTelemetryLabel(@Suppress("UNUSED_PARAMETER") url: String): String = "cdn"

    private fun httpFailureClass(code: Int): String? = if (code in 200..399) null else "http_$code"

    private fun failureClassOf(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "timeout"
        is UnknownHostException -> "dns"
        is SSLException -> "tls"
        is IOException -> "connection"
        else -> "unknown"
    }

    private val REGISTERED_STATIC_ROUTES = setOf(
        "/session/create",
        "/frequency-cap/status",
        "/minigames/catalog",
        "/character-selector",
        "/minigames/init",
        "/load/interstitial",
        "/load/native",
        "/load/rewarded",
        "/minigames/verify-reward",
        "/minigames/menu/track/click",
    )

    private val REGISTERED_IMPRESSION_ACTIONS = setOf("shown", "seen", "click", "interest", "report")

    private fun open(url: String, method: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as? HttpURLConnection
            ?: throw IOException("Expected an HttpURLConnection for $url")).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            // Advertise gzip explicitly. Setting the header ourselves disables
            // HttpURLConnection's transparent decompression, so we gunzip in decode().
            setRequestProperty("Accept-Encoding", "gzip")
            // Custom UA + device id on every native request. Set before caller headers so a
            // caller could still override them; null (pre-init / unavailable) is simply omitted.
            SimulaUserAgent.value?.let { setRequestProperty("User-Agent", it) }
            SimulaDeviceId.retryIfNeeded()
            SimulaDeviceId.value?.let { setRequestProperty("X-Device-Id", it) }
            // Read live on every call (never cached at init) — a session begun on Wi-Fi can hand
            // off to cellular mid-flight, and SimulaConnectionType's cached value updates on that
            // transition, so the very next request carries it. OpenRTB `device.connectiontype`.
            setRequestProperty("X-Connection-Type", SimulaConnectionType.value.toString())
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun decode(conn: HttpURLConnection, stream: InputStream): InputStream =
        if (conn.contentEncoding?.equals("gzip", ignoreCase = true) == true) GZIPInputStream(stream) else stream

    /**
     * Wraps a stream and throws [IOException] once more than [max] bytes have been read, so an
     * unbounded or maliciously large response can't be buffered whole into the heap (the prior
     * `readBytes()` had no ceiling). Counts post-decode bytes — the size that actually lands in
     * memory — so a small gzip-bombed body is also caught.
     */
    internal class LimitedInputStream(
        private val delegate: InputStream,
        private val max: Long,
    ) : InputStream() {
        private var count = 0L

        private fun tally(read: Int): Int {
            if (read > 0) {
                count += read
                if (count > max) throw IOException("Response body exceeds the $max-byte limit")
            }
            return read
        }

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) tally(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = tally(delegate.read(b, off, len))

        override fun available(): Int = delegate.available()

        override fun close() = delegate.close()
    }
}
