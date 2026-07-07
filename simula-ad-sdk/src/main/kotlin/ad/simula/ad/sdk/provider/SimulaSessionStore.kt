package ad.simula.ad.sdk.provider

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.telemetry.Telemetry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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

    // Serializes PPID reconciliation so at most one PATCH is ever in flight. The server then applies
    // updates in submission order (no reordering), which is what lets [sessionUserID] be advanced
    // safely — a late/out-of-order PATCH can never mark an identity the server didn't converge to.
    private val ppidSyncMutex = Mutex()

    /** Replace the PPID mid-session. Blank/empty normalizes to null (logout). */
    fun updatePpid(id: String?) {
        effectiveUserID = id?.takeIf { it.isNotBlank() }
    }

    /**
     * Drive the server session's PPID toward the current [effectiveUserID] and, on success, advance
     * [sessionUserID] to match — but only while that value is still the desired identity.
     *
     * Single-flight via [ppidSyncMutex]: only one reconcile loop runs at a time and it issues its
     * PATCHes sequentially, so the server applies them in order (no reordering can leave the local
     * [sessionUserID] disagreeing with the real server identity). Rapid switches collapse — a queued
     * reconcile finds the value already synced and no-ops. A logout (null) or a not-yet-created
     * session is intentionally a no-op that leaves [sessionUserID] stale, so a frequency-cap check
     * drops the now-mismatched session id rather than trusting it.
     */
    fun reconcileServerPpid() {
        SimulaScope.launch {
            ppidSyncMutex.withLock {
                while (true) {
                    val target = effectiveUserID ?: break
                    val sid = sessionId?.takeIf { it.isNotBlank() } ?: break
                    if (target == sessionUserID) break
                    val ok = runCatching { SimulaApiClient.updatePpid(apiKey, sid, target) }.getOrDefault(false)
                    if (!ok) break
                    // Accept the result only if the target is still current; otherwise loop to sync
                    // the newer value. Because this ran under the mutex, the PATCH just sent is the
                    // latest to reach the server, so this never marks an unconverged identity.
                    if (target == effectiveUserID) {
                        sessionUserID = target
                        break
                    }
                }
            }
        }
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
