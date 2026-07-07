package ad.simula.ad.sdk.provider

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.telemetry.Telemetry
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
    initialUserID: String?,
) {
    /** Observable session id — recomposes consumers when the session arrives. */
    var sessionId by mutableStateOf<String?>(null)
        private set

    /**
     * Current PPID (primary user id). Mutable so [SimulaAds.updatePrimaryUserID] can change it
     * mid-session: the *next* [createSession][ad.simula.ad.sdk.network.SimulaApiClient.createSession]
     * carries it, and the telemetry envelope reads it live. `@Volatile` for cross-thread visibility
     * (read on the IO session-create coroutine and the telemetry flush coroutine).
     */
    @Volatile
    var effectiveUserID: String? = initialUserID
        private set

    /**
     * The PPID the current *server* session actually represents — set to the value the session was
     * created with, and advanced only when a `PATCH …/ppid` succeeds. This can lag [effectiveUserID]
     * after a mid-session login/logout/switch (the local id updates immediately; the server session
     * reconciles asynchronously, and a logout can't be pushed at all). Consumers that must evaluate
     * for a specific identity (e.g. the frequency-cap check) compare the two and drop the session id
     * when they diverge, so a stale session can't make the backend answer for the wrong user.
     */
    @Volatile
    var sessionUserID: String? = null
        private set

    private val mutex = Mutex()
    private var sessionDeferred: Deferred<String?>? = null

    /** Replace the PPID mid-session. Blank/empty normalizes to null (logout). */
    fun updatePpid(id: String?) {
        effectiveUserID = id?.takeIf { it.isNotBlank() }
    }

    /**
     * Record that the server session now represents [id] — called after a successful
     * `PATCH …/ppid`. A no-op-safe setter kept internal to the SDK.
     */
    fun markSessionUserID(id: String?) {
        sessionUserID = id
    }

    suspend fun ensureSession(): String? {
        sessionId?.takeIf { it.isNotBlank() }?.let { return it }

        val deferred = mutex.withLock {
            sessionId?.let { if (it.isNotBlank()) return it }
            sessionDeferred ?: SimulaScope.async {
                // Emit session_created/session_failed exactly once per creation attempt (this block
                // runs once even though many callers await the shared deferred). Best-effort.
                val startNanos = System.nanoTime()
                // Capture the ppid this session is created with so sessionUserID tracks the server
                // session's true identity (used to detect a stale session after a mid-session change).
                val ppidAtCreation = effectiveUserID
                val id = SimulaApiClient.createSession(apiKey, devMode, ppidAtCreation)
                    ?.takeIf { it.isNotBlank() }
                val durationMs = (System.nanoTime() - startNanos) / 1_000_000
                if (id != null) {
                    sessionUserID = ppidAtCreation
                    Telemetry.recordOperation("session_created", durationMs, success = true)
                } else {
                    Telemetry.recordOperation("session_failed", durationMs, success = false, failureClass = "no_session")
                }
                id
            }.also { sessionDeferred = it }
        }

        val id = try {
            // runCatching so even if createSession ever throws (today it returns null on failure),
            // the exception can't escape into the calling LaunchedEffect and crash the host — a
            // failed await collapses to null and stays retryable via the finally below. A
            // CancellationException is rethrown so the caller's coroutine still cancels cooperatively.
            runCatching { deferred.await() }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
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
