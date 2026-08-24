package ad.simula.ad.sdk.telemetry

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SdkEntryTelemetryTest {
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    @Test
    fun `event encodes numeric time since init and old persisted event still decodes`() {
        val timed = TelemetryEvent(
            type = TYPE_OPERATION,
            name = "webview_prewarm",
            eventId = "event-1",
            timestamp = 123L,
            timeSinceInitMs = 456L,
        )

        val encoded = json.encodeToString(timed)
        assertTrue(encoded.contains("\"time_since_init_ms\":456"))

        val old = json.decodeFromString<TelemetryEvent>(
            """{"type":"operation","name":"old","event_id":"event-0","timestamp":1}""",
        )
        assertNull(old.timeSinceInitMs)
        assertFalse(json.encodeToString(old).contains("time_since_init_ms"))
    }

    @Test
    fun `entry origin is first wins monotonic and clamps clock regressions`() {
        var now = 1_000_000L
        val origin = MonotonicSdkEntryOrigin { now }

        assertNull(origin.elapsedMs())
        assertEquals(1_000_000L, origin.markEntry())
        now = 4_500_000L
        assertEquals(3L, origin.elapsedMs())
        assertEquals("later entry cannot replace the origin", 1_000_000L, origin.markEntry())
        now = 500_000L
        assertEquals(0L, origin.elapsedMs())
    }

    @Test
    fun `imperative config claim reserved before provider call remains first wins`() = runTest {
        val firstWins = FirstWinsProcessTask<Unit>(backgroundScope)
        var installedConfig: String? = null
        val imperative = firstWins.claim { installedConfig = "imperative" }

        runCurrent()
        assertNull("claim must remain lazy", installedConfig)

        val provider = firstWins.claim { installedConfig = "provider" }
        provider.startAndAwait()
        imperative.await()

        assertEquals("imperative", installedConfig)
    }

    @Test
    fun `provider config genuinely claimed first remains first wins`() = runTest {
        val firstWins = FirstWinsProcessTask<Unit>(backgroundScope)
        var installedConfig: String? = null
        val provider = firstWins.claim { installedConfig = "provider" }
        val imperative = firstWins.claim { installedConfig = "imperative" }

        imperative.startAndAwait()
        provider.await()

        assertEquals("provider", installedConfig)
    }

    @Test
    fun `both first wins callers await shared process task completion`() = runTest {
        val firstWins = FirstWinsProcessTask<Unit>(backgroundScope)
        val ownerEntered = CompletableDeferred<Unit>()
        val releaseOwner = CompletableDeferred<Unit>()
        var loserCompleted = false

        val owner = launch {
            firstWins.runOnce {
                ownerEntered.complete(Unit)
                releaseOwner.await()
            }
        }
        ownerEntered.await()
        val loser = launch {
            firstWins.runOnce { error("loser must not initialize") }
            loserCompleted = true
        }
        runCurrent()
        assertFalse(loserCompleted)

        releaseOwner.complete(Unit)
        owner.join()
        loser.join()
        assertTrue(loserCompleted)
    }

    @Test
    fun `failed first wins process task remains the immutable result`() = runTest {
        val firstWins = FirstWinsProcessTask<Unit>(backgroundScope)
        val failed = runCatching {
            firstWins.runOnce { throw IllegalStateException("construction failed") }
        }
        var laterBlockRan = false

        val later = runCatching { firstWins.runOnce { laterBlockRan = true } }

        assertTrue(failed.isFailure)
        assertTrue(later.isFailure)
        assertFalse(laterBlockRan)
    }

    @Test
    fun `first wins process task survives claiming caller cancellation`() = runTest {
        val firstWins = FirstWinsProcessTask<Unit>(backgroundScope)
        val taskEntered = CompletableDeferred<Unit>()
        val releaseTask = CompletableDeferred<Unit>()
        var laterCompleted = false

        val claimingCaller = launch {
            firstWins.runOnce {
                taskEntered.complete(Unit)
                releaseTask.await()
            }
        }
        taskEntered.await()
        claimingCaller.cancel()
        claimingCaller.join()

        val laterCaller = launch {
            firstWins.runOnce { error("later caller must not replace the process task") }
            laterCompleted = true
        }
        runCurrent()
        assertFalse(laterCompleted)

        releaseTask.complete(Unit)
        laterCaller.join()
        assertTrue(laterCompleted)
    }

    @Test
    fun `bounded duplicate counter claims concurrent increments once`() {
        val counter = BoundedCounter(maxCount = 10_000)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val workers = List(8) {
            thread(start = true) {
                ready.countDown()
                start.await()
                repeat(500) { counter.increment() }
            }
        }

        ready.await()
        start.countDown()
        workers.forEach { it.join() }

        val claim = counter.claim()
        assertEquals(4_000, claim?.count)
        assertEquals(null, counter.claim())
        assertTrue(claim != null && counter.acknowledge(claim))
        assertEquals(null, counter.claim())
    }

    @Test
    fun `duplicate counter saturates at its bounded wire count`() {
        val counter = BoundedCounter(maxCount = 3)
        repeat(10) { counter.increment() }
        assertEquals(3, counter.claim()?.count)
    }

    @Test
    fun `counter drain retains claim until persistence and then drains new increments`() = runTest {
        val counter = BoundedCounter(maxCount = 10)
        repeat(2) { counter.increment() }
        val recorded = mutableListOf<Int>()
        val callbacks = mutableListOf<(TelemetryPersistenceOutcome) -> Unit>()

        val drain = BoundedCounterRecoveryDrain(
            counter = counter,
            scope = backgroundScope,
            sleep = {},
            recordDurably = { count, callback ->
                recorded += count
                callbacks += callback
            },
        )
        drain.drain()
        repeat(3) { counter.increment() }
        drain.drain()

        assertEquals(listOf(2), recorded)
        assertEquals(3, counter.pendingCount())

        callbacks.removeAt(0)(TelemetryPersistenceOutcome.Persisted)
        assertEquals(listOf(2, 3), recorded)
        callbacks.removeAt(0)(TelemetryPersistenceOutcome.Persisted)
        assertEquals(0, counter.pendingCount())
        assertEquals(null, counter.claim())
    }

    @Test
    fun `retryable persistence failure schedules one drain and succeeds without another call`() = runTest {
        val counter = BoundedCounter(maxCount = 10)
        repeat(4) { counter.increment() }
        val attempts = mutableListOf<Int>()
        val sleeps = mutableListOf<Long>()
        val drain = BoundedCounterRecoveryDrain(
            counter = counter,
            scope = backgroundScope,
            sleep = { sleeps += it },
            backoffMs = { it * 10L },
            recordDurably = { count, callback ->
                attempts += count
                callback(
                    if (attempts.size == 1) TelemetryPersistenceOutcome.RetryableFailure
                    else TelemetryPersistenceOutcome.Persisted,
                )
            },
        )

        drain.drain()
        runCurrent()

        assertEquals(listOf(4, 4), attempts)
        assertEquals(listOf(10L), sleeps)
        assertEquals(0, counter.pendingCount())
    }

    @Test
    fun `disabled persistence outcome does not schedule a recovery loop`() = runTest {
        val counter = BoundedCounter(maxCount = 10).apply { increment() }
        var attempts = 0
        var sleeps = 0
        val drain = BoundedCounterRecoveryDrain(
            counter = counter,
            scope = backgroundScope,
            sleep = { sleeps++ },
            recordDurably = { _, callback ->
                attempts++
                callback(TelemetryPersistenceOutcome.Disabled)
            },
        )

        drain.drain()
        runCurrent()

        assertEquals(1, attempts)
        assertEquals(0, sleeps)
        assertEquals(1, counter.pendingCount())
    }

    @Test
    fun `permanent persistence failure exhausts bounded cycle and manual retrigger can recover`() = runTest {
        val counter = BoundedCounter(maxCount = 10).apply { increment() }
        var attempts = 0
        var succeeds = false
        val drain = BoundedCounterRecoveryDrain(
            counter = counter,
            scope = this,
            sleep = { delay(it) },
            backoffMs = { it * 10L },
            maxAutomaticAttempts = MAX_DUPLICATE_PERSISTENCE_RECOVERY_ATTEMPTS,
            recordDurably = { _, callback ->
                attempts++
                callback(
                    if (succeeds) TelemetryPersistenceOutcome.Persisted
                    else TelemetryPersistenceOutcome.RetryableFailure,
                )
            },
        )

        drain.drain()
        advanceUntilIdle()

        assertEquals(1 + MAX_DUPLICATE_PERSISTENCE_RECOVERY_ATTEMPTS, attempts)
        assertEquals(1, counter.pendingCount())

        succeeds = true
        drain.drain()
        runCurrent()

        assertEquals(2 + MAX_DUPLICATE_PERSISTENCE_RECOVERY_ATTEMPTS, attempts)
        assertEquals(0, counter.pendingCount())
    }

    @Test
    fun `duplicate recovery backoff remains capped`() {
        assertEquals(60_000L, telemetryBackoffMs(100))
    }

    @Test
    fun `typed first wins task returns effective config to every claimant`() = runTest {
        val firstWins = FirstWinsProcessTask<EffectiveTelemetryConfig>(backgroundScope)
        val first = firstWins.claim { EffectiveTelemetryConfig("first", devMode = false, enabled = false) }
        val second = firstWins.claim { EffectiveTelemetryConfig("second", devMode = true, enabled = true) }

        assertEquals(first.startAndAwait(), second.startAndAwait())
        assertEquals(EffectiveTelemetryConfig("first", devMode = false, enabled = false), second.await())
    }
}
