package ad.simula.ad.sdk.image

import ad.simula.ad.sdk.R
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.telemetry.Telemetry
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Small cost-bounded LRU with process-scope single-flight loading. Android-free state/coordination is
 * kept here so the concurrency and eviction behavior is deterministic in JVM tests.
 */
internal class BoundedSingleFlightCache<K : Any, V : Any>(
    private val maxCost: Int,
    private val scope: CoroutineScope,
    private val costOf: (V) -> Int,
) {
    private val lock = Any()
    private val cache = LinkedHashMap<K, V>(4, 0.75f, true)
    private val costs = HashMap<K, Int>()
    private var currentCost = 0
    private val inFlight = ConcurrentHashMap<K, Deferred<V?>>()

    suspend fun load(key: K, producer: suspend () -> V?): V? {
        synchronized(lock) { cache[key] }?.let { return it }

        var created: Deferred<V?>? = null
        val deferred = inFlight.computeIfAbsent(key) {
            scope.async {
                producer()?.also { value -> put(key, value) }
            }.also { created = it }
        }
        created?.invokeOnCompletion { inFlight.remove(key, deferred) }
        return deferred.await()
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
            costs.clear()
            currentCost = 0
        }
    }

    internal fun cachedKeys(): List<K> = synchronized(lock) { cache.keys.toList() }

    private fun put(key: K, value: V) {
        val cost = runCatching { costOf(value).coerceAtLeast(1) }.getOrDefault(maxCost + 1)
        if (maxCost <= 0 || cost > maxCost) return
        synchronized(lock) {
            cache.remove(key)
            currentCost -= costs.remove(key) ?: 0
            cache[key] = value
            costs[key] = cost
            currentCost += cost
            val iterator = cache.entries.iterator()
            while (currentCost > maxCost && iterator.hasNext()) {
                val eldest = iterator.next()
                currentCost -= costs.remove(eldest.key) ?: 0
                iterator.remove()
            }
        }
    }
}

/**
 * Bounded cache for the SDK's three bundled WebP assets. Decode is launched in [SimulaScope]
 * (Dispatchers.IO), never from composition/main, and concurrent requests for one resource share a
 * single decode. The target sizes avoid materializing the 2048px source at its ~16 MB full cost.
 */
internal object BundledResourceImageCache {
    private const val MAX_CACHE_BYTES = 12 * 1024 * 1024
    private const val GAME_ICON_EDGE = 256
    private const val UNAVAILABLE_EDGE = 512
    private const val INTERSTITIAL_BACKGROUND_EDGE = 1536

    private val cache = BoundedSingleFlightCache<Int, Bitmap>(
        maxCost = MAX_CACHE_BYTES,
        scope = SimulaScope,
        costOf = { bitmap -> bitmap.allocationByteCount.coerceAtLeast(1) },
    )

    @Volatile private var callbacksRegistered = false

    suspend fun load(context: Context, @DrawableRes resourceId: Int): Bitmap? {
        val appContext = context.applicationContext
        registerMemoryCallbacks(appContext)
        return cache.load(resourceId) {
            decode(appContext, resourceId)
        }
    }

    private fun decode(context: Context, @DrawableRes resourceId: Int): Bitmap? {
        return try {
            val maxLongEdge = when (resourceId) {
                R.drawable.game_icon -> GAME_ICON_EDGE
                R.drawable.games_unavailable -> UNAVAILABLE_EDGE
                R.drawable.minigame_interstitial_background -> INTERSTITIAL_BACKGROUND_EDGE
                else -> UNAVAILABLE_EDGE
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, resourceId, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxLongEdge)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeResource(context.resources, resourceId, options)
            }
        } catch (throwable: Throwable) {
            Telemetry.recordError(
                signature = "image:decode_fatal",
                errorCode = throwable.javaClass.simpleName,
                breadcrumb = "BundledResourceImageCache.decode",
            )
            null
        }
    }

    private fun sampleSizeFor(width: Int, height: Int, maxLongEdge: Int): Int {
        var sample = 1
        var longEdge = maxOf(width, height)
        while (longEdge / 2 >= maxLongEdge) {
            sample *= 2
            longEdge /= 2
        }
        return sample
    }

    private fun registerMemoryCallbacks(context: Context) {
        if (callbacksRegistered) return
        synchronized(this) {
            if (callbacksRegistered) return
            context.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cache.clear()
                }

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() {
                    cache.clear()
                }

                override fun onConfigurationChanged(newConfig: Configuration) {}
            })
            callbacksRegistered = true
        }
    }
}

private sealed interface BundledResourceImagePhase {
    data object Loading : BundledResourceImagePhase
    class Ready(val bitmap: Bitmap) : BundledResourceImagePhase
    data object Failed : BundledResourceImagePhase
}

/**
 * Phase-based renderer for a bundled drawable. Loading and failure always occupy [modifier], keeping
 * layout stable while the process-scope decode completes or if it safely degrades.
 */
@Composable
internal fun BundledResourceImage(
    @DrawableRes resourceId: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    placeholder: @Composable BoxScope.() -> Unit = {},
    fallback: @Composable BoxScope.() -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    var phase by remember(resourceId) {
        mutableStateOf<BundledResourceImagePhase>(BundledResourceImagePhase.Loading)
    }

    LaunchedEffect(resourceId) {
        phase = BundledResourceImagePhase.Loading
        phase = BundledResourceImageCache.load(context, resourceId)
            ?.let(BundledResourceImagePhase::Ready)
            ?: BundledResourceImagePhase.Failed
    }

    when (val current = phase) {
        BundledResourceImagePhase.Loading -> Box(modifier = modifier, content = placeholder)
        BundledResourceImagePhase.Failed -> Box(modifier = modifier, content = fallback)
        is BundledResourceImagePhase.Ready -> Image(
            bitmap = current.bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}
