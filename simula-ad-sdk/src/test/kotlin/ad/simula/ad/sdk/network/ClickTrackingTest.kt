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
            assertFalse(gate.hasPendingClaim())
            routes++
            true
        })
        assertFalse(handoff.handoff { routes++; true })
        assertEquals(1, routes)
        now = 1_100L
        assertNull("successful handoff starts the duplicate window", gate.claim(ClickSources.INSTALL_BANNER))
    }

    @Test
    fun `failed and cancelled persistence handoffs release their presentation claim`() {
        val gate = ClickInteractionGate(idFactory = { "event" })
        val failed = ClickPersistenceHandoff(
            requireNotNull(gate.claim(ClickSources.STORE_PROMPT)),
        ) {}
        failed.complete(ClickPersistencePart.TELEMETRY)
        failed.complete(ClickPersistencePart.BEACON)
        assertFalse(failed.handoff { false })
        val retryAfterFailure = gate.claim(ClickSources.PRIMARY_CTA)
        assertTrue(retryAfterFailure != null)
        assertTrue(retryAfterFailure?.release() == true)

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
            assertFalse(gate.hasPendingClaim())
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
    fun `generated interaction id respects backend length bound`() {
        val gate = ClickInteractionGate(idFactory = { "x".repeat(100) })

        assertEquals(64, gate.admit(ClickSources.PRIMARY_CTA)?.id?.length)
    }
}
