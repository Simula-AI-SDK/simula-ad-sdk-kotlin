package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.network.SimulaHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.URL

internal fun interface RedirectHeadClient {
    suspend fun head(url: String, timeoutMs: Long, userAgent: String?): SimulaHttp.RedirectHeadResponse
}

internal enum class RedirectFallbackReason {
    TIMEOUT,
    HOP_LIMIT,
    LOOP,
    TRANSPORT,
    NON_REDIRECT,
    MISSING_LOCATION,
    AMBIGUOUS_LOCATION,
    INVALID_LOCATION,
    DOWNGRADE,
}

internal sealed interface PlayStoreRedirectResolution {
    data class Resolved(val url: String, val redirects: Int) : PlayStoreRedirectResolution
    data class BrowserFallback(
        val originalUrl: String,
        val reason: RedirectFallbackReason,
        val redirects: Int,
    ) : PlayStoreRedirectResolution
}

internal class PlayStoreRedirectResolver(
    private val client: RedirectHeadClient = RedirectHeadClient { url, timeoutMs, userAgent ->
        SimulaHttp.requestRedirectHead(url, timeoutMs, userAgent)
    },
    private val clockNanos: () -> Long = System::nanoTime,
    private val maxRedirects: Int = MAX_REDIRECTS,
    private val totalTimeoutMs: Long = TOTAL_TIMEOUT_MS,
) {
    suspend fun resolve(
        startUrl: String,
        userAgent: String?,
        startedAtNanos: Long = clockNanos(),
    ): PlayStoreRedirectResolution {
        val original = CreativeCtaRouter.admittedHttpUrl(startUrl)
            ?: return PlayStoreRedirectResolution.BrowserFallback(
                startUrl,
                RedirectFallbackReason.INVALID_LOCATION,
                0,
            )
        if (original.length > MAX_URL_CHARS) {
            return PlayStoreRedirectResolution.BrowserFallback(
                original,
                RedirectFallbackReason.INVALID_LOCATION,
                0,
            )
        }
        CreativeCtaRouter.admittedDirectPlayStoreUrl(original)?.let {
            return PlayStoreRedirectResolution.Resolved(it, 0)
        }

        val deadlineNanos = startedAtNanos.saturatingAdd(totalTimeoutMs * NANOS_PER_MILLISECOND)
        val visited = HashSet<String>(maxRedirects + 1)
        var current = original
        var redirects = 0
        visited += loopKey(current)

        while (true) {
            val remainingMs = remainingMs(deadlineNanos)
            if (remainingMs <= 0L) return fallback(original, RedirectFallbackReason.TIMEOUT, redirects)
            val response = try {
                withTimeout(remainingMs) { client.head(current, remainingMs, userAgent) }
            } catch (_: TimeoutCancellationException) {
                return fallback(original, RedirectFallbackReason.TIMEOUT, redirects)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return fallback(original, RedirectFallbackReason.TRANSPORT, redirects)
            }

            if (response.code !in REDIRECT_CODES) {
                return fallback(original, RedirectFallbackReason.NON_REDIRECT, redirects)
            }
            if (response.locations.isEmpty() || response.locations.all { it.isBlank() }) {
                return fallback(original, RedirectFallbackReason.MISSING_LOCATION, redirects)
            }
            if (response.locations.size != 1) {
                return fallback(original, RedirectFallbackReason.AMBIGUOUS_LOCATION, redirects)
            }
            val next = resolveLocation(current, response.locations.single())
                ?: return fallback(original, RedirectFallbackReason.INVALID_LOCATION, redirects)
            if (current.startsWith("https://", ignoreCase = true) && next.startsWith("http://", ignoreCase = true)) {
                return fallback(original, RedirectFallbackReason.DOWNGRADE, redirects)
            }

            redirects++
            CreativeCtaRouter.admittedDirectPlayStoreUrl(next)?.let {
                return PlayStoreRedirectResolution.Resolved(it, redirects)
            }
            if (redirects >= maxRedirects) {
                return fallback(original, RedirectFallbackReason.HOP_LIMIT, redirects)
            }
            if (!visited.add(loopKey(next))) {
                return fallback(original, RedirectFallbackReason.LOOP, redirects)
            }
            current = next
        }
    }

    private fun remainingMs(deadlineNanos: Long): Long {
        val remainingNanos = deadlineNanos - clockNanos()
        if (remainingNanos <= 0L) return 0L
        return ((remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND)
            .coerceAtMost(totalTimeoutMs)
    }

    private fun resolveLocation(current: String, location: String): String? {
        val candidate = location.trim().takeIf { it.isNotEmpty() && it.length <= MAX_URL_CHARS } ?: return null
        CreativeCtaRouter.admittedHttpUrl(candidate)?.let { return it }
        CreativeCtaRouter.normalizeTappedDestination(candidate)?.let { normalized ->
            CreativeCtaRouter.admittedDirectPlayStoreUrl(normalized)?.let { return it }
        }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.isAbsolute) return null
        val resolved = runCatching { URL(URL(current), candidate).toExternalForm() }.getOrNull() ?: return null
        return CreativeCtaRouter.admittedHttpUrl(resolved)?.takeIf { it.length <= MAX_URL_CHARS }
    }

    private fun loopKey(url: String): String = runCatching {
        val parsed = URL(url)
        val port = if (parsed.port >= 0) parsed.port else parsed.defaultPort
        "${parsed.protocol.lowercase()}://${parsed.host.lowercase()}:$port${parsed.file}"
    }.getOrDefault(url)

    private fun fallback(
        original: String,
        reason: RedirectFallbackReason,
        redirects: Int,
    ) = PlayStoreRedirectResolution.BrowserFallback(original, reason, redirects)

    private fun Long.saturatingAdd(other: Long): Long =
        if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

    private companion object {
        const val MAX_REDIRECTS = 5
        const val TOTAL_TIMEOUT_MS = 1_500L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_URL_CHARS = 8 * 1024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
