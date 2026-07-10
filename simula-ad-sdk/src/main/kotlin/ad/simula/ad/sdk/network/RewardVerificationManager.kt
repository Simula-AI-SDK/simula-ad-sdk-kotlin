package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.core.SimulaScope
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * A reward verification waiting to be delivered to the server. Persisted so a verify
 * that couldn't land before the app was backgrounded/killed is retried — the reward
 * (and its server-side SSV postback) is never silently lost.
 */
@Serializable
internal data class PendingVerification(
    val serveId: String,
    val sessionId: String,
    val elapsedPlayTime: Double,
    var retryCount: Int,
    var lastAttemptTimestamp: Long,
    // Sent to verify-reward so the SSV callback resolves the ad unit. Defaulted + last so queue
    // entries persisted before this field existed still decode (with adUnitId = "").
    val adUnitId: String = "",
)

/** Persists the pending-verification queue. Abstracted so the queue engine can be unit-tested. */
internal interface VerificationStore {
    fun load(): List<PendingVerification>
    fun save(queue: List<PendingVerification>)
}

/** Performs one `verify-reward` call; returns the reward token (may be null) or throws. */
internal interface RewardVerifier {
    suspend fun verify(serveId: String, sessionId: String, elapsedPlayTime: Double, adUnitId: String): String?
}

/** Exponential backoff: first attempt immediate, then 5s, 10s, 20s, 40s, 60s cap. */
internal fun rewardVerificationBackoffMs(retryCount: Int): Long {
    if (retryCount <= 0) return 0L
    return minOf((2.0.pow(retryCount - 1) * 5000.0).toLong(), 60_000L)
}

/**
 * True if [e] is a permanent client error — a 4xx other than 408 (Request Timeout) or
 * 429 (Too Many Requests) — for which retrying won't help. Classified from the HTTP
 * status embedded in the message thrown by [SimulaApiClient] (`"... status: NNN"`).
 */
internal fun isPermanentVerificationError(e: Throwable): Boolean {
    val code = Regex("status: (\\d{3})").find(e.message ?: return false)
        ?.groupValues?.get(1)?.toIntOrNull() ?: return false
    return code in 400..499 && code != 408 && code != 429
}

/**
 * Thread-safe, persistent queue that delivers `verify-reward` calls reliably and
 * idempotently, off the UI path (mirrors the Swift `RewardVerificationManager`). The
 * rewarded ad closes optimistically and enqueues here, so the user never waits on the
 * network.
 *
 * - Idempotent: deduped by `serve_id`; the API layer maps HTTP 409 (already claimed)
 *   to success, so retries converge without double-firing the publisher's postback.
 * - Durable: persisted via [VerificationStore]; survives process death and is recovered
 *   on the next queue trigger.
 * - Backed off: failed attempts retry with exponential backoff (5s → max 60s).
 *
 * Collaborators are injected so the draining logic is unit-testable with fakes; the
 * production wiring lives in [RewardVerificationManager].
 */
