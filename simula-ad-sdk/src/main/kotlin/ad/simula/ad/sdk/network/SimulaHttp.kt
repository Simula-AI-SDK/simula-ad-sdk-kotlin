package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream
import java.util.concurrent.atomic.AtomicReference
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

    data class RedirectHeadResponse(
        val code: Int,
        val locations: List<String>,
    )

    internal class RedirectTargetRejectedException : IOException("Redirect target is not public")
    internal class RedirectCookieIsolationException : IOException("Redirect cookie isolation unavailable")

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
            val bytes = LimitedInputStream(decode(conn, conn.inputStream), MAX_IMAGE_BYTES).use { it.readBytes() }
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

    /**
     * One public redirect probe. Unlike normal API requests this deliberately sends no Simula
     * device, privacy, authorization, or connection headers to the third-party destination.
     */
    suspend fun requestRedirectHead(
        url: String,
        timeoutMs: Long,
        userAgent: String?,
        openConnection: (String) -> HttpURLConnection = { target ->
            URL(target).openConnection() as? HttpURLConnection
                ?: throw IOException("Expected an HttpURLConnection")
        },
        validateTarget: (String) -> Unit = ::validatePublicRedirectTarget,
        validateCookieIsolation: (String) -> Unit = ::validateRedirectCookieIsolation,
    ): RedirectHeadResponse = suspendCancellableCoroutine { continuation ->
        val activeConnection = AtomicReference<HttpURLConnection?>(null)
        continuation.invokeOnCancellation {
            // HttpURLConnection is blocking; cancellation must abort the socket from the cancelling
            // thread rather than waiting for the IO worker to observe coroutine cancellation.
            runCatching { activeConnection.getAndSet(null)?.disconnect() }
        }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            if (!continuation.isActive) return@Runnable
            var conn: HttpURLConnection? = null
            try {
                val started = System.nanoTime()
                val boundedTimeoutMs = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong())
                validateCookieIsolation(url)
                validateTarget(url)
                conn = openConnection(url)
                activeConnection.set(conn)
                if (!continuation.isActive) {
                    runCatching { activeConnection.getAndSet(null)?.disconnect() }
                    return@Runnable
                }
                configureRedirectHeadConnection(conn, boundedTimeoutMs.toInt(), userAgent)
                validateRedirectCookieIsolation(url)
                if (!continuation.isActive) {
                    runCatching { activeConnection.getAndSet(null)?.disconnect() }
                    return@Runnable
                }
                conn.connect()
                conn.readTimeout = remainingTimeoutMs(started, boundedTimeoutMs)
                val code = conn.responseCode
                val locations = conn.headerFields.orEmpty().entries
                    .filter { (name, _) -> name?.equals("Location", ignoreCase = true) == true }
                    .flatMap { it.value.orEmpty() }
                runCatching { (conn.errorStream ?: conn.inputStream)?.close() }
                activeConnection.compareAndSet(conn, null)
                continuation.resumeWith(Result.success(RedirectHeadResponse(code, locations)))
            } catch (e: Throwable) {
                // Exceptional connections are not reusable; abort them so a timed-out probe cannot
                // continue in the background and race the browser fallback.
                activeConnection.compareAndSet(conn, null)
                runCatching { conn?.disconnect() }
                continuation.resumeWith(Result.failure(e))
            }
        })
    }

    /** Non-2xx response from [requestBytes]; an [IOException] so existing callers treat it as a fetch failure. */
    private class HttpStatusException(statusCode: Int, url: String) : IOException("HTTP $statusCode for $url")

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    private fun remainingTimeoutMs(startNanos: Long, timeoutMs: Long): Int {
        val elapsedMs = ((System.nanoTime() - startNanos).coerceAtLeast(0L) / 1_000_000L)
        return (timeoutMs - elapsedMs).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    internal fun configureRedirectHeadConnection(
        conn: HttpURLConnection,
        timeoutMs: Int,
        userAgent: String?,
    ) {
        conn.requestMethod = "HEAD"
        conn.connectTimeout = timeoutMs.coerceAtLeast(1)
        conn.readTimeout = timeoutMs.coerceAtLeast(1)
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.setRequestProperty("Accept", "*/*")
        userAgent?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("User-Agent", it) }
    }

    internal fun validateRedirectCookieIsolation(
        value: String,
        cookieHandler: CookieHandler? = CookieHandler.getDefault(),
    ) {
        cookieHandler ?: return
        val uri = runCatching { URI(value) }.getOrElse { throw RedirectCookieIsolationException() }
        val headers = runCatching { cookieHandler.get(uri, emptyMap()) }
            .getOrElse { throw RedirectCookieIsolationException() }
        val hasCookies = headers.entries.any { (name, values) ->
            (name.equals("Cookie", ignoreCase = true) || name.equals("Cookie2", ignoreCase = true)) &&
                values.orEmpty().any { it.isNotBlank() }
        }
        if (hasCookies) throw RedirectCookieIsolationException()
    }

    internal fun validatePublicRedirectTarget(
        value: String,
        resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    ) {
        val host = runCatching { URL(value).host.trimEnd('.').lowercase() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: throw RedirectTargetRejectedException()
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal") || host.endsWith(".home.arpa")
        ) throw RedirectTargetRejectedException()
        val addresses = resolve(host)
        if (addresses.isEmpty() || addresses.any { !it.isPublicRedirectAddress() }) {
            throw RedirectTargetRejectedException()
        }
    }

    private fun InetAddress.isPublicRedirectAddress(): Boolean {
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
            return false
        }
        val bytes = address
        return when (this) {
            is Inet4Address -> {
                bytes.isPublicIpv4Address()
            }
            is Inet6Address -> {
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                val third = bytes[2].toInt() and 0xff
                val fourth = bytes[3].toInt() and 0xff
                val globalUnicast = first in 0x20..0x3f
                val wellKnownNat64 = first == 0x00 && second == 0x64 && third == 0xff && fourth == 0x9b &&
                    bytes.copyOfRange(4, 12).all { it.toInt() == 0 } &&
                    bytes.copyOfRange(12, 16).isPublicIpv4Address()
                val special2001 = first == 0x20 && second == 0x01 && when {
                    third == 0x00 && fourth == 0x02 -> true
                    third == 0x00 && fourth in 0x10..0x2f -> true
                    third == 0x0d && fourth == 0xb8 -> true
                    else -> false
                }
                val documentation3fff = first == 0x3f && second == 0xff && (third and 0xf0) == 0
                (globalUnicast || wellKnownNat64) && !special2001 &&
                    !(first == 0x20 && second == 0x02) && !documentation3fff
            }
            else -> false
        }
    }

    private fun ByteArray.isPublicIpv4Address(): Boolean {
        if (size != 4) return false
        val first = this[0].toInt() and 0xff
        val second = this[1].toInt() and 0xff
        val third = this[2].toInt() and 0xff
        return when {
            first == 0 || first == 10 || first == 127 || first >= 224 -> false
            first == 100 && second in 64..127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 168 -> false
            first == 192 && second == 0 && third in setOf(0, 2) -> false
            first == 198 && second in 18..19 -> false
            first == 198 && second == 51 && third == 100 -> false
            first == 203 && second == 0 && third == 113 -> false
            else -> true
        }
    }

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
