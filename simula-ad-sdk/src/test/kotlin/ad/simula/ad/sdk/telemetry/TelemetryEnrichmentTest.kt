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
        diagnostics: () -> String? = { null },
    ) = TelemetryManager(
        ctx = TelemetryContext(sdkVersion = "9.9", osVersion = "14", deviceModel = "Test", hostAppId = "com.test", devMode = true),
        store = store,
        sender = sender,
        sessionIdProvider = { "sess" },
        primaryUserIdProvider = { null },
        advertisingIdProvider = { null },
        connectionTypeProvider = connectionType,
        diagnosticsProvider = diagnostics,
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

    @Test
    fun `time_to_first_ad is emitted once on the first load_success`() = runTest {
        var now = 1_000L
        val sender = FakeSender()
        val m = build(this, FakeStore(), sender, clock = { now })

        now = 1_250L
        m.recordLifecycle("load_success", "interstitial", "u", "a1", null, 10L, null, cacheSource = "network")
        m.recordLifecycle("load_success", "interstitial", "u", "a2", null, 10L, null, cacheSource = "network")
        advanceUntilIdle()

        val ttfa = sender.batches.flatMap { it.events }.filter { it.name == "time_to_first_ad" }
        assertEquals("emitted exactly once", 1, ttfa.size)
        assertEquals(TYPE_OPERATION, ttfa.first().type)
        assertEquals(250L, ttfa.first().durationMs) // 1250 - createdAt(1000)
    }

    @Test
    fun `funnel_summary aggregates per format and emits on flush`() = runTest {
        val sender = FakeSender()
        val m = build(this, FakeStore(), sender, clock = { 1_000L })

        m.recordLifecycle("load_success", "interstitial", "u", "a1", null, 1L, null, cacheSource = "network")
        m.recordLifecycle("load_success", "interstitial", "u", "a2", null, 1L, null, cacheSource = "cache") // re-render: not counted
        m.recordLifecycle("load_fail", "interstitial", "u", null, null, null, "no_fill")
        m.recordLifecycle("displayed", "interstitial", "u", "a1", null, null, null)
        m.recordLifecycle("click", "interstitial", "u", "a1", null, null, null)
        m.flushNow()
        advanceUntilIdle()

        val summary = sender.batches.flatMap { it.events }.single { it.name == "funnel_summary" }
        assertEquals(TYPE_OPERATION, summary.type)
        // filled=1 (cache excluded), nofill=1, fail=0, req=2, imp=1, clk=1
        assertEquals("fmt=interstitial;req=2;fill=1;nofill=1;fail=0;imp=1;clk=1", summary.breadcrumb)
    }

    @Test
    fun `diagnostics sample is emitted on flush`() = runTest {
        val sender = FakeSender()
        val m = build(this, FakeStore(), sender, clock = { 1_000L }, diagnostics = { "mem_used_mb=42;wv_pool=1" })

        m.recordError("api:boom", "boom") // seed a buffer so flush has content
        m.flushNow()
        advanceUntilIdle()

        val diag = sender.batches.flatMap { it.events }.single { it.name == "diagnostics" }
        assertEquals("mem_used_mb=42;wv_pool=1", diag.breadcrumb)
    }

    @Test
    fun `experiment id and variant are attached to the envelope after setExperiment`() = runTest {
        val sender = FakeSender()
        val m = build(this, FakeStore(), sender, clock = { 1_000L })

        m.setExperiment("exp_7", "variant_b")
        m.recordError("api:boom", "boom")
        advanceUntilIdle()

        val env = sender.batches.first()
        assertEquals("exp_7", env.experimentId)
        assertEquals("variant_b", env.variantId)
    }
}
