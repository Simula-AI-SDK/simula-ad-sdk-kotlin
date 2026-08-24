package ad.simula.ad.sdk.provider

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.telemetry.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

internal const val INITIAL_ADVERTISING_ID_TIMEOUT_MS = 2_500L

/** One-shot provider gate that settles privacy and telemetry before declarative session creation. */
internal class PrivacySessionCoordinator(
    private val advertisingIdTimeoutMs: Long = INITIAL_ADVERTISING_ID_TIMEOUT_MS,
    private val startAdvertisingIdRefresh: (suspend () -> Unit) -> Deferred<Unit> = { refresh ->
        SimulaScope.async { refresh() }
    },
    private val recordAdvertisingIdTimeout: () -> Unit = {
        Telemetry.recordOperation(
            name = "privacy_gaid_startup_timeout",
            durationMs = INITIAL_ADVERTISING_ID_TIMEOUT_MS,
            success = false,
            failureClass = "timeout",
        )
    },
) {
    private val privacyReady = CompletableDeferred<Unit>()

    suspend fun preparePrivacy(
        attach: suspend () -> Unit,
        installTelemetry: suspend () -> Unit = {},
        refreshAdvertisingId: suspend () -> Unit,
        prepareInfrastructure: suspend () -> Unit = {},
    ) {
        try {
            settleStep(attach)
            settleStep(installTelemetry)
            settleStep {
                awaitInitialAdvertisingIdRefresh(
                    refreshAdvertisingId = refreshAdvertisingId,
                    timeoutMs = advertisingIdTimeoutMs,
                    startRefresh = startAdvertisingIdRefresh,
                    recordTimeout = recordAdvertisingIdTimeout,
                )
            }
            settleStep(prepareInfrastructure)
        } finally {
            privacyReady.complete(Unit)
        }
    }

    /** Releases waiters if the owning Compose effect is disposed before its body starts. */
    fun completeFailOpen() {
        privacyReady.complete(Unit)
    }

    suspend fun awaitPrivacyReady() {
        privacyReady.await()
    }

    suspend fun ensureSession(resolve: suspend () -> String?): String? {
        awaitPrivacyReady()
        return resolve()
    }

    private suspend fun settleStep(step: suspend () -> Unit) {
        try {
            step()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Privacy setup is best-effort; ads must remain available if platform services fail.
        }
    }
}

/**
 * Waits only long enough for GAID to improve the first session. The refresh runs in a process scope
 * so a blocking Play Services binder call cannot hold the session gate past [timeoutMs]; a timed-out
 * read may still finish and update later requests.
 */
internal suspend fun awaitInitialAdvertisingIdRefresh(
    refreshAdvertisingId: suspend () -> Unit,
    timeoutMs: Long = INITIAL_ADVERTISING_ID_TIMEOUT_MS,
    startRefresh: (suspend () -> Unit) -> Deferred<Unit> = { refresh -> SimulaScope.async { refresh() } },
    recordTimeout: () -> Unit = {
        Telemetry.recordOperation(
            name = "privacy_gaid_startup_timeout",
            durationMs = timeoutMs,
            success = false,
            failureClass = "timeout",
        )
    },
) {
    val refresh = try {
        startRefresh(refreshAdvertisingId)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        return
    }
    val completed = try {
        withTimeoutOrNull(timeoutMs) {
            refresh.await()
            true
        } ?: false
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        true // Refresh failures already fail open; only a timeout needs a distinct operation.
    }
    if (!completed) runCatching(recordTimeout)
}
