package ad.simula.ad.sdk.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
        var saveCount = 0
        override fun load(): List<TelemetryEvent> = data
        override fun save(events: List<TelemetryEvent>): Boolean {
            saveCount++
            data = events.toList()
            return true
        }
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
        battery: () -> BatteryInfo? = { null },
        carrier: () -> CarrierInfo? = { null },
        ctx: TelemetryContext = TelemetryContext(sdkVersion = "9.9", osVersion = "14", deviceModel = "Test", hostAppId = "com.test", devMode = true),
    ) = TelemetryManager(
        ctx = ctx,
        store = store,
        sender = sender,
        envelopeIdentityProvider = { TelemetryEnvelopeIdentity("sess", null, null) },
        connectionTypeProvider = connectionType,
        diagnosticsProvider = diagnostics,
        batteryProvider = battery,
        carrierProvider = carrier,
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
    fun `recordOperation carries clamped time since init`() = runTest {
        val sender = FakeSender()
        val m = build(this, FakeStore(), sender, clock = { 1_000L })

        m.recordOperation(
            "webview_acquire_warm",
            durationMs = 2,
            success = true,
            timeSinceInitMs = -5L,
        )
        advanceUntilIdle()

        val event = sender.batches.flatMap { it.events }.single { it.name == "webview_acquire_warm" }
        assertEquals(0L, event.timeSinceInitMs)
    }

    @Test
    fun `meta counts aggregate into one bounded event`() = runTest {
        val sender = FakeSender()
        val m = build(this, FakeStore(), sender, clock = { 1_000L })

        m.recordMetaCount(DUPLICATE_INITIALIZE_META_NAME, 2)
        m.recordMetaCount(DUPLICATE_INITIALIZE_META_NAME, 3)
        advanceUntilIdle()

        val events = sender.batches.flatMap { it.events }
            .filter { it.type == TYPE_META && it.name == DUPLICATE_INITIALIZE_META_NAME }
        assertEquals(1, events.size)
        assertEquals(5, events.single().count)
    }

    @Test
    fun `meta count added during an active send is drained without loss`() = runTest {
        val sender = object : TelemetrySender {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val events = mutableListOf<TelemetryEvent>()
            val decoder = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            var calls = 0
            override suspend fun send(body: String): TelemetryAck {
                calls++
                if (calls == 1) gate.await()
                events += decoder.decodeFromString<TelemetryEnvelope>(body).events
                return TelemetryAck.ACCEPTED
            }
        }
        val m = build(this, FakeStore(), sender, clock = { 1_000L })

        m.recordMetaCount(DUPLICATE_INITIALIZE_META_NAME, 2)
        advanceTimeBy(30_000L)
        runCurrent()
        m.recordMetaCount(DUPLICATE_INITIALIZE_META_NAME, 3)
        runCurrent()
        sender.gate.complete(Unit)
        advanceUntilIdle()

        val count = sender.events
            .filter { it.type == TYPE_META && it.name == DUPLICATE_INITIALIZE_META_NAME }
            .sumOf { it.count ?: 0 }
        val ids = sender.events
            .filter { it.type == TYPE_META && it.name == DUPLICATE_INITIALIZE_META_NAME }
            .map { it.eventId }
        assertEquals(5, count)
        assertEquals(2, ids.size)
        assertEquals("accepted aggregate and surviving remainder need different ids", 2, ids.toSet().size)
    }

    @Test
    fun `durable meta callback follows aggregate persistence`() = runTest {
        val order = mutableListOf<String>()
        val store = object : TelemetryStore {
            var data: List<TelemetryEvent> = emptyList()
            override fun load(): List<TelemetryEvent> = data
            override fun save(events: List<TelemetryEvent>): Boolean {
                data = events.toList()
                order += "saved"
                return true
            }
        }
        val m = build(this, store, FakeSender(), clock = { 1_000L })

        m.recordMetaCountDurably(DUPLICATE_INITIALIZE_META_NAME, 4) { persisted ->
            order += "callback:$persisted"
        }
        runCurrent()

        assertEquals(listOf("saved", "callback:Persisted"), order)
        assertEquals(4, store.data.single { it.name == DUPLICATE_INITIALIZE_META_NAME }.count)
    }

    @Test
    fun `failed durable meta save rejects and rolls back aggregate`() = runTest {
        val store = object : TelemetryStore {
            var allowSave = false
            var data: List<TelemetryEvent> = emptyList()
            override fun load(): List<TelemetryEvent> = emptyList()
            override fun save(events: List<TelemetryEvent>): Boolean {
                if (!allowSave) return false
                data = events.toList()
                return true
            }
        }
        var accepted: TelemetryPersistenceOutcome? = null
        val m = build(this, store, FakeSender(), clock = { 1_000L })

        m.recordMetaCountDurably(DUPLICATE_INITIALIZE_META_NAME, 4) { accepted = it }
        runCurrent()
        assertEquals(TelemetryPersistenceOutcome.RetryableFailure, accepted)

        store.allowSave = true
        m.recordMetaCountDurably(DUPLICATE_INITIALIZE_META_NAME, 4) { accepted = it }
        runCurrent()

        assertEquals(TelemetryPersistenceOutcome.Persisted, accepted)
        assertEquals(4, store.data.single { it.name == DUPLICATE_INITIALIZE_META_NAME }.count)
    }

    @Test
    fun `server-disabled manager rejects durable meta without touching storage`() = runTest {
        val store = FakeStore()
        val m = build(this, store, FakeSender(), clock = { 1_000L })
        var outcome: TelemetryPersistenceOutcome? = null
        m.applyServerConfig(enabled = false, sampleRate = 1.0)

        m.recordMetaCountDurably(DUPLICATE_INITIALIZE_META_NAME, 1) { outcome = it }
        runCurrent()

        assertEquals(TelemetryPersistenceOutcome.Disabled, outcome)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun `recordLifecycle carries click identity source trigger and cacheSource`() = runTest {
        val sender = FakeSender()
        val m = build(this, FakeStore(), sender, clock = { 1_000L })
        m.applyServerConfig(enabled = true, sampleRate = 0.25)

        m.recordLifecycle(
            "store_opened", "interstitial", null, "ad1", "ad1", 1500L, null,
            trigger = "cta",
            cacheSource = null,
            interactionId = "interaction-1",
            clickSource = "primary_cta",
        )
        m.recordLifecycle("load_success", "character_ad", "unit1", "ad2", null, null, null, cacheSource = "preload")
        advanceUntilIdle()

        val events = sender.batches.flatMap { it.events }
        val opened = events.single { it.name == "store_opened" }
        assertEquals(TYPE_LIFECYCLE, opened.type)
        assertEquals("cta", opened.trigger)
        assertEquals("interaction-1", opened.interactionId)
        assertEquals("primary_cta", opened.clickSource)
        assertEquals("ad1", opened.serveId)
        assertEquals(0.25, opened.sampleRate ?: -1.0, 0.0)
        assertEquals(1500L, opened.durationMs)
        assertNull(opened.cacheSource)
        assertEquals(0.25, sender.batches.first().sampleRate ?: -1.0, 0.0)

        val load = events.single { it.name == "load_success" }
        assertEquals("preload", load.cacheSource)
        assertNull(load.trigger)
    }

    @Test
    fun `events retain admission sample rate across server config epochs`() = runTest {
        val sender = FakeSender()
        val m = build(this, FakeStore(), sender, clock = { 1_000L })

        m.recordOperation("before_config", 1L, success = true)
        m.applyServerConfig(enabled = true, sampleRate = 0.25)
        m.recordOperation("after_config", 1L, success = true)
        m.flushNow()
        advanceUntilIdle()

        val batch = sender.batches.first { envelope ->
            envelope.events.any { it.name == "before_config" }
        }
        assertEquals(1.0, batch.events.single { it.name == "before_config" }.sampleRate ?: -1.0, 0.0)
        assertEquals(0.25, batch.events.single { it.name == "after_config" }.sampleRate ?: -1.0, 0.0)
        assertNull("mixed admission epochs have no accurate envelope rate", batch.sampleRate)
    }

    @Test
    fun `critical lifecycle persists before releasing handoff callback`() = runTest {
        val store = FakeStore()
        val sender = FakeSender()
        val m = build(this, store, sender, clock = { 1_000L })
        var persistedAtHandoff = false

        m.recordLifecycle(
            stage = "click",
            adFormat = "interstitial",
            adUnitId = null,
            adId = "serve-1",
            serveId = "serve-1",
            durationMs = null,
            errorCode = null,
            interactionId = "interaction-1",
            clickSource = "store_prompt",
            critical = true,
            onPersisted = {
                persistedAtHandoff = store.data.any { it.interactionId == "interaction-1" }
            },
        )
        advanceUntilIdle()

        assertEquals(true, persistedAtHandoff)
        assertEquals("interaction-1", sender.batches.flatMap { it.events }.single().interactionId)
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

    @Test
    fun `device diagnostics are attached to the envelope`() = runTest {
        val sender = FakeSender()
        val ctx = TelemetryContext(
            sdkVersion = "9.9", osVersion = "14", deviceModel = "Test", hostAppId = "com.test",
            devMode = true, manufacturer = "Samsung", locale = "en-US", deviceRamMb = 8192, buildType = "release",
        )
        val m = build(
            this, FakeStore(), sender, clock = { 1_000L },
            battery = { BatteryInfo(level = 0.5f, charging = true) },
            carrier = { CarrierInfo(carrier = "Verizon", radio = "5G") },
            ctx = ctx,
        )

        m.recordError("api:boom", "boom")
        advanceUntilIdle()

        val env = sender.batches.first()
        assertEquals("Samsung", env.manufacturer)
        assertEquals("en-US", env.locale)
        assertEquals(8192L, env.deviceRamMb)
        assertEquals(0.5f, env.batteryLevel)
        assertEquals(true, env.batteryCharging)
        assertEquals("Verizon", env.carrier)
        assertEquals("5G", env.radio)
        assertEquals("release", env.buildType)
    }

    @Test
    fun `recordError carries a structured stack`() = runTest {
        val sender = FakeSender()
        val m = build(this, FakeStore(), sender, clock = { 1_000L })

        m.recordError("crash:Foo.bar", "code", stack = listOf("Foo.bar(Foo.kt:1)", "Baz.qux(Baz.kt:2)"))
        advanceUntilIdle()

        val e = sender.batches.flatMap { it.events }.single { it.type == TYPE_ERROR }
        assertEquals(listOf("Foo.bar(Foo.kt:1)", "Baz.qux(Baz.kt:2)"), e.stack)
    }
}
