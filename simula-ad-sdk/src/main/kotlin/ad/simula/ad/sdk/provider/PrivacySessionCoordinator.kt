package ad.simula.ad.sdk.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** One-shot provider gate that settles privacy before declarative session creation. */
internal class PrivacySessionCoordinator {
    private val privacyReady = CompletableDeferred<Unit>()

    suspend fun preparePrivacy(
        attach: suspend () -> Unit,
        refreshAdvertisingId: suspend () -> Unit,
    ) {
        try {
            settleStep(attach)
            settleStep(refreshAdvertisingId)
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
