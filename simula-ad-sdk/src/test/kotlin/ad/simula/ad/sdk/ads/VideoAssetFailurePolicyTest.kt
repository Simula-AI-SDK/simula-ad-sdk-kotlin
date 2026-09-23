package ad.simula.ad.sdk.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAssetFailurePolicyTest {
    @Test
    fun `interstitial and rewarded cache failures use the canonical callback and telemetry matrix`() {
        val expected = mapOf(
            VideoAssetCacheError.INVALID_URL to ("no_fill" to "invalid_url"),
            VideoAssetCacheError.UNSAFE_TARGET to ("no_fill" to "unsafe_target"),
            VideoAssetCacheError.UNAVAILABLE to ("network" to "transfer_failed"),
            VideoAssetCacheError.TOO_LARGE to ("no_fill" to "asset_too_large"),
            VideoAssetCacheError.CACHE_FULL to ("no_fill" to "cache_full"),
            VideoAssetCacheError.ADMISSION_OVERFLOW to ("no_fill" to "cache_admission"),
            VideoAssetCacheError.STALE to ("no_fill" to "cache_admission"),
            VideoAssetCacheError.TIMED_OUT to ("network" to "cache_timeout"),
        )

        for (surface in listOf("interstitial", "rewarded")) {
            expected.forEach { (error, classification) ->
                val failure = videoAssetLoadFailure(error)
                assertEquals("surface=$surface error=$error", classification.first, failure?.callbackError?.telemetryCode())
                assertEquals("surface=$surface error=$error", classification.second, failure?.telemetryCode)
                assertEquals("video_asset:${classification.second}", failure?.telemetrySignature)
                if (classification.first == "network") assertTrue(failure?.callbackError is SimulaAdError.Network)
                else assertSame(SimulaAdError.NoFill, failure?.callbackError)
            }
        }
    }
}
