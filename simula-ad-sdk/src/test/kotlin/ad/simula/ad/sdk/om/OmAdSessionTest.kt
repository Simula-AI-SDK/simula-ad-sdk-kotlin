package ad.simula.ad.sdk.om

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the [OmAdSession] lifecycle state machine, exercised through the
 * [OmEventsSink] seam (so no real OMID `AdSession` / Android framework is needed).
 *
 * Pure JVM: verifies the idempotency guards that keep measurement from double-firing
 * or firing after finish — the contract the WebView hooks rely on.
 */
class OmAdSessionTest {

    /** Records every sink call so the test can assert exact counts/order. */
    private class FakeSink : OmEventsSink {
        var loaded = 0
        var impression = 0
        var finished = 0
        override fun loaded() { loaded++ }
        override fun impressionOccurred() { impression++ }
        override fun finish() { finished++ }
    }

    @Test
    fun `loaded and impression each fire at most once`() {
        val sink = FakeSink()
        val session = OmAdSession(sink)

        session.fireLoaded()
        session.fireLoaded()
        session.fireImpression()
        session.fireImpression()
        session.fireImpression()

        assertEquals(1, sink.loaded)
        assertEquals(1, sink.impression)
        assertEquals(0, sink.finished)
    }

    @Test
    fun `finish fires at most once`() {
        val sink = FakeSink()
        val session = OmAdSession(sink)

        session.finish()
        session.finish()

        assertEquals(1, sink.finished)
    }

    @Test
    fun `events after finish are no-ops`() {
        val sink = FakeSink()
        val session = OmAdSession(sink)

        session.finish()
        session.fireLoaded()
        session.fireImpression()

        assertEquals(0, sink.loaded)
        assertEquals(0, sink.impression)
        assertEquals(1, sink.finished)
    }

    @Test
    fun `normal sequence loaded then impression then finish`() {
        val sink = FakeSink()
        val session = OmAdSession(sink)

        session.fireLoaded()
        session.fireImpression()
        session.finish()

        assertEquals(1, sink.loaded)
        assertEquals(1, sink.impression)
        assertEquals(1, sink.finished)
    }

    @Test
    fun `a throwing sink never propagates`() {
        // A measurement failure must never crash the ad: guarded calls swallow throwables.
        val session = OmAdSession(object : OmEventsSink {
            override fun loaded() = throw RuntimeException("boom")
            override fun impressionOccurred() = throw RuntimeException("boom")
            override fun finish() = throw RuntimeException("boom")
        })

        session.fireLoaded()
        session.fireImpression()
        session.finish()
        // Reaching here without an exception is the assertion.
    }
}
