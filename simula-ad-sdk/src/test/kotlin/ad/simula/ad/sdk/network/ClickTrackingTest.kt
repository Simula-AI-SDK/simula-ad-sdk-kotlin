package ad.simula.ad.sdk.network

import org.junit.Assert.assertEquals
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
    fun `persistence barrier releases once after both completions`() {
        val releases = mutableListOf<Boolean>()
        val barrier = ClickPersistenceBarrier { releases += it }

        barrier.complete(ClickPersistencePart.TELEMETRY)
        assertTrue(releases.isEmpty())
        barrier.complete(ClickPersistencePart.BEACON)
        barrier.complete(ClickPersistencePart.BEACON)
        barrier.timeout()

        assertEquals(listOf(false), releases)
    }

    @Test
    fun `persistence barrier timeout releases once when a completion is wedged`() {
        val releases = mutableListOf<Boolean>()
        val barrier = ClickPersistenceBarrier { releases += it }

        barrier.complete(ClickPersistencePart.TELEMETRY)
        barrier.timeout()
        barrier.complete(ClickPersistencePart.BEACON)

        assertEquals(listOf(true), releases)
    }

    @Test
    fun `generated interaction id respects backend length bound`() {
        val gate = ClickInteractionGate(idFactory = { "x".repeat(100) })

        assertEquals(64, gate.admit(ClickSources.PRIMARY_CTA)?.id?.length)
    }
}
