package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.ads.AutomaticNavigationGate
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
    fun `async auto redirect waits for actual route completion`() {
        val coordinator = AutoRedirectCoordinator()
        val scope = Any()
        var completion: ((Boolean) -> Unit)? = null
        coordinator.activate(scope)

        assertEquals(
            AutoRedirectResult.DEFERRED,
            coordinator.requestAsync(scope, null) { _, routeCompletion, _ -> completion = routeCompletion },
        )
        assertEquals(
            AutoRedirectResult.DEFERRED,
            coordinator.requestAsync(scope, null) { _, _, _ -> error("must not start twice") },
        )

        completion?.invoke(true)

        assertEquals(
            AutoRedirectResult.SUPPRESSED,
            coordinator.request(scope, null) { true },
        )
    }

    @Test
    fun `overlapping one shot auto route runs after active route fails`() {
        val coordinator = AutoRedirectCoordinator()
        val scope = Any()
        val completions = mutableListOf<(Boolean) -> Unit>()
        val attempts = mutableListOf<String>()
        coordinator.activate(scope)

        coordinator.requestAsync(scope, null) { _, completion, _ ->
            attempts += "first"
            completions += completion
        }
        assertEquals(
            AutoRedirectResult.DEFERRED,
            coordinator.requestAsync(scope, null) { _, completion, _ ->
                attempts += "second"
                completions += completion
            },
        )
        assertEquals(listOf("first"), attempts)

        completions.first().invoke(false)

        assertEquals(listOf("first", "second"), attempts)
        completions.last().invoke(true)
        assertEquals(AutoRedirectResult.SUPPRESSED, coordinator.request(scope, null) { true })
    }

    @Test
    fun `stale async auto redirect cannot suppress replacement scope`() {
        val coordinator = AutoRedirectCoordinator()
        val first = Any()
        val second = Any()
        var staleCompletion: ((Boolean) -> Unit)? = null
        coordinator.activate(first)
        coordinator.requestAsync(first, null) { _, routeCompletion, _ -> staleCompletion = routeCompletion }

        coordinator.deactivate(first)
        coordinator.activate(second)
        staleCompletion?.invoke(true)

        assertEquals(
            AutoRedirectResult.OPENED,
            coordinator.request(second, null) { true },
        )
    }

    @Test
    fun `user handoff preempts in flight auto route and retries only after cancellation`() {
        val coordinator = AutoRedirectCoordinator()
        val scope = Any()
        val completions = mutableListOf<(Boolean) -> Unit>()
        val canOpen = mutableListOf<() -> Boolean>()
        val handoff = testHandoff("user")
        coordinator.activate(scope)
        coordinator.requestAsync(scope, null) { routeCanOpen, routeCompletion, _ ->
            canOpen += routeCanOpen
            completions += routeCompletion
        }

        coordinator.observeUserHandoff(handoff)

        assertFalse(coordinator.isActive(scope))
        assertFalse(canOpen.single().invoke())
        completions.single().invoke(true)
        assertEquals(1, completions.size)

        handoff.cancel()

        assertEquals(2, completions.size)
        assertTrue(canOpen.last().invoke())
        completions.last().invoke(true)
        assertEquals(AutoRedirectResult.SUPPRESSED, coordinator.request(scope, null) { true })
    }

    @Test
    fun `user handoff cancels in flight auto route work before retry`() {
        val coordinator = AutoRedirectCoordinator()
        val automaticGate = AutomaticNavigationGate().apply {
            retain("https://tracker.example/click", trackerAlreadyRequested = false)
        }
        val scope = Any()
        val handoff = testHandoff("user")
        var cancellations = 0
        var starts = 0
        coordinator.activate(scope)
        coordinator.requestAsync(scope, null) { _, _, registerCancellation ->
            assertTrue(automaticGate.beginPending() != null)
            starts++
            registerCancellation {
                cancellations++
                automaticGate.abandonInFlight()
            }
        }

        coordinator.observeUserHandoff(handoff)

        assertEquals(1, cancellations)
        assertEquals(1, starts)

        handoff.cancel()

        assertEquals(2, starts)
        assertEquals(1, cancellations)
    }

    @Test
    fun `failed async user route retries retained auto route`() {
        val handoff = testHandoff("user")
        val coordinator = AutoRedirectCoordinator()
        val scope = Any()
        var autoRoutes = 0
        coordinator.activate(scope)
        coordinator.request(scope, handoff) { autoRoutes++; true }
        handoff.complete(ClickPersistencePart.TELEMETRY)
        handoff.complete(ClickPersistencePart.BEACON)

        assertTrue(handoff.handoffAsync { _, completion ->
            completion(false)
            ClickRouteStart.STARTED
        })

        assertEquals(1, autoRoutes)
        assertEquals(AutoRedirectResult.SUPPRESSED, coordinator.request(scope, null) { true })
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
    fun `terminal primary route delegates to normal policy without granting navigation`() {
        val state = RetainedPrimaryCtaNavigationState<Any>()
        val handoff = testHandoff("pending")

        assertNull(state.navigationOverride())
        state.onHandoffCreated(handoff)
        assertEquals(true, state.navigationOverride())

        state.onHandoffFinished(handoff)
        assertNull(state.navigationOverride())
    }

    @Test
    fun `successful external route permanently locks creative navigation across frames`() {
        val state = RetainedPrimaryCtaNavigationState<Any>()
        val activity = Any()
        val handoff = testHandoff("opened")
        state.attachActivity(activity)
        state.onHandoffCreated(handoff)

        state.lockAfterExternalOpen()
        state.onHandoffFinished(handoff)

        assertEquals(
            true,
            state.navigationOverride(
                "https://tracker.example/click?dynamic=per-click",
                isMainFrame = true,
                hasGesture = false,
            ),
        )
        assertEquals(
            true,
            state.navigationOverride(
                "https://play.google.com/store/apps/details?id=example",
                isMainFrame = false,
                hasGesture = true,
            ),
        )
        state.detachActivity(activity)
        val replacementActivity = Any()
        state.attachActivity(replacementActivity)
        assertEquals(true, state.navigationOverride())
        assertFalse(state.retainFallback("https://creative.example/late", replacementActivity))
    }

    @Test
    fun `successful retry invalidates an older failed route fallback permit`() {
        val state = RetainedPrimaryCtaNavigationState<Any>()
        val activity = Any()
        val owner = Any()
        val fallbackUrl = "https://creative.example/failed-route"
        val handoff = testHandoff("failed")
        state.attachActivity(activity)
        state.onHandoffCreated(handoff)
        assertTrue(state.retainFallback(fallbackUrl, activity))
        assertTrue(state.bindNavigation(owner, activity) { true })
        state.onHandoffFinished(handoff)

        state.lockAfterExternalOpen()

        assertFalse(state.hasRetainedFallback())
        assertEquals(true, state.navigationOverride(fallbackUrl, true, false, owner))
    }

    @Test
    fun `failed primary route waits for replacement owner and drains exactly once`() {
        val state = RetainedPrimaryCtaNavigationState<Any>()
        val oldActivity = Any()
        val replacementActivity = Any()
        val handoff = testHandoff("route")
        val calls = mutableListOf<String>()
        state.attachActivity(oldActivity)
        state.onHandoffCreated(handoff)

        assertTrue(state.retainFallback("https://creative.example/fallback", oldActivity))
        state.detachActivity(oldActivity)
        state.attachActivity(replacementActivity)
        state.onHandoffFinished(handoff)
        assertTrue(calls.isEmpty())
        assertTrue(state.hasRetainedFallback())

        assertTrue(state.bindNavigation(Any(), replacementActivity) { url ->
            assertEquals(false, state.navigationOverride(url, isMainFrame = true, hasGesture = false))
            assertEquals(true, state.navigationOverride("https://advertiser.example", true, false))
            assertEquals(true, state.navigationOverride(url, true, true))
            calls.add(url)
        })
        assertEquals(listOf("https://creative.example/fallback"), calls)
        assertFalse(state.hasRetainedFallback())
        assertNull(state.navigationOverride())
        assertTrue(state.bindNavigation(Any(), replacementActivity, calls::add))
        assertEquals("fallback delivery is one-shot", 1, calls.size)
    }

    @Test
    fun `renderer-dead primary owner never receives failed route fallback`() {
        val state = RetainedPrimaryCtaNavigationState<Any>()
        val activity = Any()
        val deadOwner = Any()
        val replacementOwner = Any()
        val handoff = testHandoff("renderer-gone")
        val calls = mutableListOf<String>()
        state.attachActivity(activity)
        state.bindNavigation(deadOwner, activity) { calls.add("dead:$it") }
        state.onHandoffCreated(handoff)

        state.unbindNavigation(deadOwner)
        assertTrue(state.retainFallback("https://creative.example/fallback", activity))
        state.onHandoffFinished(handoff)

        assertTrue(calls.isEmpty())
        assertTrue(state.hasRetainedFallback())
        state.bindNavigation(replacementOwner, activity) { calls.add("replacement:$it") }
        assertEquals(listOf("replacement:https://creative.example/fallback"), calls)
    }

    @Test
    fun `stale primary owner cannot clear replacement binding`() {
        val state = RetainedPrimaryCtaNavigationState<Any>()
        val activity = Any()
        val staleOwner = Any()
        val replacementOwner = Any()
        val calls = mutableListOf<String>()
        val handoff = testHandoff("stale")
        state.attachActivity(activity)
        assertTrue(state.bindNavigation(staleOwner, activity) { calls.add("stale:$it") })
        assertTrue(state.bindNavigation(replacementOwner, activity) { calls.add("new:$it") })

        state.unbindNavigation(staleOwner)
        state.onHandoffCreated(handoff)
        assertTrue(state.retainFallback("https://creative.example/fallback", activity))
        state.onHandoffFinished(handoff)

        assertEquals(listOf("new:https://creative.example/fallback"), calls)
    }

    @Test
    fun `failed primary fallback delivery remains retained for replacement owner`() {
        val state = RetainedPrimaryCtaNavigationState<Any>()
        val activity = Any()
        val failedOwner = Any()
        val replacementOwner = Any()
        val handoff = testHandoff("retry")
        val calls = mutableListOf<String>()
        state.attachActivity(activity)
        assertTrue(state.bindNavigation(failedOwner, activity) { false })
        state.onHandoffCreated(handoff)
        assertTrue(state.retainFallback("https://creative.example/fallback", activity))

        state.onHandoffFinished(handoff)

        assertTrue(state.hasRetainedFallback())
        assertEquals(true, state.navigationOverride())
        state.unbindNavigation(failedOwner)
        assertTrue(state.bindNavigation(replacementOwner, activity) { url ->
            assertEquals(false, state.navigationOverride(url, true, false))
            assertEquals(true, state.navigationOverride("https://advertiser.example", true, false))
            calls.add(url)
        })
        assertEquals(listOf("https://creative.example/fallback"), calls)
        assertFalse(state.hasRetainedFallback())
        assertNull(state.navigationOverride())
    }

    @Test
    fun `primary navigation permit survives asynchronous WebView callback and is one shot`() {
        val state = RetainedPrimaryCtaNavigationState<Any>()
        val activity = Any()
        val handoff = testHandoff("async")
        val fallbackUrl = "https://creative.example/fallback"
        state.attachActivity(activity)
        state.onHandoffCreated(handoff)
        assertTrue(state.retainFallback(fallbackUrl, activity))
        assertTrue(state.bindNavigation(Any(), activity) { true })

        state.onHandoffFinished(handoff)

        assertFalse(state.hasRetainedFallback())
        assertEquals(false, state.navigationOverride(fallbackUrl, true, false))
        assertNull(state.navigationOverride(fallbackUrl, true, false))
    }

    @Test
    fun `primary navigation permit requeues for replacement owner and rejects stale callback`() {
        val state = RetainedPrimaryCtaNavigationState<Any>()
        val activity = Any()
        val oldOwner = Any()
        val replacementOwner = Any()
        val handoff = testHandoff("replacement")
        val fallbackUrl = "https://creative.example/fallback"
        state.attachActivity(activity)
        state.onHandoffCreated(handoff)
        assertTrue(state.retainFallback(fallbackUrl, activity))
        assertTrue(state.bindNavigation(oldOwner, activity) { true })
        state.onHandoffFinished(handoff)

        state.unbindNavigation(oldOwner)
        assertTrue(state.hasRetainedFallback())
        assertTrue(state.bindNavigation(replacementOwner, activity) { true })
        state.onNavigationStarted(fallbackUrl, oldOwner)
        assertEquals(true, state.navigationOverride(fallbackUrl, true, false, oldOwner))
        assertEquals(false, state.navigationOverride(fallbackUrl, true, false, replacementOwner))
        assertNull(state.navigationOverride(fallbackUrl, true, false, replacementOwner))
    }

    @Test
    fun `primary fallback rejects stale Activity and teardown clears retained work`() {
        val state = RetainedPrimaryCtaNavigationState<Any>()
        val current = Any()
        val stale = Any()
        val handoff = testHandoff("clear")
        val calls = mutableListOf<String>()
        state.attachActivity(current)
        state.onHandoffCreated(handoff)

        assertFalse(state.retainFallback("https://creative.example/stale", stale))
        assertTrue(state.retainFallback("https://creative.example/current", current))
        state.clear()
        assertFalse(state.hasRetainedFallback())
        assertFalse(state.bindNavigation(Any(), current, calls::add))
        state.onHandoffFinished(handoff)
        assertTrue(calls.isEmpty())
        assertEquals(true, state.navigationOverride())
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

    private fun testHandoff(id: String): ClickPersistenceHandoff = ClickPersistenceHandoff(
        requireNotNull(ClickInteractionGate(idFactory = { id }).claim(ClickSources.PRIMARY_CTA)),
    ) {}
}
