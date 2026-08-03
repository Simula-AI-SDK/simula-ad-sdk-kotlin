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
}
