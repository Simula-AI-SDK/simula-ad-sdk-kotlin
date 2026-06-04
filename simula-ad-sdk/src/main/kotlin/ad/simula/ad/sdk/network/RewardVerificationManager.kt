package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.core.SimulaScope
import android.content.Context
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
)

/**
 * Thread-safe, persistent queue that delivers `verify-reward` calls reliably and
 * idempotently, off the UI path (mirrors the Swift `RewardVerificationManager`). The
 * rewarded ad closes optimistically and enqueues here, so the user never waits on the
 * network.
 *
 * - Idempotent: deduped by `serve_id`; the API layer maps HTTP 409 (already claimed)
 *   to success, so retries converge without double-firing the publisher's postback.
 * - Durable: persisted to `SharedPreferences`; survives process death and is recovered
 *   on the next queue trigger.
 * - Backed off: failed attempts retry with exponential backoff (5s → max 60s).
 */
internal object RewardVerificationManager {
    private const val PREFS_NAME = "simula_ad_sdk_verification_prefs"
    private const val KEY_PENDING_QUEUE = "pending_reward_verifications"

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var isProcessing = false

    /**
     * Enqueues a verification, persists it, and starts draining the queue. [onResult]
     * is invoked per attempt: `success(token)` once verified (or already-claimed),
     * `failure` on a (possibly retryable) error. Safe to call repeatedly for the same
     * [serveId] — duplicates are ignored.
     */
    fun queueVerification(
        context: Context,
        serveId: String,
        sessionId: String,
        elapsedPlayTime: Double,
        onResult: ((Result<String?>) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        SimulaScope.launch {
            mutex.withLock {
                val list = loadQueue(appContext).toMutableList()
                if (list.none { it.serveId == serveId }) {
                    list.add(
                        PendingVerification(
                            serveId = serveId,
                            sessionId = sessionId,
                            elapsedPlayTime = elapsedPlayTime,
                            retryCount = 0,
                            lastAttemptTimestamp = 0L,
                        ),
                    )
                    saveQueue(appContext, list)
                }
            }
            processQueue(appContext, onResult)
        }
    }

    /**
     * Drains any persisted verifications eligible under their backoff. Call at app
     * startup to recover work left over from a previous session.
     */
    fun triggerProcessQueue(context: Context, onResult: ((Result<String?>) -> Unit)? = null) {
        val appContext = context.applicationContext
        SimulaScope.launch { processQueue(appContext, onResult) }
    }

    private suspend fun processQueue(context: Context, onResult: ((Result<String?>) -> Unit)?) {
        mutex.withLock {
            if (isProcessing) return
            isProcessing = true
        }
        try {
            while (true) {
                val task = mutex.withLock {
                    val now = System.currentTimeMillis()
                    loadQueue(context).firstOrNull {
                        now - it.lastAttemptTimestamp >= backoffMs(it.retryCount)
                    }
                } ?: break

                try {
                    val res = SimulaApiClient.verifyReward(
                        serveId = task.serveId,
                        sessionId = task.sessionId,
                        elapsedPlayTime = task.elapsedPlayTime,
                    )
                    removeTask(context, task.serveId)
                    onResult?.invoke(Result.success(res.token))
                } catch (e: Exception) {
                    if (isPermanentClientError(e)) {
                        // 4xx (except 408/429): retrying won't help, so drop it.
                        removeTask(context, task.serveId)
                        onResult?.invoke(Result.failure(e))
                    } else {
                        recordAttempt(context, task.serveId)
                        onResult?.invoke(Result.failure(e))
                        // Stop here; the remaining backoff is awaited by a later trigger.
                        break
                    }
                }
            }
        } finally {
            mutex.withLock { isProcessing = false }
        }
    }

    private suspend fun removeTask(context: Context, serveId: String) = mutex.withLock {
        val queue = loadQueue(context).toMutableList()
        queue.removeAll { it.serveId == serveId }
        saveQueue(context, queue)
    }

    private suspend fun recordAttempt(context: Context, serveId: String) = mutex.withLock {
        val queue = loadQueue(context).toMutableList()
        val idx = queue.indexOfFirst { it.serveId == serveId }
        if (idx != -1) {
            queue[idx].retryCount += 1
            queue[idx].lastAttemptTimestamp = System.currentTimeMillis()
            saveQueue(context, queue)
        }
    }

    /** 4xx (except 408 Request Timeout / 429 Too Many Requests) is a permanent error. */
    private fun isPermanentClientError(e: Exception): Boolean {
        val code = Regex("status: (\\d{3})").find(e.message ?: return false)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return code in 400..499 && code != 408 && code != 429
    }

    /** Exponential backoff: first attempt immediate, then 5s, 10s, 20s, 40s, 60s cap. */
    private fun backoffMs(retryCount: Int): Long {
        if (retryCount == 0) return 0L
        return minOf((2.0.pow(retryCount - 1) * 5000.0).toLong(), 60_000L)
    }

    private fun loadQueue(context: Context): List<PendingVerification> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PENDING_QUEUE, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<PendingVerification>>(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveQueue(context: Context, queue: List<PendingVerification>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PENDING_QUEUE, json.encodeToString(queue)).apply()
    }
}
