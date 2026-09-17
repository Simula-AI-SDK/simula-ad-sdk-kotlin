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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The id, server-side identity, and process generation are replaced as one observable value. */
private data class PublishedSession(val id: String?, val userID: String?, val generation: Long)

private data class SessionFlight(
    val deferred: CompletableDeferred<String?>,
    val generation: Long,
)

/**
 * Holds the server session and coalesces both ordinary creation and lazy expired-session refreshes.
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
    private val patchPpid: suspend (String, String, String) -> Boolean = SimulaApiClient::updatePpid,
    private val sessionGeneration: () -> Long = { 0L },
    private val beforeExpiredSessionCreate: suspend () -> Unit = {},
) {
    private var publishedSession by mutableStateOf(
        PublishedSession(id = null, userID = null, generation = currentSessionGeneration()),
    )

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
    private var sessionFlight: SessionFlight? = null

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
                    val ok = runCatching { patchPpid(apiKey, sid, target) }.getOrDefault(false)
                    if (!ok) break
                    // This records server truth even if the desired identity changed during PATCH;
                    // the loop then converges to the latest value in submission order. A foreground
                    // refresh can replace the id while this PATCH is in flight, so only update the
                    // pair when the PATCH still describes the published session.
                    withContext(publicationDispatcher) {
                        synchronized(sessionLock) {
                            if (
                                publishedSession.id == sid &&
                                publishedSession.generation == snapshot.generation
                            ) {
                                publishedSession = publishedSession.copy(userID = target)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Return the current id, or lazily await/start the one process-scope request for the current
     * expiration generation. A failed stale refresh returns the cached id without advancing its
     * generation, so the next external call retries rather than treating the stale cache as fresh.
     */
    suspend fun ensureSession(): String? {
        startupGate()?.await()

        while (true) {
            var cachedSession: PublishedSession? = null
            val flight: SessionFlight? = synchronized(sessionLock) {
                sessionFlight?.let { return@synchronized it }
                val requestedGeneration = currentSessionGeneration()
                val snapshot = publishedSession
                snapshot.id?.takeIf { it.isNotBlank() && snapshot.generation >= requestedGeneration }
                    ?.let {
                        cachedSession = snapshot
                        return@synchronized null
                    }
                return@synchronized startAttemptLocked(
                    generation = requestedGeneration,
                    refreshExpiredSession = snapshot.generation < requestedGeneration,
                )
            }
            cachedSession?.let { cached ->
                // The process generation is independent of sessionLock. Revalidate after selecting
                // the cache so a foreground expiration racing that selection cannot reuse the id.
                if (currentSessionGeneration() <= cached.generation) return cached.id
                return@let
            }
            if (cachedSession != null) continue

            val activeFlight = flight ?: continue
            val result = activeFlight.deferred.await()
            // Every waiter revalidates after the flight. If lifecycle expiration advanced while
            // it was suspended, all callers coalesce onto the current generation rather than one
            // returning an id that became stale mid-flight. A failure at the current generation
            // still fails open here; only a later external ensure retries that same generation.
            if (currentSessionGeneration() <= activeFlight.generation) return result
        }
    }

    /** Must be called while holding [sessionLock]. */
    private fun startAttemptLocked(
        generation: Long,
        refreshExpiredSession: Boolean,
    ): SessionFlight {
        val deferred = CompletableDeferred<String?>()
        val flight = SessionFlight(deferred, generation)
        sessionFlight = flight
        val job = runCatching {
            workScope.launch { runSessionAttempt(flight, refreshExpiredSession) }
        }.getOrNull()
        if (job == null) {
            sessionFlight = null
            deferred.complete(publishedSession.id)
        } else {
            // launch() on an already-cancelled scope returns a cancelled Job rather than throwing.
            // Ensure that case cannot strand every future caller on an incomplete shared deferred.
            job.invokeOnCompletion { cause ->
                if (cause != null && !deferred.isCompleted) {
                    val fallback = synchronized(sessionLock) {
                        if (sessionFlight === flight) sessionFlight = null
                        publishedSession.id
                    }
                    deferred.complete(fallback)
                }
            }
        }
        return flight
    }

    private suspend fun runSessionAttempt(
        flight: SessionFlight,
        refreshExpiredSession: Boolean,
    ) {
        val startNanos = System.nanoTime()
        // Privacy/device refresh is best-effort. A platform-service failure must not prevent the
        // backend from resuming or replacing an otherwise usable session.
        runCatching { startupGate()?.await() }
        if (refreshExpiredSession) runCatching { beforeExpiredSessionCreate() }
        val ppidAtCreation = effectiveUserID
        val createdId = runCatching { createSession(apiKey, devMode, ppidAtCreation) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000

        val result = withContext(NonCancellable + publicationDispatcher) {
            runCatching {
                synchronized(sessionLock) {
                    if (sessionFlight !== flight) return@synchronized publishedSession.id
                    if (createdId != null) {
                        publishedSession = PublishedSession(createdId, ppidAtCreation, flight.generation)
                    }
                    sessionFlight = null
                    publishedSession.id
                }
            }.getOrElse {
                // Even an unexpected Compose-state/publication failure must release every ad caller.
                synchronized(sessionLock) {
                    if (sessionFlight === flight) sessionFlight = null
                    publishedSession.id
                }
            }
        }
        flight.deferred.complete(result)

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

    private fun currentSessionGeneration(): Long = runCatching(sessionGeneration).getOrDefault(0L)
}
