package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.network.ClickInteractionGate
import ad.simula.ad.sdk.network.ClickPersistenceHandoff
import ad.simula.ad.sdk.network.ClickPersistencePart
import ad.simula.ad.sdk.network.ClickSources
import ad.simula.ad.sdk.network.AutoRedirectCoordinator
import ad.simula.ad.sdk.network.AutoRedirectResult
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
        assertFalse(canDismissFullscreen(dismissUnlocked = false, clickHandoffPending = false))
        assertFalse(canDismissFullscreen(dismissUnlocked = false, clickHandoffPending = true))
        assertFalse(canDismissFullscreen(dismissUnlocked = true, clickHandoffPending = true))
        assertTrue(canDismissFullscreen(dismissUnlocked = true, clickHandoffPending = false))
    }

    @Test
    fun `display reporting commits before zero-delay dismissal is admitted`() {
        val events = mutableListOf<String>()
        var displayAdmitted = false

        assertFalse(canDismissFullscreen(true, false, displayAdmitted))
        displayAdmitted = admitFullscreenDisplay(
            alreadyReported = false,
            markReported = { events += "marked" },
            notifyDisplayed = { events += "displayed" },
            enqueueShown = { events += "shown" },
        )

        assertEquals(listOf("marked", "displayed", "shown"), events)
        assertTrue(canDismissFullscreen(true, false, displayAdmitted))

        displayAdmitted = admitFullscreenDisplay(
            alreadyReported = true,
            markReported = { events += "duplicate_mark" },
            notifyDisplayed = { events += "duplicate_display" },
            enqueueShown = { events += "duplicate_shown" },
        )
        assertTrue(displayAdmitted)
        assertEquals(listOf("marked", "displayed", "shown"), events)
    }

    @Test
    fun `publisher display callback failure cannot suppress durable shown enqueue`() {
        val events = mutableListOf<String>()

        assertTrue(
            admitFullscreenDisplay(
                alreadyReported = false,
                markReported = { events += "marked" },
                notifyDisplayed = { events += "displayed"; error("publisher failure") },
                enqueueShown = { events += "shown" },
            ),
        )

        assertEquals(listOf("marked", "displayed", "shown"), events)
    }

    @Test
    fun `publisher impression failures cannot suppress paid callback or seen enqueue`() {
        val events = mutableListOf<String>()

        commitFullscreenImpression(
            alreadyReported = false,
            markReported = { events += "marked" },
            notifyImpression = { events += "impression"; error("publisher impression failure") },
            notifyPaid = { events += "paid"; error("publisher paid failure") },
            enqueueSeen = { events += "seen" },
        )
        commitFullscreenImpression(
            alreadyReported = true,
            markReported = { events += "duplicate_mark" },
            notifyImpression = { events += "duplicate_impression" },
            notifyPaid = { events += "duplicate_paid" },
            enqueueSeen = { events += "duplicate_seen" },
        )

        assertEquals(listOf("marked", "impression", "paid", "seen"), events)
    }

    @Test
    fun `dismissal stays blocked until persisted click handoff reaches route`() {
        val gate = ClickInteractionGate(idFactory = { "interaction" })
        val handoff = ClickPersistenceHandoff(
            requireNotNull(gate.claim(ClickSources.INSTALL_BANNER)),
        ) {}

        assertFalse(canDismissFullscreen(dismissUnlocked = true, clickHandoffPending = gate.hasPendingClaim()))
        handoff.complete(ClickPersistencePart.TELEMETRY)
        handoff.complete(ClickPersistencePart.BEACON)
        assertFalse(canDismissFullscreen(dismissUnlocked = true, clickHandoffPending = gate.hasPendingClaim()))

        assertTrue(handoff.handoff { true })
        assertTrue(canDismissFullscreen(dismissUnlocked = true, clickHandoffPending = gate.hasPendingClaim()))
    }

    @Test
    fun `retained routing handoff blocks dismissal and recreation after claim commits`() {
        val gate = ClickInteractionGate(idFactory = { "interaction" })
        val handoff = ClickPersistenceHandoff(
            requireNotNull(gate.claim(ClickSources.PRIMARY_CTA)),
        ) {}
        var retained: ClickPersistenceHandoff? = handoff
        var routeCompletion: ((Boolean) -> Unit)? = null
        handoff.addResultListener { retained = null }

        handoff.complete(ClickPersistencePart.TELEMETRY)
        handoff.complete(ClickPersistencePart.BEACON)
        assertTrue(handoff.handoffAsync { _, completion ->
            routeCompletion = completion
            ClickRouteStart.STARTED
        })

        assertFalse("claim is committed before routing finishes", gate.hasPendingClaim())
        assertFalse(canDismissFullscreen(dismissUnlocked = true, clickHandoffPending = retained != null))
        val recreatedPending = retained != null
        assertFalse("recreation restores the retained handoff", canDismissFullscreen(true, recreatedPending))

        routeCompletion?.invoke(true)

        assertNull(retained)
        assertTrue(canDismissFullscreen(dismissUnlocked = true, clickHandoffPending = retained != null))
    }

    @Test
    fun `primary CTA persists one stable beacon and telemetry before route handoff`() {
        var now = 1_000L
        val gate = ClickInteractionGate(clockMs = { now }, idFactory = { "interaction-1" })
        val scheduler = TestScheduler()
        val order = mutableListOf<String>()
        val beacons = mutableListOf<Pair<String, String>>()
        val claim = requireNotNull(
            notifyPublisherClickForClaim(gate.claim(ClickSources.PRIMARY_CTA)) { order += "publisher_click" },
        )

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
            listOf("publisher_click", "beacon_persisted", "telemetry_persisted"),
            order,
        )
        assertEquals(listOf("interaction-1" to ClickSources.PRIMARY_CTA), beacons)
        assertNull("a duplicate callback cannot create another interaction", gate.claim(ClickSources.PRIMARY_CTA))

        scheduler.runReady()

        assertEquals(listOf("publisher_click", "beacon_persisted", "telemetry_persisted", "route"), order)
        assertEquals(1, beacons.size)
        now = 1_100L
        assertNull("successful route starts the duplicate window", gate.claim(ClickSources.PRIMARY_CTA))
    }

    @Test
    fun `failed primary CTA route notifies admitted tap once without store open`() {
        var nextId = 0
        val gate = ClickInteractionGate(idFactory = { "interaction-${++nextId}" })
        val scheduler = TestScheduler()
        var publisherClicks = 0
        var storeOpens = 0
        val claim = requireNotNull(
            notifyPublisherClickForClaim(gate.claim(ClickSources.PRIMARY_CTA)) { publisherClicks++ },
        )

        coordinateClickPersistence(
            scheduler = scheduler,
            claim = claim,
            enqueueBeacon = { it(BeaconPersistenceOutcome.Persisted) },
            recordTelemetry = { it() },
            onHandoff = {
                val opened = false
                if (opened) storeOpens++
                opened
            },
        )
        scheduler.runReady()

        assertEquals(1, publisherClicks)
        assertEquals(0, storeOpens)
        assertNull(
            "persisted interaction cannot be reissued after route failure",
            notifyPublisherClickForClaim(gate.claim(ClickSources.PRIMARY_CTA)) { publisherClicks++ },
        )
        assertEquals("duplicate callbacks do not notify again", 1, publisherClicks)
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
        val gate = ClickInteractionGate(idFactory = { "interaction" })

        val claim = notifyPublisherClickForClaim(gate.claim(ClickSources.PRIMARY_CTA)) {
            throw IllegalStateException("host callback")
        }
        continued = true

        assertTrue(claim != null)
        assertTrue(continued)
    }

    @Test
    fun `auto redirect opens without publisher click callback`() {
        val redirects = AutoRedirectCoordinator()
        val scope = Any()
        var publisherClicks = 0
        var routeAttempts = 0
        redirects.activate(scope)
        assertNull(notifyPublisherClickForClaim(null) { publisherClicks++ })

        val result = redirects.request(scope, pendingHandoff = null) {
            routeAttempts++
            true
        }

        assertEquals(AutoRedirectResult.OPENED, result)
        assertEquals(1, routeAttempts)
        assertEquals(0, publisherClicks)
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
    fun `fallback CTA requires admitted external route before claiming`() {
        val tracker = "https://tracker.example/click"

        assertEquals(
            CreativeCtaRouter.PrimaryCtaTapPlan.AllowInWebView,
            CreativeCtaRouter.primaryCtaTapPlan(
                tappedUrl = "javascript:openDetails()",
                creativeBaseUrl = "https://creative.example/end-screen",
                trackingUrl = tracker,
                destination = "appstore",
            ),
        )
        listOf("market://details?missing=id", "custom-app://offer").forEach { tapped ->
            assertEquals(
                CreativeCtaRouter.PrimaryCtaTapPlan.ConsumeWithoutClick,
                CreativeCtaRouter.primaryCtaTapPlan(
                    tappedUrl = tapped,
                    creativeBaseUrl = "https://creative.example/end-screen",
                    trackingUrl = tracker,
                    destination = "appstore",
                ),
            )
        }
        assertTrue(
            CreativeCtaRouter.primaryCtaTapPlan(
                tappedUrl = "https://advertiser.example/offer",
                creativeBaseUrl = "https://creative.example/end-screen",
                trackingUrl = tracker,
                destination = "appstore",
            ) is CreativeCtaRouter.PrimaryCtaTapPlan.Route,
        )
    }

    @Test
    fun `fullscreen fallback click beacon belongs to fallback screen`() {
        assertEquals("fallback-ad", fallbackClickBeaconImpressionId("fallback-ad", serverEnabled = true))
        assertNull(fallbackClickBeaconImpressionId("fallback-ad", serverEnabled = false))
        assertNull(fallbackClickBeaconImpressionId("", serverEnabled = true))
    }

    @Test
    fun `unowned fallback beacon completes persistence without enqueue`() {
        val outcomes = mutableListOf<BeaconPersistenceOutcome>()
        val enqueued = mutableListOf<String>()

        enqueueOwnedFallbackClickBeacon(
            adId = "fallback-ad",
            serverEnabled = false,
            completion = outcomes::add,
            enqueue = enqueued::add,
        )
        assertEquals(listOf(BeaconPersistenceOutcome.Persisted), outcomes)
        assertTrue(enqueued.isEmpty())

        enqueueOwnedFallbackClickBeacon(
            adId = "fallback-ad",
            serverEnabled = true,
            completion = outcomes::add,
            enqueue = enqueued::add,
        )
        assertEquals(listOf("fallback-ad"), enqueued)
    }

    @Test
    fun `retained fallback permits only the exact SDK owned navigation`() {
        val state = FallbackPresentationState()
        val fallbackUrl = "https://creative.example/fallback"
        state.showing(0)
        state.setClickPending(true)
        assertEquals(true, state.navigationOverride())
        assertTrue(state.retainNavigation(fallbackUrl))
        state.setClickPending(false)
        state.bindNavigation(Any()) { url ->
            assertEquals(false, state.navigationOverride(url, true, false))
            assertEquals(true, state.navigationOverride("https://advertiser.example", true, false))
            assertEquals(true, state.navigationOverride(url, false, false))
            assertEquals(true, state.navigationOverride(url, true, true))
            true
        }
        assertNull(state.navigationOverride())
    }

    @Test
    fun `fallback navigation permit survives asynchronous WebView callback and is one shot`() {
        val state = FallbackPresentationState()
        val fallbackUrl = "https://creative.example/fallback"
        state.showing(0)
        state.setClickPending(true)
        assertTrue(state.retainNavigation(fallbackUrl))
        state.bindNavigation(Any()) { true }

        state.setClickPending(false)

        assertFalse(state.hasRetainedNavigation())
        assertEquals(false, state.navigationOverride(fallbackUrl, true, false))
        assertNull(state.navigationOverride(fallbackUrl, true, false))
    }

    @Test
    fun `fallback navigation permit requeues for replacement owner and rejects stale callback`() {
        val state = FallbackPresentationState()
        val oldOwner = Any()
        val replacementOwner = Any()
        val fallbackUrl = "https://creative.example/fallback"
        state.showing(0)
        state.setClickPending(true)
        assertTrue(state.retainNavigation(fallbackUrl))
        state.bindNavigation(oldOwner) { true }
        state.setClickPending(false)

        state.unbindNavigation(oldOwner)
        assertTrue(state.hasRetainedNavigation())
        state.bindNavigation(replacementOwner) { true }
        state.onNavigationStarted(fallbackUrl, oldOwner)
        assertEquals(true, state.navigationOverride(fallbackUrl, true, false, oldOwner))
        assertEquals(false, state.navigationOverride(fallbackUrl, true, false, replacementOwner))
        assertNull(state.navigationOverride(fallbackUrl, true, false, replacementOwner))
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
    fun `successful fallback route records terminal navigation before dismissal unblocks`() {
        val state = FallbackPresentationState()
        val scheduler = TestScheduler()
        val gate = ClickInteractionGate(idFactory = { "fallback" })
        var routeCompletion: ((Boolean) -> Unit)? = null
        state.showing(0)

        coordinateDeferredClickPersistence(
            scheduler = scheduler,
            claim = requireNotNull(gate.claim(ClickSources.FALLBACK_CTA)),
            enqueueBeacon = { it(BeaconPersistenceOutcome.Persisted) },
            recordTelemetry = { it() },
            onHandoff = { _, completion ->
                routeCompletion = completion
                ClickRouteStart.STARTED
            },
            onCreated = { state.setClickPending(true) },
            onFinished = { state.setClickPending(false) },
        )
        scheduler.runReady()

        assertEquals(true, state.navigationOverride())
        assertFalse(state.advance(total = 2))

        routeCompletion?.invoke(true)
        assertTrue("terminal UI update is still queued", state.clickHandoffPending)
        scheduler.runReady()

        assertNull(state.navigationOverride())
        assertTrue(state.advance(total = 2))
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
        state.bindNavigation(oldOwner) { calls.add("old:$it") }
        state.bindNavigation(replacement) { calls.add("new:$it") }

        state.unbindNavigation(oldOwner)
        assertTrue(state.retainNavigation("https://tracker.example"))

        assertEquals(listOf("new:https://tracker.example"), calls)
    }

    @Test
    fun `fallback route failure waits for terminal handoff and replacement WebView`() {
        val state = FallbackPresentationState()
        val oldOwner = Any()
        val replacementOwner = Any()
        val calls = mutableListOf<String>()
        state.bindNavigation(oldOwner) { calls.add("old:$it") }
        state.setClickPending(true)

        assertTrue(state.retainNavigation("https://creative.example/fallback"))
        state.unbindNavigation(oldOwner)
        state.setClickPending(false)
        assertTrue(calls.isEmpty())
        assertTrue(state.hasRetainedNavigation())

        state.bindNavigation(replacementOwner) { calls.add("new:$it") }
        assertEquals(listOf("new:https://creative.example/fallback"), calls)
        assertFalse(state.hasRetainedNavigation())
        state.onNavigationStarted("https://creative.example/fallback", replacementOwner)
        state.bindNavigation(Any()) { calls.add("duplicate:$it") }
        assertEquals("fallback delivery is one-shot", 1, calls.size)
    }

    @Test
    fun `stale fallback owner cannot clear replacement and teardown drops pending navigation`() {
        val state = FallbackPresentationState()
        val staleOwner = Any()
        val replacementOwner = Any()
        val calls = mutableListOf<String>()
        state.bindNavigation(staleOwner) { calls.add("stale:$it") }
        state.bindNavigation(replacementOwner) { calls.add("new:$it") }
        state.unbindNavigation(staleOwner)
        state.setClickPending(true)
        assertTrue(state.retainNavigation("https://creative.example/fallback"))
        state.setClickPending(false)

        assertEquals(listOf("new:https://creative.example/fallback"), calls)

        state.setClickPending(true)
        assertTrue(state.retainNavigation("https://creative.example/dropped"))
        state.clear()
        state.setClickPending(false)
        assertEquals(1, calls.size)
        assertFalse(state.hasRetainedNavigation())
    }

    @Test
    fun `close cancels undeliverable fallback navigation and advances`() {
        val state = FallbackPresentationState()
        val failedOwner = Any()
        state.showing(0)
        state.bindNavigation(failedOwner) { false }
        state.setClickPending(true)
        assertTrue(state.retainNavigation("https://creative.example/fallback"))

        state.setClickPending(false)

        assertTrue(state.hasRetainedNavigation())
        assertTrue(state.advance(total = 2))
        assertFalse(state.hasRetainedNavigation())
        assertEquals(1, state.index)
    }

    @Test
    fun `fallback close gate progress survives recreation per screen`() {
        val state = FallbackPresentationState()

        assertEquals(0L, state.closeGateElapsedMs(0))
        assertEquals(4_500L, state.addCloseGateElapsedMs(0, 4_500L))
        assertEquals(4_500L, state.closeGateElapsedMs(0))
        assertEquals(FALLBACK_CLOSE_GATE_MS, state.addCloseGateElapsedMs(0, 1_000L))
        assertEquals(FALLBACK_CLOSE_GATE_MS, state.closeGateElapsedMs(0))
        assertEquals(0L, state.closeGateElapsedMs(1))
    }

    @Test
    fun `fallback automatic navigation is one shot per screen`() {
        val state = FallbackPresentationState()

        assertTrue(state.claimAutomaticNavigation(0))
        assertFalse(state.claimAutomaticNavigation(0))
        assertTrue(state.claimAutomaticNavigation(1))
    }

    @Test
    fun `fallback navigation ownership resets for the next screen`() {
        val state = FallbackPresentationState()
        state.showing(0)
        state.bindNavigation(Any()) { true }
        val firstUrl = "https://creative.example/first"
        assertTrue(state.retainNavigation(firstUrl))
        state.onNavigationStarted(firstUrl)
        assertNull(state.navigationOverride())

        state.showing(1)

        assertNull(state.navigationOverride())
    }

    @Test
    fun `post-close fallback deadline survives recreation and rejects late success`() {
        var now = 1_000L
        val state = FallbackPresentationState(clockMs = { now })
        val generation = state.startPostCloseFetchWait()

        assertEquals(FALLBACK_POST_CLOSE_WAIT_MS, state.postCloseFetchWaitRemainingMs(generation))
        now += 1_250L
        assertEquals(generation, state.retainedPostCloseFetchWait())
        assertEquals(750L, state.postCloseFetchWaitRemainingMs(generation))

        now += 750L
        assertTrue(state.timeoutPostCloseFetchWait(generation))
        assertEquals(FallbackStage.DONE, state.stage)
        assertFalse(
            state.resolvePostCloseFetchWait(
                generation,
                listOf(SimulaApiClient.FallbackAd("late", html = "<html/>")),
            ),
        )
        assertEquals(FallbackStage.DONE, state.stage)
    }

    @Test
    fun `post-close fallback result wins before deadline`() {
        var now = 5_000L
        val state = FallbackPresentationState(clockMs = { now })
        val generation = state.startPostCloseFetchWait()
        val ads = listOf(SimulaApiClient.FallbackAd("fallback", html = "<html/>"))

        now += FALLBACK_POST_CLOSE_WAIT_MS - 1L
        assertTrue(state.resolvePostCloseFetchWait(generation, ads))
        assertEquals(FallbackStage.SHOWING, state.stage)
        assertEquals(0, state.index)
        assertFalse(state.timeoutPostCloseFetchWait(generation))
    }

    @Test
    fun `accepted primary CTA allows a later distinct interaction`() {
        var now = 1_000L
        var nextId = 0
        val gate = ClickInteractionGate(
            clockMs = { now },
            idFactory = { "primary-${++nextId}" },
        )
        val handoff = ClickPersistenceHandoff(
            requireNotNull(gate.claim(ClickSources.PRIMARY_CTA)),
        ) {}

        handoff.complete(ClickPersistencePart.TELEMETRY)
        handoff.complete(ClickPersistencePart.BEACON)
        assertTrue(handoff.handoff { true })

        now += 500L
        assertEquals("primary-2", gate.claim(ClickSources.PRIMARY_CTA)?.interaction?.id)
    }
}
