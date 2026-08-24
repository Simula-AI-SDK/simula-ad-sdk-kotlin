package ad.simula.ad.sdk.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacySessionCoordinatorTest {

    @Test
    fun `child cannot create a session before privacy readiness`() = runTest {
        val coordinator = PrivacySessionCoordinator(
            startAdvertisingIdRefresh = { refresh -> backgroundScope.async { runCatching { refresh() }; Unit } },
        )
        val attachEntered = CompletableDeferred<Unit>()
        val releaseAttach = CompletableDeferred<Unit>()
        var refreshFinished = false
        var sessionCalls = 0

        launch {
            coordinator.preparePrivacy(
                attach = {
                    attachEntered.complete(Unit)
                    releaseAttach.await()
                },
                refreshAdvertisingId = { refreshFinished = true },
            )
        }
        attachEntered.await()

        val child = async {
            coordinator.ensureSession {
                sessionCalls++
                "session"
            }
        }
        runCurrent()

        assertEquals(0, sessionCalls)
        releaseAttach.complete(Unit)

        assertEquals("session", child.await())
        assertTrue(refreshFinished)
        assertEquals(1, sessionCalls)
    }

    @Test
    fun `telemetry installs after attach and before provider session readiness`() = runTest {
        val coordinator = PrivacySessionCoordinator(
            startAdvertisingIdRefresh = { refresh -> backgroundScope.async { refresh() } },
        )
        val steps = mutableListOf<String>()

        coordinator.preparePrivacy(
            attach = { steps += "attach" },
            installTelemetry = { steps += "telemetry" },
            refreshAdvertisingId = { steps += "gaid" },
            prepareInfrastructure = { steps += "infrastructure" },
        )
        val session = coordinator.ensureSession {
            steps += "session"
            "session-id"
        }

        assertEquals("session-id", session)
        assertEquals(listOf("attach", "telemetry", "gaid", "infrastructure", "session"), steps)
    }

    @Test
    fun `concurrent child callers release after readiness`() = runTest {
        val coordinator = PrivacySessionCoordinator(
            startAdvertisingIdRefresh = { refresh -> backgroundScope.async { runCatching { refresh() }; Unit } },
        )
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        var sessionCalls = 0

        launch {
            coordinator.preparePrivacy(
                attach = {},
                refreshAdvertisingId = {
                    refreshEntered.complete(Unit)
                    releaseRefresh.await()
                },
            )
        }
        refreshEntered.await()

        val children = List(3) { index ->
            async {
                coordinator.ensureSession {
                    sessionCalls++
                    "session-$index"
                }
            }
        }
        runCurrent()
        assertEquals(0, sessionCalls)

        releaseRefresh.complete(Unit)

        assertEquals(listOf("session-0", "session-1", "session-2"), children.awaitAll())
        assertEquals(3, sessionCalls)
    }

    @Test
    fun `attach and refresh failures complete readiness fail open`() = runTest {
        val coordinator = PrivacySessionCoordinator(
            startAdvertisingIdRefresh = { refresh -> backgroundScope.async { runCatching { refresh() }; Unit } },
        )
        val settledSteps = mutableListOf<String>()

        coordinator.preparePrivacy(
            attach = {
                settledSteps += "attach"
                throw IllegalStateException("preferences unavailable")
            },
            installTelemetry = {
                settledSteps += "telemetry"
                throw IllegalStateException("telemetry unavailable")
            },
            refreshAdvertisingId = {
                settledSteps += "refresh"
                throw IllegalStateException("GAID unavailable")
            },
        )

        val session = coordinator.ensureSession { "session" }

        assertEquals(listOf("attach", "telemetry", "refresh"), settledSteps)
        assertEquals("session", session)
    }

    @Test
    fun `initializer cancellation completes readiness fail open`() = runTest {
        val coordinator = PrivacySessionCoordinator(
            startAdvertisingIdRefresh = { refresh -> backgroundScope.async { runCatching { refresh() }; Unit } },
        )
        val attachEntered = CompletableDeferred<Unit>()
        var sessionCalls = 0

        val initializer = launch {
            coordinator.preparePrivacy(
                attach = {
                    attachEntered.complete(Unit)
                    awaitCancellation()
                },
                refreshAdvertisingId = {},
            )
        }
        attachEntered.await()

        val child = async {
            coordinator.ensureSession {
                sessionCalls++
                "session"
            }
        }
        runCurrent()
        assertEquals(0, sessionCalls)

        initializer.cancel()
        runCurrent()

        assertTrue(initializer.isCancelled)
        assertEquals("session", child.await())
        assertEquals(1, sessionCalls)
    }

    @Test
    fun `advertising id timeout releases session and records timeout`() = runTest {
        var timeoutOperations = 0
        var sessionCalls = 0
        val refreshEntered = CompletableDeferred<Unit>()
        val coordinator = PrivacySessionCoordinator(
            advertisingIdTimeoutMs = 2_500L,
            startAdvertisingIdRefresh = { refresh -> backgroundScope.async { runCatching { refresh() }; Unit } },
            recordAdvertisingIdTimeout = { timeoutOperations++ },
        )

        launch {
            coordinator.preparePrivacy(
                attach = {},
                refreshAdvertisingId = {
                    refreshEntered.complete(Unit)
                    awaitCancellation()
                },
            )
        }
        refreshEntered.await()
        val child = async {
            coordinator.ensureSession {
                sessionCalls++
                "session"
            }
        }

        advanceTimeBy(2_499L)
        runCurrent()
        assertEquals(0, sessionCalls)
        assertEquals(0, timeoutOperations)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals("session", child.await())
        assertEquals(1, sessionCalls)
        assertEquals(1, timeoutOperations)
    }
}
