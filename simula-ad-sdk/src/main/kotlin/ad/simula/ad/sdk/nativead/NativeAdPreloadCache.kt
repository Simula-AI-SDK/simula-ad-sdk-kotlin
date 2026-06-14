package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.ads.SimulaAds
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.telemetry.Telemetry
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
 * At most [MAX] ads are kept at once; further preloads are dropped (PRD: "cap silently at 5, log
 * warning internally"). Backed by a [ConcurrentHashMap] so it's safe to call from any thread.
 */
internal object NativeAdPreloadCache {

    private const val MAX = 5
    private const val TAG = "SimulaNativeAd"

    private data class Entry(
        val deferred: Deferred<SimulaApiClient.NativeAdResult>,
        val adUnitId: String?,
        val position: Int,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Fire one preload and return its id, or null if the [MAX] cap is already reached. */
    fun preload(adUnitId: String?, position: Int, theme: String? = null): String? {
        if (entries.size >= MAX) {
            Log.w(TAG, "preloadNativeAd ignored — at most $MAX preloaded ads are kept at once.")
            Telemetry.recordOperation("native_preload_capped", 0L, false)
            return null
        }
        val id = UUID.randomUUID().toString()
        // async on the process-lifetime supervisor scope: an un-awaited failure can't crash the host,
        // and the exception is surfaced only when consume() awaits (then mapped to a live fallback).
        val deferred = SimulaScope.async {
            NativeAdController.load(
                ensureSession = { SimulaAds.store.ensureSession() },
                adUnitId = adUnitId,
                position = position,
                theme = theme,
            )
        }
        entries[id] = Entry(deferred, adUnitId, position)
        return id
    }

    /**
     * Await and remove the preloaded ad for [id]. Returns null if the id is unknown (expired,
     * destroyed, or already consumed) or if its load failed — the caller then falls back to a live
     * request, surfacing no error (PRD).
     */
    suspend fun consume(id: String): SimulaApiClient.NativeAdResult? {
        val entry = entries.remove(id) ?: return null
        return try {
            entry.deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Release a preloaded ad that was never consumed, cancelling its request if still in flight. */
    fun destroy(id: String) {
        entries.remove(id)?.deferred?.cancel()
    }
}
