package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.core.LaunchSettledGate
import ad.simula.ad.sdk.core.ProcessLaunchSettledGate
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.telemetry.Telemetry
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
    val createdTimestamp: Long = 0L,
)

/** Persists the pending-verification queue. Abstracted so the queue engine can be unit-tested. */
internal interface VerificationStore {
    fun load(): DurableLoadResult<List<PendingVerification>>
    fun save(queue: List<PendingVerification>): DurableMutationResult

    fun persistNew(records: List<PendingVerification>): DurableMutationResult {
        if (records.isEmpty()) return DurableMutationResult.NoMatch
        val queue = when (val loaded = load()) {
            is DurableLoadResult.Loaded -> loaded.value.toMutableList()
            DurableLoadResult.Failed -> return DurableMutationResult.Failed
        }
        var changed = false
        for (record in records) {
            if (queue.none { it.serveId == record.serveId }) {
                queue += record
                changed = true
            }
        }
        return if (changed) save(queue) else DurableMutationResult.NoMatch
    }

    fun insertIfAbsent(verification: PendingVerification): DurableMutationResult {
        val queue = when (val loaded = load()) {
            is DurableLoadResult.Loaded -> loaded.value.toMutableList()
            DurableLoadResult.Failed -> return DurableMutationResult.Failed
        }
        if (queue.any { it.serveId == verification.serveId }) return DurableMutationResult.NoMatch
        queue += verification
        return save(queue)
    }

    fun remove(serveId: String): DurableMutationResult {
        val queue = when (val loaded = load()) {
            is DurableLoadResult.Loaded -> loaded.value
            DurableLoadResult.Failed -> return DurableMutationResult.Failed
        }
        if (queue.none { it.serveId == serveId }) return DurableMutationResult.NoMatch
        return save(queue.filterNot { it.serveId == serveId })
    }

    fun replaceIfPresent(verification: PendingVerification): DurableMutationResult {
        val queue = when (val loaded = load()) {
            is DurableLoadResult.Loaded -> loaded.value.toMutableList()
            DurableLoadResult.Failed -> return DurableMutationResult.Failed
        }
        val index = queue.indexOfFirst { it.serveId == verification.serveId }
        if (index != -1) {
            queue[index] = verification
            return save(queue)
        }
        return DurableMutationResult.NoMatch
    }
}

