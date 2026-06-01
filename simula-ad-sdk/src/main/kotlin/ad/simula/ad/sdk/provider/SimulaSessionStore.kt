package ad.simula.ad.sdk.provider

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.network.SimulaApiClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the server session and coalesces concurrent session-creation calls.
 *
 * [ensureSession] returns the cached id, awaits an in-flight creation, or starts
 * a new one in [SimulaScope] (so it survives the launching composable being torn
 * down). The in-flight handle is always cleared once it settles, so a failed
 * attempt is retryable on the next call. Mirrors the Swift SDK's `ensureSession`
 * and removes the launch-race "Session invalid" failure.
 */
internal class SimulaSessionStore(
    private val apiKey: String,
    private val devMode: Boolean,
    private val effectiveUserID: String?,
) {
    /** Observable session id — recomposes consumers when the session arrives. */
    var sessionId by mutableStateOf<String?>(null)
        private set

    private val mutex = Mutex()
    private var sessionDeferred: Deferred<String?>? = null

    suspend fun ensureSession(): String? {
        sessionId?.takeIf { it.isNotBlank() }?.let { return it }

        val deferred = mutex.withLock {
            sessionId?.let { if (it.isNotBlank()) return it }
            sessionDeferred ?: SimulaScope.async {
                SimulaApiClient.createSession(apiKey, devMode, effectiveUserID)
                    ?.takeIf { it.isNotBlank() }
            }.also { sessionDeferred = it }
        }

        val id = try {
            deferred.await()
        } finally {
            // Clear regardless of success/failure so the next call can retry.
            mutex.withLock {
                if (sessionDeferred === deferred) sessionDeferred = null
            }
        }
        if (!id.isNullOrBlank()) sessionId = id
        return id
    }
}