internal class RewardVerificationQueue(
    private val store: VerificationStore,
    private val verifier: RewardVerifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = SimulaScope,
) {
    private val mutex = Mutex()
    private var isProcessing = false

    /**
     * A scheduled wake-up for the earliest backed-off task after a retryable failure (e.g. a
     * server 5xx). Without it the backoff computed eligibility but nothing ever re-triggered
     * the drain — a failed verify sat in the queue until the NEXT earned reward or app
     * relaunch, so the reward-verified signal could stall for a whole session. Guarded by [mutex].
     */
    private var retryJob: Job? = null

    /**
     * Per-`serveId` result callbacks, so a verification's outcome reaches the caller
     * that enqueued it — not whoever happens to be draining the queue. One-shot:
     * removed the first time the task is attempted, so it can't be misrouted to another
     * play and can't retain the caller beyond a single attempt.
     */
    private val activeCallbacks = ConcurrentHashMap<String, (Result<String?>) -> Unit>()

    /**
     * Enqueues a verification, persists it, and starts draining the queue. [onResult]
     * is delivered once — `success(token)` when verified (or already-claimed), or
     * `failure` on the first (possibly retryable) error. Safe to call repeatedly for the
     * same [serveId] — duplicates are ignored.
     */
    fun queue(
        serveId: String,
        sessionId: String,
        elapsedPlayTime: Double,
        adUnitId: String = "",
        onResult: ((Result<String?>) -> Unit)? = null,
    ) {
        // Register before enqueueing so a drain already in flight (which reloads the
        // queue each iteration and can pick this task up) still routes the result here.
        if (onResult != null) {
            activeCallbacks[serveId] = onResult
        }
        scope.launch {
            mutex.withLock {
                val list = store.load().toMutableList()
                if (list.none { it.serveId == serveId }) {
                    list.add(
                        PendingVerification(
                            serveId = serveId,
                            sessionId = sessionId,
                            elapsedPlayTime = elapsedPlayTime,
                            retryCount = 0,
                            lastAttemptTimestamp = 0L,
                            adUnitId = adUnitId,
                        ),
                    )
                    store.save(list)
                }
            }
            processQueue()
        }
    }

    /** Drains any persisted verifications eligible under their backoff. */
    fun trigger() {
        scope.launch { processQueue() }
    }

    private suspend fun processQueue() {
        mutex.withLock {
            if (isProcessing) return
            isProcessing = true
        }
        // True when we stopped because a task hit a retryable error: its peers (if any)
        // are intentionally left for a later trigger, so we must NOT immediately re-drain.
        var bailedForBackoff = false
        // Successfully-verified / permanently-failed serveIds, persisted in ONE save in the finally
        // block. The prior code did store.load() on every loop iteration AND a load+save per removal,
        // re-parsing the whole queue JSON each time → O(n²) on a large backlog. We now pick from a
        // single in-memory snapshot and batch the removals. Re-verifying a serveId after a crash
        // mid-drain is safe: the server SSV is idempotent and the client signal is one-shot.
        val removedServeIds = mutableSetOf<String>()
        try {
            // One load per drain pass (was one per iteration). Concurrently-enqueued verifications
            // are not in this snapshot, but the finally's re-drain re-loads and re-triggers for them.
            var queue = mutex.withLock { store.load() }
            while (true) {
                val now = clock()
                val task = queue.firstOrNull {
                    it.serveId !in removedServeIds &&
                        now - it.lastAttemptTimestamp >= rewardVerificationBackoffMs(it.retryCount)
                } ?: break

                // Resolve the outcome first, then deliver it. Keeping the callback OUT of
                // the verify try/catch means a listener that throws can't be misread as a
                // verification error (and can't derail the drain).
                val outcome: Result<String?> = try {
                    val token = verifier.verify(task.serveId, task.sessionId, task.elapsedPlayTime, task.adUnitId)
                    removedServeIds += task.serveId
                    Result.success(token)
                } catch (e: Exception) {
                    if (isPermanentVerificationError(e)) {
                        // 4xx (except 408/429): retrying won't help, so drop it.
                        removedServeIds += task.serveId
                    } else {
                        // Keep the task for a later trigger; the server-side SSV postback
                        // still lands on a successful retry — the client signal is one-shot.
                        // Persist the retry bump immediately and reflect it in the snapshot so it
                        // isn't re-picked this pass.
                        recordAttempt(task.serveId)
                        queue = queue.map {
                            if (it.serveId == task.serveId) {
                                it.copy(retryCount = it.retryCount + 1, lastAttemptTimestamp = now)
                            } else {
                                it
                            }
                        }
                        bailedForBackoff = true
                    }
                    Result.failure(e)
                }

                try {
                    activeCallbacks.remove(task.serveId)?.invoke(outcome)
                } catch (_: Exception) {
                    // A listener that throws must not break queue draining.
                }
                if (bailedForBackoff) break
            }
        } finally {
            // Persist all removals in one pass, then — under the SAME lock that observes the queue —
            // decide whether to re-drain. This closes the race where a verification enqueued just as
            // the drain finished would otherwise sit idle until some later trigger.
            // NonCancellable so a cancellation mid-drain can't leave isProcessing stuck or skip the save.
            val reDrain = withContext(NonCancellable) {
                mutex.withLock {
                    // Re-load before filtering so a verification enqueued concurrently during a
                    // verify() is preserved rather than clobbered by a stale snapshot save.
                    if (removedServeIds.isNotEmpty()) {
                        store.save(store.load().filterNot { it.serveId in removedServeIds })
                    }
                    isProcessing = false
                    if (bailedForBackoff) {
                        // Bailed on a retryable failure: schedule a wake at the earliest remaining
                        // backoff so the retry actually happens in-session, instead of waiting for
                        // the next enqueue or launch. Scheduled ONLY from the bail path (not from
                        // every pass with pending tasks), so a wake that finds nothing eligible —
                        // e.g. under a frozen test clock — terminates instead of rescheduling
                        // itself forever. One pending wake is enough: every bail recomputes the
                        // earliest eligibility across the WHOLE queue and replaces the schedule.
                        val remaining = store.load()
                        if (remaining.isNotEmpty()) {
                            val now = clock()
                            val delayMs = remaining
                                .minOf { rewardVerificationBackoffMs(it.retryCount) - (now - it.lastAttemptTimestamp) }
                                .coerceAtLeast(1_000L) // ≥1s floor: never hot-loop a failing backend
                            retryJob?.cancel()
                            retryJob = scope.launch {
                                delay(delayMs)
                                processQueue()
                            }
                        }
                        false
                    } else {
                        val now = clock()
                        store.load().any { now - it.lastAttemptTimestamp >= rewardVerificationBackoffMs(it.retryCount) }
                    }
                }
            }
            if (reDrain) trigger()
        }
    }

    private suspend fun recordAttempt(serveId: String) = mutex.withLock {
        val queue = store.load().toMutableList()
        val idx = queue.indexOfFirst { it.serveId == serveId }
        if (idx != -1) {
            queue[idx] = queue[idx].copy(
                retryCount = queue[idx].retryCount + 1,
                lastAttemptTimestamp = clock(),
            )
            store.save(queue)
        }
    }
}