internal class DurableQueuePersistenceException(message: String) : Exception(message)

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
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxPendingEnqueues: Int = DEFAULT_MAX_PENDING_ENQUEUES,
) {
    private data class PendingEnqueue(
        val verification: PendingVerification,
        val callback: ((Result<String?>) -> Unit)?,
    )

    private val mutex = Mutex()
    private var isProcessing = false

    /**
     * A scheduled wake-up for the earliest backed-off task after a retryable failure (e.g. a
     * server 5xx). Without it the backoff computed eligibility but nothing ever re-triggered
     * the drain — a failed verify sat in the queue until the NEXT earned reward or app
     * relaunch, so the reward-verified signal could stall for a whole session. Guarded by [mutex].
     */
    private var retryJob: Job? = null
    private var storageRecoveryJob: Job? = null
    private val pendingEnqueues = LinkedHashMap<String, PendingEnqueue>()
    private var storageFailureCount = 0
    private var storageFailureReported = false

    /**
     * Per-`serveId` result callbacks, so a verification's outcome reaches the caller
     * that enqueued it — not whoever happens to be draining the queue. One-shot and removed only
     * after the network outcome is durably reconciled. Storage-recovery callbacks stay in the
     * bounded pending map; capacity rejection fails the new callback immediately.
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
        scope.launch {
            var rejectedCallback: ((Result<String?>) -> Unit)? = null
            val shouldProcess = mutex.withLock {
                val existing = pendingEnqueues[serveId]
                if (existing == null && pendingEnqueues.size >= maxPendingEnqueues.coerceAtLeast(0)) {
                    rejectedCallback = onResult
                    Telemetry.recordError(
                        signature = "durable_queue:pending_full",
                        breadcrumb = "queue=reward_verification",
                    )
                    return@withLock false
                }
                val verification = existing?.verification ?: PendingVerification(
                    serveId = serveId,
                    sessionId = sessionId,
                    elapsedPlayTime = elapsedPlayTime,
                    retryCount = 0,
                    lastAttemptTimestamp = 0L,
                    adUnitId = adUnitId,
                    createdTimestamp = clock(),
                )
                pendingEnqueues[serveId] = PendingEnqueue(
                    verification = verification,
                    callback = onResult ?: existing?.callback,
                )
                if (storageRecoveryJob?.isActive == true) {
                    false
                } else {
                    recoverStorageLocked()
                }
            }
            if (rejectedCallback != null) {
                try {
                    rejectedCallback?.invoke(
                        Result.failure(
                            DurableQueuePersistenceException("Reward verification pending capacity reached"),
                        ),
                    )
                } catch (_: Exception) {
                    // A publisher callback must not break the SDK scope.
                }
                return@launch
            }
            if (shouldProcess) processQueue()
        }
    }

    /** Called with [mutex] held. Persists pending serve ids atomically before exposing callbacks. */
    private fun recoverStorageLocked(scheduleOnFailure: Boolean = true): Boolean {
        val durable = when (val loaded = store.load()) {
            is DurableLoadResult.Loaded -> loaded.value
            DurableLoadResult.Failed -> {
                noteStorageFailure()
                if (scheduleOnFailure) scheduleStorageRecoveryLocked()
                return false
            }
        }
        val durableIds = durable.mapTo(HashSet()) { it.serveId }
        val records = pendingEnqueues.values
            .map { it.verification }
            .filterNot { it.serveId in durableIds }
        val persistence = store.persistNew(records)
        if (persistence == DurableMutationResult.Failed) {
            noteStorageFailure()
            if (scheduleOnFailure) scheduleStorageRecoveryLocked()
            return false
        }
        for ((serveId, pending) in pendingEnqueues) {
            pending.callback?.let { activeCallbacks[serveId] = it }
        }
        pendingEnqueues.clear()
        noteStorageSuccess()
        return true
    }

    /** Called with [mutex] held. At most one storage-recovery job exists per queue. */
    private fun scheduleStorageRecoveryLocked() {
        if (storageRecoveryJob?.isActive == true) return
        storageRecoveryJob = scope.launch {
            var recovered = false
            while (!recovered) {
                val delayMs = mutex.withLock {
                    durableMutationBackoffMs(storageFailureCount.coerceAtLeast(1))
                }
                sleep(delayMs)
                recovered = mutex.withLock {
                    val result = recoverStorageLocked(scheduleOnFailure = false)
                    if (result) storageRecoveryJob = null
                    result
                }
            }
            processQueue(scheduleIneligibleWake = true)
        }
    }

    private fun noteStorageFailure() {
        storageFailureCount++
        if (!storageFailureReported) {
            storageFailureReported = true
            Telemetry.recordError(
                signature = "durable_queue:storage_failed",
                breadcrumb = "queue=reward_verification",
            )
        }
    }

    private fun noteStorageSuccess() {
        storageFailureCount = 0
        storageFailureReported = false
    }

    /** Drains any persisted verifications eligible under their backoff. */
    fun trigger(launchSettledGate: LaunchSettledGate = LaunchSettledGate.Open) {
        scope.launch {
            launchSettledGate.awaitSettled()
            processQueue()
        }
    }

    private suspend fun processQueue(scheduleIneligibleWake: Boolean = true) {
        mutex.withLock {
            if (isProcessing) return
            isProcessing = true
        }
        var bailedForBackoff = false
        var madeProgress = false
        var loadFailed = false
        try {
            while (true) {
                val selection: DurableLoadResult<PendingVerification?> = mutex.withLock {
                    when (val loaded = store.load()) {
                        is DurableLoadResult.Loaded -> {
                            noteStorageSuccess()
                            val now = clock()
                            DurableLoadResult.Loaded(
                                loaded.value.firstOrNull {
                                    now - it.lastAttemptTimestamp >= rewardVerificationBackoffMs(it.retryCount)
                                },
                            )
                        }
                        DurableLoadResult.Failed -> {
                            noteStorageFailure()
                            DurableLoadResult.Failed
                        }
                    }
                }
                val task = when (selection) {
                    is DurableLoadResult.Loaded -> selection.value ?: break
                    DurableLoadResult.Failed -> {
                        loadFailed = true
                        break
                    }
                }

                val outcome = try {
                    Result.success(
                        verifier.verify(task.serveId, task.sessionId, task.elapsedPlayTime, task.adUnitId),
                    )
                } catch (e: Exception) {
                    Result.failure(e)
                }
                val failure = outcome.exceptionOrNull()
                val retryable = failure != null && !isPermanentVerificationError(failure)
                if (retryable) {
                    val mutation = reconcileMutation {
                        store.replaceIfPresent(
                            task.copy(
                                retryCount = task.retryCount + 1,
                                lastAttemptTimestamp = clock(),
                            ),
                        )
                    }
                    bailedForBackoff = mutation == DurableMutationResult.Applied
                } else {
                    reconcileMutation { store.remove(task.serveId) }
                }

                // The callback is released only after delete/retry state is durably reconciled.
                try {
                    activeCallbacks.remove(task.serveId)?.invoke(outcome)
                } catch (_: Exception) {
                    // A listener that throws must not break queue draining.
                }
                madeProgress = true
                if (retryable && bailedForBackoff) break
            }
        } finally {
            finishProcessing(bailedForBackoff, scheduleIneligibleWake, madeProgress, loadFailed)
        }
    }

    private suspend fun reconcileMutation(
        mutation: () -> DurableMutationResult,
    ): DurableMutationResult = withContext(NonCancellable) {
        var failures = 0
        var result: DurableMutationResult
        do {
            result = mutex.withLock { mutation() }
            if (result == DurableMutationResult.Failed) {
                failures++
                sleep(durableMutationBackoffMs(failures))
            }
        } while (result == DurableMutationResult.Failed)
        result
    }

    private suspend fun finishProcessing(
        bailedForBackoff: Boolean,
        scheduleIneligibleWake: Boolean,
        madeProgress: Boolean,
        loadFailed: Boolean,
    ) {
        val (reDrain, wakeDelay) = withContext(NonCancellable) {
            mutex.withLock {
                isProcessing = false
                if (loadFailed) {
                    scheduleStorageRecoveryLocked()
                    return@withLock false to null
                }
                val remaining = when (val loaded = store.load()) {
                    is DurableLoadResult.Loaded -> {
                        noteStorageSuccess()
                        loaded.value
                    }
                    DurableLoadResult.Failed -> {
                        noteStorageFailure()
                        scheduleStorageRecoveryLocked()
                        return@withLock false to null
                    }
                }
                if (remaining.isEmpty()) return@withLock false to null
                val now = clock()
                val earliestDelay = remaining.minOf {
                    rewardVerificationBackoffMs(it.retryCount) - (now - it.lastAttemptTimestamp)
                }
                when {
                    earliestDelay <= 0L && !bailedForBackoff -> true to null
                    bailedForBackoff -> false to earliestDelay.coerceAtLeast(1_000L)
                    scheduleIneligibleWake || madeProgress -> false to earliestDelay.coerceAtLeast(1L)
                    else -> false to null
                }
            }
        }
        if (reDrain) trigger()
        if (wakeDelay != null) scheduleRetry(wakeDelay)
    }

    private suspend fun scheduleRetry(delayMs: Long) = mutex.withLock {
        retryJob?.cancel()
        retryJob = scope.launch {
            sleep(delayMs)
            processQueue(scheduleIneligibleWake = false)
        }
    }
}

