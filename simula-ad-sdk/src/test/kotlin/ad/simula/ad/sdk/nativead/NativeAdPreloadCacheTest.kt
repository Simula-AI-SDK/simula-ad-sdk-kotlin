package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.network.SimulaApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NativeAdPreloadCacheTest {

    @Test
    fun `preload snapshots metadata for load and consumption`() = runBlocking {
        val source = linkedMapOf("screen" to "search")
        val releaseLoad = CompletableDeferred<Unit>()
        var loadMetadata: Map<String, String>? = null
        val id = NativeAdPreloadCache.preload(
            adUnitId = "feed",
            position = 1,
            metadata = source,
            load = { snapshot ->
                loadMetadata = snapshot
                releaseLoad.await()
                nativeResult()
            },
        )

        source["screen"] = "mutated"
        releaseLoad.complete(Unit)
        assertNotNull(id)
        val consumed = NativeAdPreloadCache.consume(id ?: error("missing preload id"))

        assertEquals(mapOf("screen" to "search"), loadMetadata)
        assertEquals(mapOf("screen" to "search"), consumed?.metadata)
    }

    @Test
    fun `preload without metadata retains an empty snapshot`() = runBlocking {
        val id = NativeAdPreloadCache.preload(
            adUnitId = "feed",
            position = 2,
            load = { nativeResult() },
        )

        assertNotNull(id)
        val consumed = NativeAdPreloadCache.consume(id ?: error("missing preload id"))

        assertEquals(null, consumed?.metadata)
    }

    @Test
    fun `cancelled consumer leaves in-flight preload available for remount`() = runBlocking {
        coroutineScope {
            val releaseLoad = CompletableDeferred<Unit>()
            val loadStarted = CompletableDeferred<Unit>()
            val id = NativeAdPreloadCache.preload(
                adUnitId = "feed",
                position = 3,
                metadata = mapOf("screen" to "search"),
                load = {
                    loadStarted.complete(Unit)
                    releaseLoad.await()
                    nativeResult()
                },
            ) ?: error("missing preload id")
            val firstConsumer = async { NativeAdPreloadCache.consume(id) }
            loadStarted.await()

            firstConsumer.cancelAndJoin()
            releaseLoad.complete(Unit)
            val remounted = NativeAdPreloadCache.consume(id)

            assertEquals(mapOf("screen" to "search"), remounted?.metadata)
        }
    }

    private fun nativeResult() = SimulaApiClient.NativeAdResult(
        impressionId = "preloaded-impression",
        adInserted = true,
        adFormat = "character_ad",
        iframeUrl = null,
        renderedHtml = "<html></html>",
    )
}
