package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

internal fun interface DeadlineHostResolver {
    fun resolve(host: String, deadlineNanos: Long): Array<InetAddress>
}

/** Bounds both OS resolver concurrency and callers' wait, even when libc DNS ignores interruption. */
internal class BoundedDeadlineHostResolver(
    private val lookup: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    private val clockNanos: () -> Long = System::nanoTime,
    maxWorkers: Int = 2,
    queueCapacity: Int = 8,
    private val recordOverload: () -> Unit = {
        Telemetry.recordError(signature = "dns:resolver_overloaded")
    },
) : DeadlineHostResolver {
    private val workerCount = maxWorkers.coerceIn(1, 2)
    private val executor = ThreadPoolExecutor(
        workerCount,
        workerCount,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(queueCapacity.coerceAtLeast(1)),
        { runnable -> Thread(runnable, "simula-dns-resolver").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    override fun resolve(host: String, deadlineNanos: Long): Array<InetAddress> {
        val remaining = deadlineNanos - clockNanos()
        if (remaining <= 0L) throw SocketTimeoutException("DNS deadline exceeded")
        val future: Future<Array<InetAddress>> = try {
            executor.submit<Array<InetAddress>> { lookup(host) }
        } catch (_: RejectedExecutionException) {
            // libc DNS may ignore interruption after a caller's deadline. Keep the fixed workers
            // bounded and fail closed under saturation; rotating executors would leak blocked threads.
            runCatching(recordOverload)
            throw RedirectResolverOverloadedException()
        }
        return try {
            future.get(remaining, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            (future as? Runnable)?.let(executor::remove)
            executor.purge()
            throw SocketTimeoutException("DNS deadline exceeded")
        } catch (interrupted: InterruptedException) {
            future.cancel(true)
            (future as? Runnable)?.let(executor::remove)
            executor.purge()
            Thread.currentThread().interrupt()
            throw SocketTimeoutException("DNS resolution interrupted")
        } catch (failure: java.util.concurrent.ExecutionException) {
            throw (failure.cause as? Exception ?: UnknownHostException(host))
        }
    }

    internal fun queuedTaskCount(): Int = executor.queue.size
}

internal class RedirectResolverOverloadedException : IOException("DNS resolver overloaded")

/** Keeps potentially blocking platform disconnect calls off cancellation and deadline threads. */
internal class BoundedConnectionAborter(
    maxWorkers: Int = 2,
    queueCapacity: Int = 16,
) {
    private val threadId = AtomicInteger()
    private val workerCount = maxWorkers.coerceIn(1, 2)
    private val executor = ThreadPoolExecutor(
        workerCount,
        workerCount,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(queueCapacity.coerceAtLeast(1)),
        { runnable ->
            Thread(runnable, "simula-http-abort-${threadId.incrementAndGet()}").apply { isDaemon = true }
        },
        ThreadPoolExecutor.DiscardPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    fun abort(connection: HttpURLConnection?) {
        connection ?: return
        runCatching { executor.execute { runCatching { connection.disconnect() } } }
    }
}

private val SharedDeadlineHostResolver = BoundedDeadlineHostResolver()
private val SharedConnectionAborter = BoundedConnectionAborter()

internal fun abortConnectionAsync(connection: HttpURLConnection?) {
    SharedConnectionAborter.abort(connection)
}

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
    private const val PLAIN_GET_TIMEOUT_MS = 5_000
    private const val PLAIN_GET_MAX_REDIRECTS = 5
    private const val NANOS_PER_MILLISECOND = 1_000_000L
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

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

    /** One unauthenticated third-party impression GET. No SDK, privacy, device, or telemetry headers. */
    suspend fun requestPlainGet(
        url: String,
        resolver: DeadlineHostResolver = SharedDeadlineHostResolver,
        clockNanos: () -> Long = System::nanoTime,
        openConnection: (String) -> HttpURLConnection = { target ->
            URL(target).openConnection() as? HttpURLConnection
                ?: throw IOException("Expected an HttpURLConnection")
        },
    ): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val deadlineNanos = saturatingDeadlineNanos(clockNanos(), PLAIN_GET_TIMEOUT_MS.toLong())
            var current = url
            var redirects = 0
            var result: Boolean? = null
            while (result == null) {
                validateRedirectCookieIsolation()
                validatePublicRedirectTarget(current, deadlineNanos, resolver)
                val connectTimeoutMs = remainingPlainGetTimeoutMs(deadlineNanos, clockNanos)
                    ?: return@withContext false
                conn = openConnection(current)
                configurePlainGetConnection(conn, connectTimeoutMs)
                validateRedirectCookieIsolation()
                conn.connect()
                val readTimeoutMs = remainingPlainGetTimeoutMs(deadlineNanos, clockNanos)
                if (readTimeoutMs == null) {
                    abortConnectionAsync(conn)
                    return@withContext false
                }
                conn.readTimeout = readTimeoutMs
                val code = conn.responseCode
                runCatching { (conn.errorStream ?: conn.inputStream)?.close() }

                if (code in REDIRECT_CODES) {
                    if (redirects >= PLAIN_GET_MAX_REDIRECTS) return@withContext false
                    val location = conn.getHeaderField("Location")?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return@withContext false
                    current = resolvePublicRedirect(current, location)
                        ?: return@withContext false
                    redirects++
                    conn = null
                    continue
                }
                result = code in 200..299
            }
            result
        } catch (cancelled: CancellationException) {
            abortConnectionAsync(conn)
            throw cancelled
        } catch (_: RedirectCookieIsolationException) {
            abortConnectionAsync(conn)
            Telemetry.recordError(signature = "impression:cookie_isolation_unavailable")
            false
        } catch (_: Exception) {
            abortConnectionAsync(conn)
            false
        }
    }

    internal fun configurePlainGetConnection(
        conn: HttpURLConnection,
        timeoutMs: Int = PLAIN_GET_TIMEOUT_MS,
    ) {
        conn.requestMethod = "GET"
        conn.connectTimeout = timeoutMs.coerceAtLeast(1)
        conn.readTimeout = timeoutMs.coerceAtLeast(1)
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.defaultUseCaches = false
    }

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
        resolver: DeadlineHostResolver = SharedDeadlineHostResolver,
        validateTarget: (String, Long) -> Unit = { target, deadline ->
            validatePublicRedirectTarget(target, deadline, resolver)
        },
        validateCookieIsolation: () -> Unit = ::validateRedirectCookieIsolation,
    ): RedirectHeadResponse = suspendCancellableCoroutine { continuation ->
        val activeConnection = AtomicReference<HttpURLConnection?>(null)
        continuation.invokeOnCancellation {
            abortConnectionAsync(activeConnection.getAndSet(null))
        }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            if (!continuation.isActive) return@Runnable
            var conn: HttpURLConnection? = null
            try {
                val started = System.nanoTime()
                val boundedTimeoutMs = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong())
                val deadlineNanos = saturatingDeadlineNanos(started, boundedTimeoutMs)
                validateCookieIsolation()
                validateTarget(url, deadlineNanos)
                conn = openConnection(url)
                activeConnection.set(conn)
                if (!continuation.isActive) {
                    abortConnectionAsync(activeConnection.getAndSet(null))
                    return@Runnable
                }
                configureRedirectHeadConnection(
                    conn,
                    remainingTimeoutMs(started, boundedTimeoutMs),
                    userAgent,
                )
                validateRedirectCookieIsolation()
                if (!continuation.isActive) {
                    abortConnectionAsync(activeConnection.getAndSet(null))
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
                abortConnectionAsync(conn)
                continuation.resumeWith(Result.failure(e))
            }
        })
    }

    /** Non-2xx response from [requestBytes]; an [IOException] so existing callers treat it as a fetch failure. */
    private class HttpStatusException(statusCode: Int, url: String) : IOException("HTTP $statusCode for $url")

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    private fun remainingTimeoutMs(startNanos: Long, timeoutMs: Long): Int {
        val elapsedMs = ((System.nanoTime() - startNanos).coerceAtLeast(0L) / 1_000_000L)
        val remaining = timeoutMs - elapsedMs
        if (remaining <= 0L) throw SocketTimeoutException("Request deadline exceeded")
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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

    internal fun validateRedirectCookieIsolation(cookieHandler: CookieHandler? = CookieHandler.getDefault()) {
        if (cookieHandler != null) throw RedirectCookieIsolationException()
    }

    internal fun validatePublicRedirectTarget(
        value: String,
        deadlineNanos: Long,
        resolver: DeadlineHostResolver = SharedDeadlineHostResolver,
    ) {
        val uri = runCatching { URI(value) }.getOrNull() ?: throw RedirectTargetRejectedException()
        if (!uri.isAbsolute || uri.scheme?.lowercase() !in setOf("http", "https") || uri.rawUserInfo != null) {
            throw RedirectTargetRejectedException()
        }
        val authority = uri.rawAuthority ?: throw RedirectTargetRejectedException()
        if (!hasValidNetworkAuthority(authority)) throw RedirectTargetRejectedException()
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.')?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: throw RedirectTargetRejectedException()
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal") || host.endsWith(".home.arpa")
        ) throw RedirectTargetRejectedException()
        val addresses = runCatching { resolver.resolve(host, deadlineNanos) }
            .getOrElse { throw RedirectTargetRejectedException() }
        if (addresses.isEmpty() || addresses.any { !it.isPublicRedirectAddress() }) {
            throw RedirectTargetRejectedException()
        }
    }

    private fun hasValidNetworkAuthority(authority: String): Boolean {
        if (authority.isBlank() || authority.contains('@')) return false
        val portText = when {
            authority.startsWith('[') -> {
                val closing = authority.indexOf(']')
                if (closing <= 1) return false
                val suffix = authority.substring(closing + 1)
                when {
                    suffix.isEmpty() -> null
                    suffix.startsWith(':') -> suffix.drop(1)
                    else -> return false
                }
            }
            authority.count { it == ':' } > 1 -> return false
            ':' in authority -> authority.substringAfterLast(':')
            else -> null
        }
        if (portText == null) return true
        val port = portText.toIntOrNull() ?: return false
        return port in 1..65535
    }

    internal fun resolvePublicRedirect(current: String, location: String): String? = runCatching {
        URI(current).resolve(URI(location)).toASCIIString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun remainingPlainGetTimeoutMs(
        deadlineNanos: Long,
        clockNanos: () -> Long,
    ): Int? {
        val remainingNanos = deadlineNanos - clockNanos()
        if (remainingNanos <= 0L) return null
        return ((remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND)
            .coerceIn(1L, PLAIN_GET_TIMEOUT_MS.toLong())
            .toInt()
    }

    private fun saturatingDeadlineNanos(startNanos: Long, timeoutMs: Long): Long {
        val timeoutNanos = timeoutMs.coerceAtMost(Long.MAX_VALUE / NANOS_PER_MILLISECOND) *
            NANOS_PER_MILLISECOND
        return if (startNanos > Long.MAX_VALUE - timeoutNanos) Long.MAX_VALUE else startNanos + timeoutNanos
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
                    third <= 0x01 -> true
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
            first == 192 && second == 88 && third == 99 -> false
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
