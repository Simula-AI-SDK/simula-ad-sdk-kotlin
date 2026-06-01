package ad.simula.ad.sdk.image

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.network.SimulaHttp
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/** Result of decoding a remote image. */
internal sealed interface DecodedImage {
    /** A decoded, downsampled still bitmap. */
    class Static(val bitmap: Bitmap) : DecodedImage

    /** Encoded bytes of an animated source (GIF / animated WebP), built into a drawable per use. */
    class Animated(val bytes: ByteArray) : DecodedImage

    /** Network or decode failure. */
    object Failed : DecodedImage
}

/**
 * Thread-safe in-memory image cache with cost-based eviction and in-flight
 * request de-duplication.
 *
 * Mirrors the Swift SDK's `CoverImageCache` goals natively:
 *  - [LruCache] sized by byte cost, capped at min(¼ heap, 96 MB).
 *  - Concurrent loads of the same URL share one network + decode via a
 *    [ConcurrentHashMap] of [Deferred]s launched in [SimulaScope]; the download
 *    therefore survives the requesting composable being torn down and completes
 *    into the cache for next time.
 *  - Failures are negatively cached for a short TTL only (so a transient blip
 *    recovers, while a broken URL isn't refetched on every scroll).
 *  - Cache cleared on memory pressure via a [ComponentCallbacks2] registered on
 *    the application context.
 */
internal object ImageCache {

    private const val HARD_CEILING_BYTES = 96 * 1024 * 1024
    private const val MIN_BYTES = 8 * 1024 * 1024
    private const val NEGATIVE_TTL_MS = 30_000L

    private val costLimit: Int = run {
        val quarterHeap = Runtime.getRuntime().maxMemory() / 4
        minOf(quarterHeap, HARD_CEILING_BYTES.toLong()).toInt().coerceAtLeast(MIN_BYTES)
    }

    private val cache = object : LruCache<String, DecodedImage>(costLimit) {
        override fun sizeOf(key: String, value: DecodedImage): Int = when (value) {
            is DecodedImage.Static -> value.bitmap.allocationByteCount.coerceAtLeast(1)
            is DecodedImage.Animated -> value.bytes.size.coerceAtLeast(1)
            DecodedImage.Failed -> 1
        }
    }

    private val inFlight = ConcurrentHashMap<String, Deferred<DecodedImage>>()

    /** url -> last failure timestamp (negative cache, TTL-bounded). */
    private val failures = ConcurrentHashMap<String, Long>()

    @Volatile private var callbacksRegistered = false

    /**
     * Load (or de-duplicate a load of) [url]. Suspends the caller until the
     * shared decode completes. Cancelling the caller does not cancel the shared
     * download (it runs in [SimulaScope]); the result is cached for reuse.
     */
    suspend fun load(context: Context, url: String): DecodedImage {
        registerMemoryCallbacks(context)
        cache.get(url)?.let { return it }
        if (url.isBlank()) return DecodedImage.Failed
        failures[url]?.let { failedAt ->
            if (now() - failedAt < NEGATIVE_TTL_MS) return DecodedImage.Failed
            failures.remove(url, failedAt) // TTL expired — allow a retry
        }

        var created: Deferred<DecodedImage>? = null
        val deferred = inFlight.computeIfAbsent(url) { key ->
            SimulaScope.async { performLoad(key) }.also { created = it }
        }
        // Only the call that actually created the job registers cleanup (value-matched
        // remove, so a newer in-flight entry for the same URL is never clobbered).
        created?.invokeOnCompletion { inFlight.remove(url, deferred) }
        return deferred.await()
    }

    private suspend fun performLoad(url: String): DecodedImage {
        val result = try {
            val bytes = SimulaHttp.requestBytes(url)
            ImageDecoder.decode(bytes)
        } catch (ce: CancellationException) {
            throw ce // never record a cancellation — stays immediately retryable
        } catch (e: Exception) {
            DecodedImage.Failed
        }
        if (result is DecodedImage.Failed) {
            failures[url] = now() // negative-cache for the TTL, don't pollute the LruCache
        } else {
            cache.put(url, result)
            failures.remove(url)
        }
        return result
    }

    private fun clearAll() {
        cache.evictAll()
        failures.clear()
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun registerMemoryCallbacks(context: Context) {
        if (callbacksRegistered) return
        synchronized(this) {
            if (callbacksRegistered) return
            context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) clearAll()
                }

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() {
                    clearAll()
                }

                override fun onConfigurationChanged(newConfig: Configuration) {}
            })
            callbacksRegistered = true
        }
    }
}
