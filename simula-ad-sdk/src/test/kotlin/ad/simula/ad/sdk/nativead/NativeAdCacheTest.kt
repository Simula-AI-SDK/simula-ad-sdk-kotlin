package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.network.SimulaApiClient
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeAdCacheTest {

    @Test
    fun `cached fill retains seen metadata snapshot`() {
        val metadata = mapOf("placement" to "feed")
        NativeAdCache.putFill(
            adUnitId = "snapshot-test",
            position = 987,
            result = SimulaApiClient.NativeAdResult(
                impressionId = "snapshot-impression",
                adInserted = true,
                adFormat = "character_ad",
                iframeUrl = null,
                renderedHtml = "<html></html>",
            ),
            seenMetadata = metadata,
        )

        val cached = NativeAdCache.get("snapshot-test", 987) as NativeAdCache.Value.Fill

        assertEquals(metadata, cached.seenMetadata)
    }

    @Test
    fun `preloaded fill retains its preload-time metadata`() {
        val preloadMetadata = mapOf("screen" to "search")
        NativeAdCache.putFill(
            adUnitId = "preload-snapshot-test",
            position = 988,
            result = SimulaApiClient.NativeAdResult(
                impressionId = "preload-snapshot-impression",
                adInserted = true,
                adFormat = "character_ad",
                iframeUrl = null,
                renderedHtml = "<html></html>",
            ),
            seenMetadata = preloadMetadata,
        )

        val cached = NativeAdCache.get("preload-snapshot-test", 988) as NativeAdCache.Value.Fill

        assertEquals(preloadMetadata, cached.seenMetadata)
    }
}
