package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.AdValue
import ad.simula.ad.sdk.model.Creative
import ad.simula.ad.sdk.model.CreativeType
import ad.simula.ad.sdk.model.RewardCompletionReason
import ad.simula.ad.sdk.network.ClickInteraction
import ad.simula.ad.sdk.network.SimulaApiClient
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FullscreenLaunchOwnershipTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `interstitial failed launch preserves Ready lease and retry owns normal callbacks`() {
        val releases = AtomicInteger()
        val displayed = AtomicInteger()
        val closed = AtomicInteger()
        val lease = VideoAssetLease(temporaryFolder.newFile("interstitial.mp4")) {
            releases.incrementAndGet()
        }
        val presentation = InterstitialPresentation(
            ad = SimulaApiClient.AdLoadResult(
                impressionId = "interstitial-serve",
                adInserted = true,
                adUnitId = "interstitial-unit",
                destination = "web",
                renderedFormat = "video",
                trackingUrl = null,
                renderedHtml = null,
                creative = Creative(type = CreativeType.VIDEO),
            ),
            apiKey = "key",
            callbacks = object : InterstitialCallbacks {
                override fun onDisplayed() {
                    displayed.incrementAndGet()
                }

                override fun onImpression() = Unit
                override fun onPaid(adValue: AdValue) = Unit
                override fun persistClick(interaction: ClickInteraction, onTelemetryPersisted: () -> Unit) = Unit
                override fun notifyClicked(interaction: ClickInteraction) = Unit
                override fun onClosed() {
                    closed.incrementAndGet()
                }
            },
            videoLease = lease,
        )
        var state = TestAdState.READY
        val failedToken = UUID.randomUUID().toString()

        val firstLaunch = launchWithHandoff(
            publish = { InterstitialHandoff.put(failedToken, presentation) },
            launch = { throw IllegalStateException("startActivity failed") },
            rollback = {
                assertSame(presentation, InterstitialHandoff.recoverAfterLaunchFailure(failedToken))
            },
        )
        if (firstLaunch) state = TestAdState.SHOWING

        assertFalse(firstLaunch)
        assertEquals(TestAdState.READY, state)
        assertEquals(0, releases.get())
        assertTrue(lease.file.exists())
        assertNull(InterstitialHandoff.get(failedToken))

        val retryToken = UUID.randomUUID().toString()
        val retryLaunch = launchWithHandoff(
            publish = { InterstitialHandoff.put(retryToken, presentation) },
            launch = { Unit },
            rollback = { InterstitialHandoff.recoverAfterLaunchFailure(retryToken) },
        )
        if (retryLaunch) state = TestAdState.SHOWING
        val active = requireNotNull(InterstitialHandoff.get(retryToken))
        active.callbacks.onDisplayed()
        active.callbacks.onClosed()
        state = TestAdState.IDLE
        InterstitialHandoff.remove(retryToken)

        assertTrue(retryLaunch)
        assertEquals(TestAdState.IDLE, state)
        assertEquals(1, displayed.get())
        assertEquals(1, closed.get())
        assertEquals(1, releases.get())
    }

    @Test
    fun `rewarded failed launch preserves Ready lease and retry owns normal callbacks`() {
        val releases = AtomicInteger()
        val displayed = AtomicInteger()
        val closed = AtomicInteger()
        val rewarded = AtomicInteger()
        val lease = VideoAssetLease(temporaryFolder.newFile("rewarded.mp4")) {
            releases.incrementAndGet()
        }
        val presentation = RewardedPresentation(
            creative = Creative(type = CreativeType.VIDEO),
            impressionId = "rewarded-serve",
            apiKey = "key",
            callbacks = object : RewardedCallbacks {
                override fun onDisplayed() {
                    displayed.incrementAndGet()
                }

                override fun onImpression() = Unit
                override fun onPaid(adValue: AdValue) = Unit
                override fun persistClick(interaction: ClickInteraction, onTelemetryPersisted: () -> Unit) = Unit
                override fun notifyClicked(interaction: ClickInteraction) = Unit
                override fun onClose(earned: Boolean, elapsedPlayTimeSeconds: Double) {
                    closed.incrementAndGet()
                }

                override fun onRewardCompleted(
                    earned: Boolean,
                    elapsedPlayTimeSeconds: Double,
                    completionReason: RewardCompletionReason?,
                ) {
                    if (earned) rewarded.incrementAndGet()
                }
            },
            videoLease = lease,
        )
        var state = TestAdState.READY
        val failedToken = UUID.randomUUID().toString()

        val firstLaunch = launchWithHandoff(
            publish = { RewardedHandoff.put(failedToken, presentation) },
            launch = { throw IllegalStateException("startActivity failed") },
            rollback = {
                assertSame(presentation, RewardedHandoff.recoverAfterLaunchFailure(failedToken))
            },
        )
        if (firstLaunch) state = TestAdState.SHOWING

        assertFalse(firstLaunch)
        assertEquals(TestAdState.READY, state)
        assertEquals(0, releases.get())
        assertTrue(lease.file.exists())
        assertNull(RewardedHandoff.get(failedToken))

        val retryToken = UUID.randomUUID().toString()
        val retryLaunch = launchWithHandoff(
            publish = { RewardedHandoff.put(retryToken, presentation) },
            launch = { Unit },
            rollback = { RewardedHandoff.recoverAfterLaunchFailure(retryToken) },
        )
        if (retryLaunch) state = TestAdState.SHOWING
        val active = requireNotNull(RewardedHandoff.get(retryToken))
        active.callbacks.onDisplayed()
        active.callbacks.onWholeUnitCompleted(
            earned = true,
            elapsedPlayTimeSeconds = 5.0,
            completionReason = RewardCompletionReason.DURATION_ELAPSED,
        )
        state = TestAdState.IDLE
        RewardedHandoff.remove(retryToken)

        assertTrue(retryLaunch)
        assertEquals(TestAdState.IDLE, state)
        assertEquals(1, displayed.get())
        assertEquals(1, closed.get())
        assertEquals(1, rewarded.get())
        assertEquals(1, releases.get())
    }

    private enum class TestAdState {
        READY,
        SHOWING,
        IDLE,
    }
}
