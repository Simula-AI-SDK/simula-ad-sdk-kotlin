package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.AdValue
import ad.simula.ad.sdk.network.SimulaApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MetadataApiTest {

    @Test
    fun `fullscreen ads expose only canonical metadata setters`() {
        listOf(SimulaInterstitialAd::class.java, SimulaRewardedAd::class.java).forEach { adClass ->
            val metadataSignatures = adClass.declaredMethods
                .filter { it.name == "setMetadata" }
                .map { it.parameterTypes.toList() }
                .toSet()

            assertEquals(
                setOf(
                    listOf(String::class.java, String::class.java),
                    listOf(Map::class.java),
                ),
                metadataSignatures,
            )
            assertFalse(adClass.declaredMethods.any { it.name == "setExtraParameter" })
            assertFalse(adClass.declaredMethods.any { it.name == "setExtraParameters" })
        }
    }

    @Test
    fun `fullscreen presentations retain load-time metadata for seen beacon`() {
        val snapshot = mapOf("placement" to "load-time")
        val interstitial = InterstitialPresentation(
            ad = SimulaApiClient.AdLoadResult(
                impressionId = "interstitial-impression",
                adInserted = true,
                adUnitId = "interstitial-unit",
                destination = "appstore",
                renderedFormat = null,
                trackingUrl = null,
                renderedHtml = "<html></html>",
            ),
            apiKey = "test-key",
            callbacks = object : InterstitialCallbacks {
                override fun onDisplayed() = Unit
                override fun onImpression() = Unit
                override fun onPaid(adValue: AdValue) = Unit
                override fun onClicked() = Unit
                override fun onClosed() = Unit
            },
            metadata = snapshot,
        )
        val rewarded = RewardedPresentation(
            iframeUrl = "https://example.test/creative",
            impressionId = "rewarded-impression",
            apiKey = "test-key",
            callbacks = object : RewardedCallbacks {
                override fun onDisplayed() = Unit
                override fun onImpression() = Unit
                override fun onPaid(adValue: AdValue) = Unit
                override fun onClicked() = Unit
                override fun onClose(earned: Boolean, elapsedPlayTimeSeconds: Double) = Unit
                override fun onRewardCompleted(earned: Boolean, elapsedPlayTimeSeconds: Double) = Unit
            },
            metadata = snapshot,
        )

        assertEquals(snapshot, interstitial.metadata)
        assertEquals(snapshot, rewarded.metadata)
    }
}
