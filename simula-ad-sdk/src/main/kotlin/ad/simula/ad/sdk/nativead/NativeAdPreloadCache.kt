package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.ads.SimulaAds
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.model.normalizeExtraParameters
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.telemetry.Telemetry
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Imperative one-at-a-time preload registry behind [SimulaAds.preloadNativeAd] /
 * [SimulaAds.destroyPreloadedAd].
 *
 * Each [preload] fires exactly one `POST /load/native` (using the current provider context +
 * `SimulaAds.store`'s session) into a held [Deferred], and returns a `preloadedAdId`. When a
 * [ad.simula.ad.sdk.nativead.NativeAdSlot] mounts with that id it [consume]s the entry — rendering
 * from cache with no live network call — and the entry is evicted. Unconsumed ids must be released
 * with [destroy].
 *
 * At most [MAX] ads are kept at once; further preloads are dropped and recorded by telemetry.
 * Backed by a [ConcurrentHashMap] so it's safe to call from any thread.
 */
internal object NativeAdPreloadCache {

    private const val MAX = 5
    private const val TAG = "SimulaNativeAd"

    internal data class PreloadedNativeAd(
        val result: SimulaApiClient.NativeAdResult,
        val metadata: Map<String, String>?,
    )

    private data class Entry(
        val deferred: Deferred<SimulaApiClient.NativeAdResult>,
        val metadata: Map<String, String>?,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Guards the cap check + insert so two concurrent [preload]s can't both pass `size < MAX`. */
    private val capLock = Any()

    /** Fire one preload and return its id, or null if the [MAX] cap is already reached. */
    fun preload(
        adUnitId: String?,
        position: Int,
        theme: String? = null,
        metadata: Map<String, String> = emptyMap(),
        load: suspend (Map<String, String>?) -> SimulaApiClient.NativeAdResult = { snapshot ->
            NativeAdController.load(
                ensureSession = { SimulaAds.store.ensureSession() },
                adUnitId = adUnitId,
                position = position,
                theme = theme,
                metadata = snapshot,
            )
        },
    ): String? {
        val metadataSnapshot = normalizeExtraParameters(metadata)
        // The check-and-insert must be atomic: a plain `size >= MAX` check then a separate put let
        // two concurrent callers both pass the check and overshoot the cap. Launching the request
        // inside the lock is fine — async() returns immediately without blocking.
        synchronized(capLock) {
            if (entries.size >= MAX) {
                Log.w(TAG, "preloadNativeAd ignored: at most $MAX preloaded ads are kept at once.")
                Telemetry.recordOperation("native_preload_capped", 0L, false)
                return null
            }
            val id = UUID.randomUUID().toString()
            // async on the process-lifetime supervisor scope: an un-awaited failure can't crash the
            // host, and the exception is surfaced only when consume() awaits (then mapped to a live fallback).
            val deferred = SimulaScope.async {
                load(metadataSnapshot)
            }
            entries[id] = Entry(deferred, metadataSnapshot)
            return id
        }
    }

    /**
     * Await and remove the preloaded ad for [id]. Returns null if the id is unknown (expired,
     * destroyed, or already consumed) or if its load failed — the caller then falls back to a live
     * request, surfacing no error (PRD).
     */
    suspend fun consume(id: String): PreloadedNativeAd? {
        val entry = entries[id] ?: return null
        return try {
            val result = entry.deferred.await()
            if (!entries.remove(id, entry)) return null
            PreloadedNativeAd(result, entry.metadata)
        } catch (e: CancellationException) {
            // Caller cancellation means the slot was recycled: preserve the process-scoped preload
            // for remount. A still-active caller means destroy() or the loader cancelled the deferred;
            // consume that terminal entry and fail open to the slot's live-load fallback.
            currentCoroutineContext().ensureActive()
            entries.remove(id, entry)
            null
        } catch (_: Exception) {
            entries.remove(id, entry)
            null
        }
    }

    /** Release a preloaded ad that was never consumed, cancelling its request if still in flight. */
    fun destroy(id: String) {
        entries.remove(id)?.deferred?.cancel()
    }
}