/**
 * Production entry point: a process-wide [RewardVerificationQueue] wired to the real
 * SQLite row store and `SimulaApiClient` verifier, built lazily from the app
 * context. The rewarded ad enqueues here on a qualifying close; [triggerProcessQueue]
 * is also called at SDK init to recover verifications left pending by a prior process.
 */
internal object RewardVerificationManager {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var engine: RewardVerificationQueue? = null

    private fun engine(context: Context): RewardVerificationQueue {
        return engine ?: synchronized(this) {
            engine ?: RewardVerificationQueue(
                store = SqliteVerificationStore(context.applicationContext, json),
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
    ) {
        // Store construction can open/migrate SQLite. Keep it off the Activity caller even if an ad
        // somehow earns a reward before the normal startup recovery trigger builds the singleton.
        SimulaScope.launch {
            val queue = runCatching { engine(context) }.getOrElse {
                try {
                    onResult?.invoke(
                        Result.failure(DurableQueuePersistenceException("Unable to open reward verification store")),
                    )
                } catch (_: Exception) {
                    // A publisher callback must not break the SDK scope.
                }
                return@launch
            }
            queue.queue(serveId, sessionId, elapsedPlayTime, adUnitId, onResult)
        }
    }

    /**
     * Drains any persisted verifications eligible under their backoff. Call at app
     * startup to recover work left over from a previous session.
     */
    fun triggerProcessQueue(
        context: Context,
        launchSettledGate: LaunchSettledGate = ProcessLaunchSettledGate,
    ) = engine(context).trigger(launchSettledGate)
}

/** Real verifier: the SSV-firing `verify-reward` call (HTTP 409 → success token=null). */
private object ApiRewardVerifier : RewardVerifier {
    override suspend fun verify(serveId: String, sessionId: String, elapsedPlayTime: Double, adUnitId: String): String? =
        SimulaApiClient.verifyReward(serveId, sessionId, elapsedPlayTime, adUnitId).token
}

/** WAL SQLite rows keyed by the stable reward action key (`serveId`). */
internal class SqliteVerificationStore internal constructor(
    private val rows: DurableQueueRows,
    private val legacy: LegacyQueueSource,
    private val json: Json,
    private val clock: () -> Long,
) : VerificationStore {
    constructor(
        context: Context,
        json: Json,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        rows = SqliteDurableQueueRows(context, DB_NAME, TABLE),
        legacy = SharedPrefsLegacyQueueSource(context, LEGACY_PREFS, LEGACY_KEY),
        json = json,
        clock = clock,
    )

    init {
        migrateLegacy()
    }

    override fun load(): DurableLoadResult<List<PendingVerification>> {
        return when (val loaded = rows.load()) {
            is DurableLoadResult.Loaded -> {
                val decoded = ArrayList<PendingVerification>(loaded.value.size)
                for (row in loaded.value) {
                    val verification = runCatching {
                        json.decodeFromString<PendingVerification>(row.payload)
                    }.getOrNull() ?: return DurableLoadResult.Failed
                    if (verification.serveId != row.key || verification.serveId != row.rowId) {
                        return DurableLoadResult.Failed
                    }
                    decoded += verification
                }
                DurableLoadResult.Loaded(decoded)
            }
            DurableLoadResult.Failed -> DurableLoadResult.Failed
        }
    }

    override fun save(queue: List<PendingVerification>): DurableMutationResult = mutation {
        rows.replaceAll(queue.map(::row))
    }

    override fun insertIfAbsent(verification: PendingVerification): DurableMutationResult = mutation {
        rows.insertIfAbsent(row(verification))
    }

    override fun persistNew(records: List<PendingVerification>): DurableMutationResult = mutation {
        rows.insertAllIfAbsent(records.map(::row))
    }

    override fun remove(serveId: String): DurableMutationResult = mutation { rows.remove(serveId) }

    override fun replaceIfPresent(verification: PendingVerification): DurableMutationResult = mutation {
        rows.replaceIfRevision(verification.serveId, verification.serveId, row(verification))
    }

    private fun mutation(block: () -> DurableMutationResult): DurableMutationResult =
        runCatching(block).getOrDefault(DurableMutationResult.Failed)

    private fun row(verification: PendingVerification): DurableQueueRow {
        val normalized = if (verification.createdTimestamp > 0L) {
            verification
        } else {
            verification.copy(createdTimestamp = clock())
        }
        return DurableQueueRow(
            key = normalized.serveId,
            rowId = normalized.serveId,
            createdAt = normalized.createdTimestamp,
            payload = json.encodeToString(normalized),
        )
    }

    private fun migrateLegacy() {
        val encoded = legacy.read() ?: return
        val pending = runCatching { json.decodeFromString<List<PendingVerification>>(encoded) }.getOrNull() ?: return
        val migratedRows = runCatching { pending.map(::row) }.getOrNull() ?: return
        if (mutation { rows.import(migratedRows) } == DurableMutationResult.Applied) legacy.clear()
    }

    internal companion object {
        const val DB_NAME = "simula_ad_sdk_reward_verifications.db"
        const val TABLE = "pending_reward_verifications"
        const val LEGACY_PREFS = "simula_ad_sdk_verification_prefs"
        const val LEGACY_KEY = "pending_reward_verifications"
    }
}