/**
 * Production entry point: a process-wide [RewardVerificationQueue] wired to the real
 * `SharedPreferences` store and `SimulaApiClient` verifier, built lazily from the app
 * context. The rewarded ad enqueues here on a qualifying close; [triggerProcessQueue]
 * is also called at SDK init to recover verifications left pending by a prior process.
 */
internal object RewardVerificationManager {
    private const val PREFS_NAME = "simula_ad_sdk_verification_prefs"
    private const val KEY_PENDING_QUEUE = "pending_reward_verifications"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var engine: RewardVerificationQueue? = null

    private fun engine(context: Context): RewardVerificationQueue {
        return engine ?: synchronized(this) {
            engine ?: RewardVerificationQueue(
                store = SharedPrefsVerificationStore(context.applicationContext, json, PREFS_NAME, KEY_PENDING_QUEUE),
                verifier = ApiRewardVerifier,
            ).also { engine = it }
        }
    }

    fun queueVerification(
        context: Context,
        serveId: String,
        sessionId: String,
        elapsedPlayTime: Double,
        adUnitId: String = "",
        onResult: ((Result<String?>) -> Unit)? = null,
    ) = engine(context).queue(serveId, sessionId, elapsedPlayTime, adUnitId, onResult)

    /**
     * Drains any persisted verifications eligible under their backoff. Call at app
     * startup to recover work left over from a previous session.
     */
    fun triggerProcessQueue(context: Context) = engine(context).trigger()
}

/** Real verifier: the SSV-firing `verify-reward` call (HTTP 409 → success token=null). */
private object ApiRewardVerifier : RewardVerifier {
    override suspend fun verify(serveId: String, sessionId: String, elapsedPlayTime: Double, adUnitId: String): String? =
        SimulaApiClient.verifyReward(serveId, sessionId, elapsedPlayTime, adUnitId).token
}

/** Real store: a single `SharedPreferences` entry holding the JSON-encoded queue. */
private class SharedPrefsVerificationStore(
    private val context: Context,
    private val json: Json,
    private val prefsName: String,
    private val key: String,
) : VerificationStore {
    override fun load(): List<PendingVerification> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(key, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<PendingVerification>>(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun save(queue: List<PendingVerification>) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putString(key, json.encodeToString(queue)).apply()
    }
}
