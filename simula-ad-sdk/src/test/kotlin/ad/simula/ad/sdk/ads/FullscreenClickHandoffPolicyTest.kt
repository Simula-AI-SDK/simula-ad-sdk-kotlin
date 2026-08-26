package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.network.ClickInteractionGate
import ad.simula.ad.sdk.network.ClickPersistenceHandoff
import ad.simula.ad.sdk.network.ClickPersistencePart
import ad.simula.ad.sdk.network.ClickSources
import ad.simula.ad.sdk.network.BeaconPersistenceOutcome
import ad.simula.ad.sdk.network.ClickRouteStart
import ad.simula.ad.sdk.network.ClickHandoffResult
import ad.simula.ad.sdk.network.PresentationRouteResult
import ad.simula.ad.sdk.network.ResumedPresentationRoute
import ad.simula.ad.sdk.network.SimulaApiClient
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenClickHandoffPolicyTest {
    private class TestScheduler : ClickHandoffScheduler {
        private val ready = ArrayDeque<Runnable>()
        private val delayed = LinkedHashSet<Runnable>()

        override fun post(block: Runnable) { ready += block }
        override fun postDelayed(block: Runnable, delayMs: Long) { delayed += block }
        override fun remove(block: Runnable) { delayed -= block }

        fun runReady() {
            while (ready.isNotEmpty()) ready.removeFirst().run()
        }
    }

    @Test
    fun `dismissal requires unlocked close and no pending click`() {
        assertFalse(canDismissFullscreen(dismissUnlocked = false, hasPendingClick = false))
        assertFalse(canDismissFullscreen(dismissUnlocked = false, hasPendingClick = true))
        assertFalse(canDismissFullscreen(dismissUnlocked = true, hasPendingClick = true))
        assertTrue(canDismissFullscreen(dismissUnlocked = true, hasPendingClick = false))
    }

    @Test
    fun `dismissal stays blocked until persisted click handoff reaches route`() {
        val gate = ClickInteractionGate(idFactory = { "interaction" })
        val handoff = ClickPersistenceHandoff(
            requireNotNull(gate.claim(ClickSources.INSTALL_BANNER)),
        ) {}

        assertFalse(canDismissFullscreen(dismissUnlocked = true, hasPendingClick = gate.hasPendingClaim()))
        handoff.complete(ClickPersistencePart.TELEMETRY)
        handoff.complete(ClickPersistencePart.BEACON)
        assertFalse(canDismissFullscreen(dismissUnlocked = true, hasPendingClick = gate.hasPendingClaim()))

        assertTrue(handoff.handoff { true })
        assertTrue(canDismissFullscreen(dismissUnlocked = true, hasPendingClick = gate.hasPendingClaim()))
    }

    @Test
    fun `primary CTA persists one stable beacon and telemetry before route handoff`() {
        var now = 1_000L
        val gate = ClickInteractionGate(clockMs = { now }, idFactory = { "interaction-1" })
        val claim = requireNotNull(gate.claim(ClickSources.PRIMARY_CTA))
        val scheduler = TestScheduler()
        val order = mutableListOf<String>()
        val beacons = mutableListOf<Pair<String, String>>()

        coordinateClickPersistence(
            scheduler = scheduler,
            claim = claim,
            enqueueBeacon = { complete ->
                beacons += claim.interaction.id to claim.interaction.source
                order += "beacon_persisted"
                complete(BeaconPersistenceOutcome.Persisted)
            },
            recordTelemetry = { complete ->
                order += "telemetry_persisted"
                complete()
            },
            onHandoff = {
                order += "route"
                true
            },
        )

        assertEquals(
            listOf("beacon_persisted", "telemetry_persisted"),
            order,
        )
        assertEquals(listOf("interaction-1" to ClickSources.PRIMARY_CTA), beacons)
        assertNull("a duplicate callback cannot create another interaction", gate.claim(ClickSources.PRIMARY_CTA))

        scheduler.runReady()

        assertEquals(listOf("beacon_persisted", "telemetry_persisted", "route"), order)
        assertEquals(1, beacons.size)
        now = 1_100L
        assertNull("successful route starts the duplicate window", gate.claim(ClickSources.PRIMARY_CTA))
    }

    @Test
    fun `failed primary CTA route remains accounted and does not notify publisher`() {
        var nextId = 0
        val gate = ClickInteractionGate(idFactory = { "interaction-${++nextId}" })
        val claim = requireNotNull(gate.claim(ClickSources.PRIMARY_CTA))
        val scheduler = TestScheduler()
        var publisherClicks = 0

        coordinateClickPersistence(
            scheduler = scheduler,
            claim = claim,
            enqueueBeacon = { it(BeaconPersistenceOutcome.Persisted) },
            recordTelemetry = { it() },
            onHandoff = {
                val opened = false
                if (opened) publisherClicks++
                opened
            },
        )
        scheduler.runReady()

        assertEquals(0, publisherClicks)
        assertNull("persisted interaction cannot be reissued after route failure", gate.claim(ClickSources.PRIMARY_CTA))
    }

    @Test
    fun `only the current live Activity may route after persistence`() {
        assertTrue(canRouteFromCurrentFullscreenActivity(false, false))
        assertFalse(canRouteFromCurrentFullscreenActivity(true, false))
        assertFalse(canRouteFromCurrentFullscreenActivity(false, true))
    }

    @Test
    fun `throwing publisher click callback is isolated`() {
        var continued = false

        notifyPublisherClick { throw IllegalStateException("host callback") }
        continued = true

        assertTrue(continued)
    }

    @Test
    fun `deferred persisted handoff stays pending until resumed route reports outcome`() {
        val gate = ClickInteractionGate(idFactory = { "interaction" })
        val claim = requireNotNull(gate.claim(ClickSources.PRIMARY_CTA))
        val scheduler = TestScheduler()
        val routes = ResumedPresentationRoute<Any>()
        val host = Any()
        var finished = 0
        var routeAttempts = 0
        var terminalResult: ClickHandoffResult? = null
        routes.attach(host)

        val handoff = coordinateDeferredClickPersistence(
            scheduler = scheduler,
            claim = claim,
            enqueueBeacon = { it(BeaconPersistenceOutcome.Persisted) },
            recordTelemetry = { it() },
            onHandoff = { _, completion ->
                val result = routes.request(
                    route = { routeAttempts++; false },
                    completion = completion,
                )
                if (result == PresentationRouteResult.REJECTED) ClickRouteStart.REJECTED else ClickRouteStart.STARTED
            },
            onFinished = { finished++ },
        )
        handoff.addResultListener { terminalResult = it }
        scheduler.runReady()

        assertFalse("route has not run while host is paused", handoff.isTerminal())
        assertEquals(0, routeAttempts)
        assertEquals(0, finished)

        routes.resume(host)
        scheduler.runReady()

        assertTrue(handoff.isTerminal())
        assertEquals(1, routeAttempts)
        assertEquals(1, finished)
        assertEquals(ClickHandoffResult.ACCOUNTED, terminalResult)
    }

    @Test
    fun `cancelled deferred handoff never routes after resume`() {
        val gate = ClickInteractionGate(idFactory = { "interaction" })
        val claim = requireNotNull(gate.claim(ClickSources.PRIMARY_CTA))
        val scheduler = TestScheduler()
        val routes = ResumedPresentationRoute<Any>()
        val host = Any()
        var attempts = 0
        routes.attach(host)

        val handoff = coordinateDeferredClickPersistence(
            scheduler = scheduler,
            claim = claim,
            enqueueBeacon = { it(BeaconPersistenceOutcome.Persisted) },
            recordTelemetry = { it() },
            onHandoff = { _, completion ->
                val result = routes.request({ attempts++; true }, completion)
                if (result == PresentationRouteResult.REJECTED) ClickRouteStart.REJECTED else ClickRouteStart.STARTED
            },
        )
        scheduler.runReady()
        routes.cancel()
        handoff.cancel()
        routes.resume(host)
        scheduler.runReady()

        assertEquals(0, attempts)
        assertTrue(handoff.isTerminal())
    }

    @Test
    fun `deferred successful open completes handoff as routed`() {
        val gate = ClickInteractionGate(idFactory = { "interaction" })
        val scheduler = TestScheduler()
        val routes = ResumedPresentationRoute<Any>()
        val host = Any()
        var terminalResult: ClickHandoffResult? = null
        routes.attach(host)

        val handoff = coordinateDeferredClickPersistence(
            scheduler = scheduler,
            claim = requireNotNull(gate.claim(ClickSources.PRIMARY_CTA)),
            enqueueBeacon = { it(BeaconPersistenceOutcome.Persisted) },
            recordTelemetry = { it() },
            onHandoff = { _, completion ->
                val result = routes.request({ true }, completion)
                if (result == PresentationRouteResult.REJECTED) ClickRouteStart.REJECTED else ClickRouteStart.STARTED
            },
        )
        handoff.addResultListener { terminalResult = it }
        scheduler.runReady()
        assertFalse(handoff.isTerminal())

        routes.resume(host)
        scheduler.runReady()

        assertEquals(ClickHandoffResult.ROUTED, terminalResult)
        assertTrue(handoff.isTerminal())
    }

    @Test
    fun `fallback CTA persists stable native interaction and source`() {
        val gate = ClickInteractionGate(idFactory = { "fallback-interaction" })
        val claim = requireNotNull(gate.claim(ClickSources.FALLBACK_CTA))
        val scheduler = TestScheduler()
        val captured = mutableListOf<Pair<String, String>>()

        coordinateDeferredClickPersistence(
            scheduler = scheduler,
            claim = claim,
            enqueueBeacon = { complete ->
                captured += claim.interaction.id to claim.interaction.source
                complete(BeaconPersistenceOutcome.Persisted)
            },
            recordTelemetry = { it() },
            onHandoff = { _, complete ->
                complete(true)
                ClickRouteStart.STARTED
            },
        )
        scheduler.runReady()

        assertEquals(listOf("fallback-interaction" to ClickSources.FALLBACK_CTA), captured)
    }

    @Test
    fun `rejected beacon persistence reports diagnostic without trapping route`() {
        val gate = ClickInteractionGate(idFactory = { "interaction" })
        val scheduler = TestScheduler()
        val issues = mutableListOf<Pair<String, String>>()
        var routes = 0

        coordinateDeferredClickPersistence(
            scheduler = scheduler,
            claim = requireNotNull(gate.claim(ClickSources.PRIMARY_CTA)),
            enqueueBeacon = { it(BeaconPersistenceOutcome.Rejected) },
            recordTelemetry = { it() },
            onHandoff = { _, complete ->
                routes++
                complete(true)
                ClickRouteStart.STARTED
            },
            recordPersistenceIssue = { signature, breadcrumb -> issues += signature to breadcrumb },
        )
        scheduler.runReady()

        assertEquals(1, routes)
        assertEquals(
            listOf("click:persistence_rejected" to "part=beacon;outcome=rejected"),
            issues,
        )
    }

    @Test
    fun `fallback presentation blocks screen advance while click is pending`() {
        val state = FallbackPresentationState()
        state.showing(0)
        state.setClickPending(true)

        assertFalse(state.advance(total = 2))
        assertEquals(0, state.index)
        assertEquals(FallbackStage.SHOWING, state.stage)

        state.setClickPending(false)
        assertTrue(state.advance(total = 2))
        assertEquals(1, state.index)
        assertEquals(FallbackStage.SHOWING, state.stage)
        assertTrue(state.advance(total = 2))
        assertEquals(FallbackStage.DONE, state.stage)
    }

    @Test
    fun `fallback presentation retains phase index and pending ownership for recreation`() {
        val state = FallbackPresentationState()
        state.showing(2)
        state.setClickPending(true)

        assertEquals(FallbackStage.SHOWING, state.stage)
        assertEquals(2, state.index)
        assertTrue(state.clickHandoffPending)
    }

    @Test
    fun `fallback presentation retains fetched ads and accepted click through refetch failure`() {
        val state = FallbackPresentationState()
        val ads = listOf(SimulaApiClient.FallbackAd("ad-1", html = "<html/>"))
        state.retainFetchedAds(ads)
        state.showing(0)
        state.setClickPending(true)

        state.fetchFailed()

        assertEquals(ads, state.fetchedAds)
        assertEquals(FallbackStage.SHOWING, state.stage)
        assertEquals(0, state.index)
        assertTrue(state.clickHandoffPending)
    }

    @Test
    fun `initial fallback fetch exhaustion retains terminal empty result`() {
        val state = FallbackPresentationState()

        assertTrue(state.terminalizeInitialFetchFailure().isEmpty())
        assertEquals(emptyList<SimulaApiClient.FallbackAd>(), state.fetchedAds)
    }

    @Test
    fun `fetch exhaustion never overwrites retained fallback content`() {
        val state = FallbackPresentationState()
        val retained = listOf(SimulaApiClient.FallbackAd("retained", html = "<html/>"))
        state.retainFetchedAds(retained)

        assertEquals(retained, state.terminalizeInitialFetchFailure())
        assertEquals(retained, state.fetchedAds)
    }

    @Test
    fun `fallback navigation ownership rebinds to replacement overlay`() {
        val state = FallbackPresentationState()
        val oldOwner = Any()
        val replacement = Any()
        val calls = mutableListOf<String>()
        state.bindNavigation(oldOwner) { calls += "old:$it" }
        state.bindNavigation(replacement) { calls += "new:$it" }

        state.unbindNavigation(oldOwner)
        state.navigate("https://tracker.example")

        assertEquals(listOf("new:https://tracker.example"), calls)
    }

    @Test
    fun `fallback click admission persists for same index and resets for next screen`() {
        val state = FallbackPresentationState()
        val first = state.clickAdmission(0)
        assertTrue(first.disable())

        assertSame(first, state.clickAdmission(0))
        assertFalse(state.clickAdmission(0).isEnabled())
        assertTrue(state.clickAdmission(1).isEnabled())
    }
}
