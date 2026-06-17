package ad.simula.ad.sdk.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the telemetry enrichment fields added in "Better Telemetry Tracking": per-event
 * `event_age_ms` (stamped at flush), envelope `connection_type` (resolved at flush), and the new
 * `recordOperation`/`recordLifecycle` fields (failureClass, breadcrumb, trigger, cacheSource).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryEnrichmentTest {

    private class FakeStore : TelemetryStore {
        var data: List<TelemetryEvent> = emptyList()
        override fun load(): List<TelemetryEvent> = data
        override fun save(events: List<TelemetryEvent>) { data = events.toList() }
    }

    private class FakeSender : TelemetrySender {
        val batches = mutableListOf<TelemetryEnvelope>()
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        override suspend fun send(body: String): TelemetryAck {
            batches.add(json.decodeFromString<TelemetryEnvelope>(body))
            return TelemetryAck.ACCEPTED
        }
    }

    private fun build(
        scope: CoroutineScope,
        store: TelemetryStore,
        sender: TelemetrySender,
        clock: () -> Long,
        connectionType: () -> String? = { null },
    ) = TelemetryManager(
        ctx = TelemetryContext(sdkVersion = "9.9", osVersion = "14", deviceModel = "Test", hostAppId = "com.test", devMode = true),
        store = store,
        sender = sender,
        sessionIdProvider = { "sess" },
        primaryUserIdProvider = { null },
        advertisingIdProvider = { null },
        connectionTypeProvider = connectionType,
        enabled = true,
        sampleRate = 1.0,
        clock = clock,
        scope = scope,
        random = { 0.0 },
    )

    @Test
    fun `event_age_ms is stamped at flush time`() = runTest {
        var now = 1_000L
        val sender = FakeSender()
        val m = build(this, FakeStore(), sender, clock = { now })

        m.recordNetwork("/load", "POST", 200, durationMs = 5, requestBytes = 0, responseBytes = 0, failureClass = null)
        now = 5_000L // time passes before the timed flush fires
        advanceUntilIdle()

        val e = sender.batches.flatMap { it.events }.single { it.type == TYPE_NETWORK }
        assertEquals("age = flushClock - timestamp", 4_000L, e.eventAgeMs)
    }

    @Test
    fun `connection_type is resolved onto the envelope at flush`() = runTest {
        val sender = FakeSender()
        val m = build(this, FakeStore(), sender, clock = { 1_000L }, connectionType = { "wifi" })

        m.recordError("api:boom", "boom")
        advanceUntilIdle()

        assertEquals("wifi", sender.batches.first().connectionType)
    }

    @Test
    fun `recordOperation carries failureClass and breadcrumb`() = runTest {
        val sender = FakeSender()
        val m = build(this, FakeStore(), sender, clock = { 1_000L })

        m.recordOperation("session_failed", durationMs = 12, success = false, failureClass = "no_session", breadcrumb = "ctx=true")
        advanceUntilIdle()

        val e = sender.batches.flatMap { it.events }.single { it.name == "session_failed" }
        assertEquals(TYPE_OPERATION, e.type)
        assertEquals(false, e.success)
        assertEquals("no_session", e.failureClass)
        assertEquals("ctx=true", e.breadcrumb)
    }

    @Test
    fun `recordLifecycle carries trigger and cacheSource`() = runTest {
        val sender = FakeSender()
        val m = build(this, FakeStore(), sender, clock = { 1_000L })

        m.recordLifecycle("store_opened", "interstitial", null, "ad1", null, 1500L, null, trigger = "cta", cacheSource = null)
        m.recordLifecycle("load_success", "character_ad", "unit1", "ad2", null, null, null, cacheSource = "preload")
        advanceUntilIdle()

        val events = sender.batches.flatMap { it.events }
        val opened = events.single { it.name == "store_opened" }
        assertEquals(TYPE_LIFECYCLE, opened.type)
        assertEquals("cta", opened.trigger)
        assertEquals(1500L, opened.durationMs)
        assertNull(opened.cacheSource)

        val load = events.single { it.name == "load_success" }
        assertEquals("preload", load.cacheSource)
        assertNull(load.trigger)
    }
}
