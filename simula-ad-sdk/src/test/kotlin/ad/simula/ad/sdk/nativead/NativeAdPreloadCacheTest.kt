package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.network.SimulaApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NativeAdPreloadCacheTest {

    @Test
    fun `preload invokes metadata-free loader and returns its result`() = runBlocking {
        val releaseLoad = CompletableDeferred<Unit>()
        var loadCount = 0
        val id = NativeAdPreloadCache.preload(
            adUnitId = "feed",
            position = 1,
            load = {
                loadCount++
                releaseLoad.await()
                nativeResult()
            },
        )

        releaseLoad.complete(Unit)
        assertNotNull(id)
        val consumed = NativeAdPreloadCache.consume(id ?: error("missing preload id"))

        assertEquals(1, loadCount)
        assertEquals("preloaded-impression", consumed?.impressionId)
    }

    @Test
    fun `cancelled consumer leaves in-flight preload available for remount`() = runBlocking {
        coroutineScope {
            val releaseLoad = CompletableDeferred<Unit>()
            val loadStarted = CompletableDeferred<Unit>()
            val id = NativeAdPreloadCache.preload(
                adUnitId = "feed",
                position = 3,
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

            assertEquals("preloaded-impression", remounted?.impressionId)
        }
    }

    @Test
    fun `destroyed preload returns null to an active consumer for live fallback`() = runBlocking {
        val loadStarted = CompletableDeferred<Unit>()
        val id = NativeAdPreloadCache.preload(
            adUnitId = "feed",
            position = 4,
            load = {
                loadStarted.complete(Unit)
                awaitCancellation()
            },
        ) ?: error("missing preload id")
        val consumer = async(start = CoroutineStart.UNDISPATCHED) { NativeAdPreloadCache.consume(id) }
        loadStarted.await()

        NativeAdPreloadCache.destroy(id)

        assertNull(consumer.await())
    }

    private fun nativeResult() = SimulaApiClient.NativeAdResult(
        impressionId = "preloaded-impression",
        adInserted = true,
        adFormat = "character_ad",
        iframeUrl = null,
        renderedHtml = "<html></html>",
    )
}
