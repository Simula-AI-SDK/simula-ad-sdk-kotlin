package ad.simula.ad.sdk.provider

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.network.Ipv4Beacon
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.telemetry.Telemetry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The id and server-side identity are replaced as one observable value. */
private data class PublishedSession(val id: String?, val userID: String?)

/**
 * Holds the server session and coalesces both ordinary creation and forced refresh calls.
 *
 * The process-scope attempt, rather than any individual waiter, owns publication and in-flight
 * cleanup. Cancelling a caller therefore cannot expose a still-running request to a second caller.
 */
internal class SimulaSessionStore(
    private val apiKey: String,
    private val devMode: Boolean,
    initialUserID: String?,
    private val workScope: CoroutineScope = SimulaScope,
    private val publicationDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val createSession: suspend (String, Boolean, String?) -> String? = SimulaApiClient::createSession,
    private val recordSessionOperation: (String, Long, Boolean, String?) -> Unit = { name, durationMs, success, failureClass ->
        Telemetry.recordOperation(name, durationMs, success, failureClass = failureClass)
    },
    private val fireIpv4: (String, String?, String?, String) -> Unit = Ipv4Beacon::fire,
) {
    private var publishedSession by mutableStateOf(PublishedSession(id = null, userID = null))

    /** Observable session id; consumers recompose when a successful refresh replaces it. */
    val sessionId: String? get() = publishedSession.id

    /**
     * Current PPID (primary user id). Mutable so `SimulaAds.updatePrimaryUserID` can change it
     * mid-session. Volatile because request and telemetry work read it from background threads.
     */
    @Volatile
    var effectiveUserID: String? = initialUserID
        private set

    /** The PPID represented by [sessionId], published atomically with the id. */
    val sessionUserID: String? get() = publishedSession.userID

    private val sessionLock = Any()
    private var sessionDeferred: CompletableDeferred<String?>? = null

    /**
     * Optional startup gate resolved for every attempt. This keeps both imperative and provider-only
     * entry paths behind consent, telemetry installation, and beacon-queue setup.
     */
    var startupGate: () -> CompletableDeferred<Unit>? = { null }

    // PATCH requests are serialized; completion also verifies that the patched id is still current.
    private val ppidSyncMutex = Mutex()

    /** Replace the PPID mid-session. Blank/empty normalizes to null (logout). */
    fun updatePpid(id: String?) {
        effectiveUserID = id?.takeIf { it.isNotBlank() }
    }

    /** Drive the server session's PPID toward the current [effectiveUserID], best-effort. */
    fun reconcileServerPpid() {
        workScope.launch {
            ppidSyncMutex.withLock {
                while (true) {
                    val target = effectiveUserID ?: break
                    val snapshot = publishedSession
                    val sid = snapshot.id?.takeIf { it.isNotBlank() } ?: break
                    if (target == snapshot.userID) {
                        if (effectiveUserID == target) {
                            fireIpv4(apiKey, sid, target, Ipv4Beacon.REASON_PPID_UPDATE)
                        }
                        break
                    }
                    val ok = runCatching { SimulaApiClient.updatePpid(apiKey, sid, target) }.getOrDefault(false)
                    if (!ok) break
                    // This records server truth even if the desired identity changed during PATCH;
                    // the loop then converges to the latest value in submission order. A foreground
                    // refresh can replace the id while this PATCH is in flight, so only update the
                    // pair when the PATCH still describes the published session.
                    withContext(publicationDispatcher) {
                        synchronized(sessionLock) {
                            if (publishedSession.id == sid) {
                                publishedSession = publishedSession.copy(userID = target)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Return the current id, or await/start the one process-scope request. An already requested
     * foreground refresh takes precedence over the cached id, so ad callers cannot race past it.
     */
    suspend fun ensureSession(): String? {
        startupGate()?.await()

        val deferred = synchronized(sessionLock) {
            sessionDeferred?.let { return@synchronized it }
            publishedSession.id?.takeIf { it.isNotBlank() }?.let { return it }
            startAttemptLocked()
        }
        return deferred.await()
    }

    /**
     * Synchronously claim a forced refresh before scheduling its network work. Lifecycle callbacks
     * use this so an immediate ad load observes and awaits this exact attempt instead of the cache.
     */
    fun requestForcedRefresh(
        beforeCreate: suspend () -> Unit = {},
    ): Deferred<String?> = synchronized(sessionLock) {
        sessionDeferred ?: startAttemptLocked(beforeCreate)
    }

    /** Must be called while holding [sessionLock]. */
    private fun startAttemptLocked(
        beforeCreate: suspend () -> Unit = {},
    ): CompletableDeferred<String?> {
        val deferred = CompletableDeferred<String?>()
        sessionDeferred = deferred
        val job = runCatching {
            workScope.launch { runSessionAttempt(deferred, beforeCreate) }
        }.getOrNull()
        if (job == null) {
            sessionDeferred = null
            deferred.complete(publishedSession.id)
        } else {
            // launch() on an already-cancelled scope returns a cancelled Job rather than throwing.
            // Ensure that case cannot strand every future caller on an incomplete shared deferred.
            job.invokeOnCompletion { cause ->
                if (cause != null && !deferred.isCompleted) {
                    val fallback = synchronized(sessionLock) {
                        if (sessionDeferred === deferred) sessionDeferred = null
                        publishedSession.id
                    }
                    deferred.complete(fallback)
                }
            }
        }
        return deferred
    }

    private suspend fun runSessionAttempt(
        deferred: CompletableDeferred<String?>,
        beforeCreate: suspend () -> Unit,
    ) {
        val startNanos = System.nanoTime()
        // Privacy/device refresh is best-effort. A platform-service failure must not prevent the
        // backend from resuming or replacing an otherwise usable session.
        runCatching { startupGate()?.await() }
        runCatching { beforeCreate() }
        val ppidAtCreation = effectiveUserID
        val createdId = runCatching { createSession(apiKey, devMode, ppidAtCreation) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000

        val result = withContext(NonCancellable + publicationDispatcher) {
            runCatching {
                synchronized(sessionLock) {
                    if (sessionDeferred !== deferred) return@synchronized publishedSession.id
                    if (createdId != null) {
                        publishedSession = PublishedSession(createdId, ppidAtCreation)
                    }
                    sessionDeferred = null
                    publishedSession.id
                }
            }.getOrElse {
                // Even an unexpected Compose-state/publication failure must release every ad caller.
                synchronized(sessionLock) {
                    if (sessionDeferred === deferred) sessionDeferred = null
                    publishedSession.id
                }
            }
        }
        deferred.complete(result)

        runCatching {
            if (createdId != null) {
                recordSessionOperation("session_created", durationMs, true, null)
            } else {
                recordSessionOperation("session_failed", durationMs, false, "no_session")
            }
        }
        if (effectiveUserID == ppidAtCreation) {
            runCatching { fireIpv4(apiKey, createdId, ppidAtCreation, Ipv4Beacon.REASON_INIT) }
        }
        if (createdId != null && effectiveUserID != ppidAtCreation) reconcileServerPpid()
    }
}
