package ad.simula.ad.sdk.telemetry

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulaTelemetryStartupTest {
    @Test
    fun `first config wins while latest provider pair is published together`() = runTest {
        var observedConfig: String? = null
        var observedIdentity: Pair<String?, String?>? = null
        val engine = TelemetryStartupEngine<String>(this, runStartup = { config, currentProviders ->
            observedConfig = config
            val providers = currentProviders()
            observedIdentity = providers.sessionId() to providers.primaryUserId()
        })

        val firstReady = engine.register("first", providers("session-a", "user-a"))
        val secondReady = engine.register("second", providers("session-b", "user-b"))
        engine.start()
        runCurrent()

        assertSame(firstReady, secondReady)
        assertEquals("first", observedConfig)
        assertEquals("session-b" to "user-b", observedIdentity)
    }

    @Test
    fun `concurrent start calls execute startup once`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val starts = AtomicInteger(0)
            val entered = CountDownLatch(1)
            val engine = TelemetryStartupEngine<String>(scope, runStartup = { _, _ ->
                starts.incrementAndGet()
                entered.countDown()
            })
            val ready = engine.register("winner", providers("session", "user"))

            val callers = List(24) {
                Thread { engine.start() }.also { it.start() }
            }
            callers.forEach { it.join() }

            assertTrue(entered.await(5, TimeUnit.SECONDS))
            runBlocking { ready.await() }
            assertEquals(1, starts.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `provider lookup remains live after startup`() = runTest {
        val lookup = AtomicReference<(() -> TelemetryIdentityProviders)?>(null)
        val engine = TelemetryStartupEngine<String>(this, runStartup = { _, currentProviders ->
            lookup.set(currentProviders)
        })
        engine.register("first", providers("session-a", "user-a"))
        engine.start()
        runCurrent()

        engine.register("ignored-config", providers("session-b", "user-b"))
        val current = lookup.get()
        assertNotNull(current)
        val snapshot = current?.invoke()
        assertEquals("session-b", snapshot?.sessionId?.invoke())
        assertEquals("user-b", snapshot?.primaryUserId?.invoke())
    }

    @Test
    fun `higher priority identity pair cannot be replaced by a later lower priority pair`() = runTest {
        var observed: Pair<String?, String?>? = null
        val engine = TelemetryStartupEngine<String>(this, runStartup = { _, currentProviders ->
            val providers = currentProviders()
            observed = providers.sessionId() to providers.primaryUserId()
        })
        engine.register("provider-config", providers("provider-session", "provider-user"))
        engine.register(
            "ignored-imperative-config",
            providers("imperative-session", "imperative-user"),
            providerPriority = 1,
        )
        engine.register("ignored-provider-config", providers("late-provider-session", "late-provider-user"))

        engine.start()
        runCurrent()

        assertEquals("imperative-session" to "imperative-user", observed)
    }

    @Test
    fun `ungated work is fired as a sibling but never awaited by the gate`() = runTest {
        // Regression test for the crash-guard decoupling: ungated work suspends on a main
        // dispatcher that never drains (a stalled main looper) — the ad-request gate must
        // still release once the gated startup section finishes.
        val stalledMain = StandardTestDispatcher(TestCoroutineScheduler())
        Dispatchers.setMain(stalledMain)
        try {
            var gatedRan = false
            var ungatedRan = false
            val engine = TelemetryStartupEngine<String>(
                scope = backgroundScope, // auto-cancelled by runTest; the stalled job must not hang the test
                runStartup = { _, _ -> gatedRan = true },
                runUngated = { withContext(Dispatchers.Main) { ungatedRan = true } },
            )

            val ready = engine.register("config", providers("session", "user"))
            engine.start()
            runCurrent() // drains the gated startup; stalledMain is a separate scheduler

            assertTrue(gatedRan)
            assertTrue(ready.isCompleted)
            assertFalse(ungatedRan)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a non-completing shared ready is abandoned without cancellation`() = runTest {
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()

        assertFalse(awaitTelemetryReady(ready))
        assertEquals(TELEMETRY_READY_TIMEOUT_MS, testScheduler.currentTime)
        assertFalse("the shared startup must remain available to other waiters", ready.isCancelled)
        assertFalse(ready.isCompleted)

        // A late completion remains observable, proving the timeout cancelled only its waiter.
        ready.complete(Unit)
        assertTrue(awaitTelemetryReady(ready, timeoutMs = 5_000))
    }

    @Test
    fun `ready completes only after suspend startup publishes`() = runTest {
        val recoveryFinished = kotlinx.coroutines.CompletableDeferred<Unit>()
        var published = false
        val engine = TelemetryStartupEngine<String>(this, runStartup = { _, _ ->
            recoveryFinished.await()
            published = true
        })
        val ready = engine.register("config", providers("session", "user"))

        engine.start()
        runCurrent()
        assertFalse(published)
        assertFalse(ready.isCompleted)

        recoveryFinished.complete(Unit)
        runCurrent()
        assertTrue(published)
        assertTrue(ready.isCompleted)
    }

    private fun providers(sessionId: String, primaryUserId: String) =
        TelemetryIdentityProviders(sessionId = { sessionId }, primaryUserId = { primaryUserId })
}
