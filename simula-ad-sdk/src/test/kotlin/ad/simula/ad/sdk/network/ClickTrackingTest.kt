package ad.simula.ad.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickTrackingTest {
    @Test
    fun `canonical click sources map legacy labels`() {
        assertEquals(ClickSources.PRIMARY_CTA, ClickSources.normalize("cta"))
        assertEquals(ClickSources.STORE_PROMPT, ClickSources.normalize("store_prompt"))
        assertEquals(ClickSources.INSTALL_BANNER, ClickSources.normalize("install_banner"))
        assertEquals(ClickSources.FALLBACK_CTA, ClickSources.normalize("fallback_cta"))
        assertEquals(ClickSources.AUTO_REDIRECT, ClickSources.normalize("auto_store_redirect"))
        assertEquals("cta", ClickSources.storeExitTrigger(ClickSources.PRIMARY_CTA))
        assertEquals("cta", ClickSources.storeExitTrigger("cta"))
        assertEquals(ClickSources.STORE_PROMPT, ClickSources.storeExitTrigger(ClickSources.STORE_PROMPT))
    }

    @Test
    fun `presentation gate drops rapid duplicate dispatch and assigns later interaction a new id`() {
        var now = 1_000L
        var nextId = 0
        val gate = ClickInteractionGate(clockMs = { now }, idFactory = { "event-${++nextId}" })

        val first = gate.admit(ClickSources.STORE_PROMPT)
        now = 1_100L
        val duplicate = gate.admit(ClickSources.STORE_PROMPT)
        now = 1_500L
        val later = gate.admit(ClickSources.STORE_PROMPT)

        assertEquals("event-1", first?.id)
        assertNull(duplicate)
        assertEquals("event-2", later?.id)
        assertNotEquals(first?.id, later?.id)
    }

    @Test
    fun `failed click claim releases admission without starting duplicate window`() {
        var now = 1_000L
        var nextId = 0
        val gate = ClickInteractionGate(clockMs = { now }, idFactory = { "event-${++nextId}" })

        val failed = gate.claim(ClickSources.PRIMARY_CTA)
        assertNull("an in-flight open owns admission", gate.claim(ClickSources.PRIMARY_CTA))
        assertTrue(failed?.release() == true)

        now = 1_100L
        val fallback = gate.claim(ClickSources.PRIMARY_CTA)
        assertEquals("event-2", fallback?.interaction?.id)
        assertTrue(fallback?.commit() == true)
    }

    @Test
    fun `successful click claim commits one interaction and suppresses duplicate callback`() {
        var now = 1_000L
        val gate = ClickInteractionGate(clockMs = { now }, idFactory = { "event" })

        val successful = gate.claim(ClickSources.FALLBACK_CTA)
        assertTrue(successful?.commit() == true)
        assertEquals(ClickSources.FALLBACK_CTA, successful?.interaction?.source)

        now = 1_100L
        assertNull(gate.claim(ClickSources.FALLBACK_CTA))
        assertFalse(successful?.release() == true)
    }

    @Test
    fun `duplicate window begins when a delayed claim commits`() {
        var now = 1_000L
        val gate = ClickInteractionGate(clockMs = { now }, idFactory = { "event-$now" })

        val delayed = gate.claim(ClickSources.PRIMARY_CTA)
        now = 5_000L
        assertTrue(delayed?.commit() == true)

        now = 5_100L
        assertNull("an immediate post-commit duplicate is suppressed", gate.claim(ClickSources.PRIMARY_CTA))
        now = 5_500L
        assertEquals("event-5500", gate.claim(ClickSources.PRIMARY_CTA)?.interaction?.id)
    }

    @Test
    fun `pending persistence blocks cross-surface claim and success commits once at handoff`() {
        var now = 1_000L
        val gate = ClickInteractionGate(clockMs = { now }, idFactory = { "store-click" })
        val claim = gate.claim(ClickSources.STORE_PROMPT)
        val ready = mutableListOf<Boolean>()
        val handoff = ClickPersistenceHandoff(requireNotNull(claim)) { ready += it }

        handoff.complete(ClickPersistencePart.TELEMETRY)
        assertTrue(ready.isEmpty())
        assertNull("primary CTA stays blocked while beacon persistence is pending", gate.claim(ClickSources.PRIMARY_CTA))
        assertNull("fallback CTA shares the presentation gate", gate.claim(ClickSources.FALLBACK_CTA))

        handoff.complete(ClickPersistencePart.BEACON)
        assertEquals(listOf(false), ready)
        assertTrue("durability alone does not commit before the scheduled route", gate.hasPendingClaim())
        var routes = 0
        assertTrue(handoff.handoff {
            assertFalse("persisted interaction commits before routing", gate.hasPendingClaim())
            routes++
            true
        })
        assertFalse(handoff.handoff { routes++; true })
        assertEquals(1, routes)
        now = 1_100L
        assertNull("successful handoff starts the duplicate window", gate.claim(ClickSources.INSTALL_BANNER))
    }

    @Test
    fun `failed persisted handoff remains committed while cancellation releases claim`() {
        var now = 1_000L
        var nextId = 0
        val gate = ClickInteractionGate(
            clockMs = { now },
            idFactory = { "event-${++nextId}" },
        )
        val failed = ClickPersistenceHandoff(
            requireNotNull(gate.claim(ClickSources.STORE_PROMPT)),
        ) {}
        failed.complete(ClickPersistencePart.TELEMETRY)
        failed.complete(ClickPersistencePart.BEACON)
        assertFalse(failed.handoff { false })
        assertNull("a persisted failed route cannot be reissued", gate.claim(ClickSources.PRIMARY_CTA))

        now = 1_500L
        val retryAfterWindow = gate.claim(ClickSources.PRIMARY_CTA)
        assertEquals("event-2", retryAfterWindow?.interaction?.id)
        assertTrue(retryAfterWindow?.release() == true)

        val cancelled = ClickPersistenceHandoff(
            requireNotNull(gate.claim(ClickSources.INSTALL_BANNER)),
        ) {}
        assertTrue(cancelled.cancel())
        assertFalse(cancelled.handoff { true })
        assertTrue("teardown cancellation frees fallback admission", gate.claim(ClickSources.FALLBACK_CTA) != null)
    }

    @Test
    fun `persistence timeout commits and routes exactly once`() {
        val gate = ClickInteractionGate(idFactory = { "event" })
        val ready = mutableListOf<Boolean>()
        val handoff = ClickPersistenceHandoff(
            requireNotNull(gate.claim(ClickSources.STORE_PROMPT)),
        ) { ready += it }
        handoff.complete(ClickPersistencePart.TELEMETRY)

        assertTrue(handoff.timeout())
        assertFalse(handoff.timeout())
        assertEquals(listOf(true), ready)
        assertTrue("timeout makes the handoff ready without committing early", gate.hasPendingClaim())
        var routes = 0
        assertTrue(handoff.handoff {
            assertFalse("persisted interaction commits before routing", gate.hasPendingClaim())
            routes++
            true
        })
        assertFalse(handoff.handoff { routes++; true })
        handoff.complete(ClickPersistencePart.BEACON)
        assertEquals(1, routes)
    }

    @Test
    fun `synchronous native route failure releases claim for immediate retry`() {
        val gate = ClickInteractionGate(idFactory = { "event" })
        var clicks = 0

        val failed = routeClaimedClick(
            claim = gate.claim(ClickSources.PRIMARY_CTA),
            open = { false },
            onOpened = { clicks++ },
        )
        val retried = routeClaimedClick(
            claim = gate.claim(ClickSources.PRIMARY_CTA),
            open = { true },
            onOpened = { clicks++ },
        )

        assertEquals(ClickRouteOutcome.OPEN_FAILED, failed)
        assertEquals(ClickRouteOutcome.OPENED, retried)
        assertEquals(1, clicks)
    }

    @Test
    fun `pending user handoff success suppresses deferred auto redirect`() {
        val gate = ClickInteractionGate(idFactory = { "user" })
        val handoff = ClickPersistenceHandoff(
            requireNotNull(gate.claim(ClickSources.STORE_PROMPT)),
        ) {}
        val coordinator = AutoRedirectCoordinator()
        val scope = Any()
        var autoRoutes = 0
        coordinator.activate(scope)

        assertEquals(
            AutoRedirectResult.DEFERRED,
            coordinator.request(scope, handoff) { autoRoutes++; true },
        )
        handoff.complete(ClickPersistencePart.TELEMETRY)
        handoff.complete(ClickPersistencePart.BEACON)
        assertTrue(handoff.handoff { true })

        assertEquals(0, autoRoutes)
        assertEquals(
            AutoRedirectResult.SUPPRESSED,
            coordinator.request(scope, null) { autoRoutes++; true },
        )
        assertEquals(0, autoRoutes)
    }

    @Test
    fun `user handoff success before trigger still suppresses later auto redirect`() {
        val gate = ClickInteractionGate(idFactory = { "user" })
        val handoff = ClickPersistenceHandoff(
            requireNotNull(gate.claim(ClickSources.STORE_PROMPT)),
        ) {}
        val coordinator = AutoRedirectCoordinator()
        val scope = Any()
        var autoRoutes = 0
        coordinator.observeUserHandoff(handoff)

        handoff.complete(ClickPersistencePart.TELEMETRY)
        handoff.complete(ClickPersistencePart.BEACON)
        assertTrue(handoff.handoff { true })
        coordinator.activate(scope)

        assertEquals(
            AutoRedirectResult.SUPPRESSED,
            coordinator.request(scope, null) { autoRoutes++; true },
        )
        assertEquals(0, autoRoutes)
    }

    @Test
    fun `direct primary CTA success suppresses future presentation auto redirect`() {
        val coordinator = AutoRedirectCoordinator()
        val playableScope = Any()
        val fallbackScope = Any()
        var autoRoutes = 0
        coordinator.activate(playableScope)

        coordinator.recordUserRouteOpened()
        coordinator.deactivate(playableScope)
        coordinator.activate(fallbackScope)

        assertEquals(
            AutoRedirectResult.SUPPRESSED,
            coordinator.request(fallbackScope, null) { autoRoutes++; true },
        )
        assertEquals(0, autoRoutes)
    }

    @Test
    fun `fallback direct CTA success suppresses its end-screen auto redirect`() {
        val coordinator = AutoRedirectCoordinator()
        val firstEndScreen = Any()
        val secondEndScreen = Any()
        var autoRoutes = 0
        coordinator.activate(firstEndScreen)
        coordinator.deactivate(firstEndScreen)
        coordinator.activate(secondEndScreen)

        coordinator.recordUserRouteOpened()

        assertEquals(
            AutoRedirectResult.SUPPRESSED,
            coordinator.request(secondEndScreen, null) { autoRoutes++; true },
        )
        assertEquals(0, autoRoutes)
    }

    @Test
    fun `accounted user handoff suppresses auto redirect when external route fails`() {
        val gate = ClickInteractionGate(idFactory = { "user" })
        val handoff = ClickPersistenceHandoff(
            requireNotNull(gate.claim(ClickSources.INSTALL_BANNER)),
        ) {}
        val coordinator = AutoRedirectCoordinator()
        val scope = Any()
        var autoRoutes = 0
        coordinator.activate(scope)
        coordinator.request(scope, handoff) { autoRoutes++; true }

        handoff.complete(ClickPersistencePart.TELEMETRY)
        handoff.complete(ClickPersistencePart.BEACON)
        assertFalse(handoff.handoff { false })

        assertEquals(0, autoRoutes)
        assertEquals(
            AutoRedirectResult.SUPPRESSED,
            coordinator.request(scope, null) { autoRoutes++; true },
        )
    }

    @Test
    fun `pending user handoff cancellation retries auto redirect while scope is active`() {
        val gate = ClickInteractionGate(idFactory = { "user" })
        val handoff = ClickPersistenceHandoff(
            requireNotNull(gate.claim(ClickSources.STORE_PROMPT)),
        ) {}
        val coordinator = AutoRedirectCoordinator()
        val scope = Any()
        var autoRoutes = 0
        coordinator.activate(scope)
        coordinator.request(scope, handoff) { autoRoutes++; true }

        assertTrue(handoff.cancel())

        assertEquals(1, autoRoutes)
    }

    @Test
    fun `stale screen and disposed presentation suppress deferred callbacks`() {
        val firstGate = ClickInteractionGate(idFactory = { "first" })
        val staleScreenHandoff = ClickPersistenceHandoff(
            requireNotNull(firstGate.claim(ClickSources.STORE_PROMPT)),
        ) {}
        val coordinator = AutoRedirectCoordinator()
        val firstScreen = Any()
        val secondScreen = Any()
        var autoRoutes = 0
        coordinator.activate(firstScreen)
        coordinator.request(firstScreen, staleScreenHandoff) { autoRoutes++; true }

        coordinator.activate(secondScreen)
        staleScreenHandoff.complete(ClickPersistencePart.TELEMETRY)
        staleScreenHandoff.complete(ClickPersistencePart.BEACON)
        assertFalse(staleScreenHandoff.handoff { false })
        assertEquals(0, autoRoutes)
        assertEquals(
            AutoRedirectResult.STALE,
            coordinator.request(firstScreen, null) { autoRoutes++; true },
        )

        val secondGate = ClickInteractionGate(idFactory = { "second" })
        val stalePresentationHandoff = ClickPersistenceHandoff(
            requireNotNull(secondGate.claim(ClickSources.STORE_PROMPT)),
        ) {}
        coordinator.request(secondScreen, stalePresentationHandoff) { autoRoutes++; true }
        coordinator.dispose()
        assertTrue(stalePresentationHandoff.cancel())
        assertEquals(0, autoRoutes)
        assertEquals(
            AutoRedirectResult.STALE,
            coordinator.request(secondScreen, null) { autoRoutes++; true },
        )
    }

    @Test
    fun `direct auto redirect failure is one best-effort attempt and a new trigger may retry`() {
        val coordinator = AutoRedirectCoordinator()
        val scope = Any()
        var attempts = 0
        coordinator.activate(scope)

        assertEquals(
            AutoRedirectResult.FAILED,
            coordinator.request(scope, null) { attempts++; false },
        )
        assertEquals("failure does not schedule an automatic retry", 1, attempts)
        assertEquals(
            AutoRedirectResult.OPENED,
            coordinator.request(scope, null) { attempts++; true },
        )
        assertEquals(
            AutoRedirectResult.SUPPRESSED,
            coordinator.request(scope, null) { attempts++; true },
        )
        assertEquals(2, attempts)
    }

    @Test
    fun `duplicate deferred requests stay suppressed after accounted click`() {
        val gate = ClickInteractionGate(idFactory = { "user" })
        val handoff = ClickPersistenceHandoff(
            requireNotNull(gate.claim(ClickSources.STORE_PROMPT)),
        ) {}
        val coordinator = AutoRedirectCoordinator()
        val scope = Any()
        var autoRoutes = 0
        coordinator.activate(scope)

        assertEquals(
            AutoRedirectResult.DEFERRED,
            coordinator.request(scope, handoff) { autoRoutes++; true },
        )
        assertEquals(
            AutoRedirectResult.DEFERRED,
            coordinator.request(scope, handoff) { autoRoutes++; true },
        )
        handoff.complete(ClickPersistencePart.TELEMETRY)
        handoff.complete(ClickPersistencePart.BEACON)
        assertFalse(handoff.handoff { false })
        assertFalse(handoff.handoff { false })

        assertEquals(0, autoRoutes)
    }

    @Test
    fun `generated interaction id respects backend length bound`() {
        val gate = ClickInteractionGate(idFactory = { "x".repeat(100) })

        assertEquals(64, gate.admit(ClickSources.PRIMARY_CTA)?.id?.length)
    }

    @Test
    fun `primary CTA document admission disables permanently exactly once`() {
        val admission = PrimaryCtaDocumentAdmission()

        assertTrue(admission.isEnabled())
        assertTrue(admission.disable())
        assertFalse(admission.isEnabled())
        assertFalse("disable is one-shot", admission.disable())
        assertFalse("disabled document chain never re-enables", admission.isEnabled())
    }

    @Test
    fun `primary CTA route preserves tapped fallback separately from external target`() {
        val route = PrimaryCtaRoute(
            tappedUrl = "https://creative.example/original",
            externalTarget = "https://tracker.example/click",
        )

        assertEquals("https://creative.example/original", route.tappedUrl)
        assertEquals("https://tracker.example/click", route.externalTarget)
    }

    @Test
    fun `route requested while paused defers until current host resumes exactly once`() {
        val routes = ResumedPresentationRoute<Any>()
        val host = Any()
        var executions = 0
        val outcomes = mutableListOf<Boolean>()
        routes.attach(host)

        assertEquals(PresentationRouteResult.DEFERRED, routes.request({ executions++; true }, outcomes::add))
        assertEquals(PresentationRouteResult.REJECTED, routes.request({ executions += 100; true }, outcomes::add))
        assertEquals(0, executions)
        assertTrue(outcomes.isEmpty())
        routes.resume(host)
        routes.resume(host)

        assertEquals(1, executions)
        assertEquals(listOf(true), outcomes)
    }

    @Test
    fun `deferred route rebinds to replacement host`() {
        val routes = ResumedPresentationRoute<Any>()
        val oldHost = Any()
        val replacement = Any()
        var executedWith: Any? = null
        routes.attach(oldHost)
        assertEquals(PresentationRouteResult.DEFERRED, routes.request({ executedWith = it; true }) {})

        routes.detach(oldHost)
        routes.attach(replacement)
        routes.resume(oldHost)
        assertNull(executedWith)
        routes.resume(replacement)

        assertTrue(executedWith === replacement)
    }

    @Test
    fun `paused resumed host defers new route until next resume`() {
        val routes = ResumedPresentationRoute<Any>()
        val host = Any()
        var executions = 0
        var outcome: Boolean? = null
        routes.attach(host)
        routes.resume(host)
        routes.pause(host)

        assertEquals(PresentationRouteResult.DEFERRED, routes.request({ executions++; false }) { outcome = it })
        assertEquals(0, executions)
        routes.resume(host)

        assertEquals(1, executions)
        assertEquals(false, outcome)
    }

    @Test
    fun `cancel drops pending route and rejects later work`() {
        val routes = ResumedPresentationRoute<Any>()
        val host = Any()
        var executions = 0
        routes.attach(host)
        assertEquals(PresentationRouteResult.DEFERRED, routes.request({ executions++; true }) {})

        routes.cancel()
        routes.resume(host)
        assertEquals(0, executions)
        assertEquals(PresentationRouteResult.REJECTED, routes.request({ executions++; true }) {})
        assertEquals(0, executions)
    }
}
