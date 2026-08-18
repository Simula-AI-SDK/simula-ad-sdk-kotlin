package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.core.LaunchSettledGate
import ad.simula.ad.sdk.core.ProcessLaunchSettledGate
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.model.normalizeExtraParameters
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
import java.util.UUID

/**
 * A billing/measurement beacon waiting to be delivered. Today's `track*` helpers are
 * fire-and-forget: a beacon that fails (offline, 5xx) is lost. This makes them durable —
 * persisted so a beacon that couldn't land before the app was backgrounded/killed is retried
 * (PRD "Better Telemetry Tracking" → durable billing queue).
 *
 * [action] is the impression-action path segment: `shown` / `seen` / `click`.
 */
@Serializable
internal data class PendingBeacon(
    val impressionId: String,
    val action: String,
    val metadata: Map<String, String>? = null,
    var retryCount: Int = 0,
    var lastAttemptTimestamp: Long = 0L,
    val rowId: String = UUID.randomUUID().toString(),
    val createdTimestamp: Long = 0L,
)

internal fun beaconActionKey(impressionId: String, action: String): String = "$impressionId\u0000$action"

/** Persists the pending-beacon queue. Abstracted so the queue engine is unit-testable. */
internal interface BeaconStore {
    fun load(): DurableLoadResult<List<PendingBeacon>>
    fun save(queue: List<PendingBeacon>): DurableMutationResult

    fun persist(records: List<PendingBeacon>): DurableMutationResult {
        if (records.isEmpty()) return DurableMutationResult.NoMatch
        val queue = when (val loaded = load()) {
            is DurableLoadResult.Loaded -> loaded.value.toMutableList()
            DurableLoadResult.Failed -> return DurableMutationResult.Failed
        }
        for (record in records) {
            val index = queue.indexOfFirst {
                it.impressionId == record.impressionId && it.action == record.action
            }
            if (index == -1) queue += record else queue[index] = record
        }
        return save(queue)
    }

    fun upsert(beacon: PendingBeacon): DurableMutationResult {
        val queue = when (val loaded = load()) {
            is DurableLoadResult.Loaded -> loaded.value.toMutableList()
            DurableLoadResult.Failed -> return DurableMutationResult.Failed
        }
        val index = queue.indexOfFirst {
            it.impressionId == beacon.impressionId && it.action == beacon.action
        }
        if (index == -1) queue += beacon else queue[index] = beacon
        return save(queue)
    }

    fun remove(beacon: PendingBeacon): DurableMutationResult {
        val queue = when (val loaded = load()) {
            is DurableLoadResult.Loaded -> loaded.value
            DurableLoadResult.Failed -> return DurableMutationResult.Failed
        }
        if (queue.none { it.rowId == beacon.rowId }) return DurableMutationResult.NoMatch
        return save(queue.filterNot { it.rowId == beacon.rowId })
    }

    fun replaceIfRevision(
        previous: PendingBeacon,
        replacement: PendingBeacon,
    ): DurableMutationResult {
        val queue = when (val loaded = load()) {
            is DurableLoadResult.Loaded -> loaded.value.toMutableList()
            DurableLoadResult.Failed -> return DurableMutationResult.Failed
        }
        val index = queue.indexOfFirst { it.rowId == previous.rowId }
        if (index != -1) {
            queue[index] = replacement
            return save(queue)
        }
        return DurableMutationResult.NoMatch
    }
}

/** Sends one impression-action beacon; returns the HTTP status, or throws on a connectivity failure. */
internal interface BeaconSender {
    suspend fun send(impressionId: String, action: String, metadata: Map<String, String>?): Int
}

/**
 * Thread-safe, persistent queue that delivers impression beacons (`/shown`, `/seen`, `/click`)
 * reliably and off the UI path — the same durable, conflict-free design as
 * [RewardVerificationQueue]. The ad fires-and-forgets into this queue; the queue owns delivery.
 *
 * - **Deduped**: at most one in-flight entry per `(impressionId, action)`, so a beacon is never
 *   enqueued twice. Retries only happen for sends that did NOT get a 2xx, so a beacon the server
 *   already accepted is not re-sent. (The billable `/seen` is deduped server-side per impression;
 *   `/click` increments a counter, so a lost-response retry carries a small over-count risk —
 *   acceptable vs. today's silent loss, and removable once the endpoint takes an idempotency key.)
 * - **Durable**: persisted via [BeaconStore]; survives process death, recovered on the next trigger.
 * - **Backed off**: failed attempts retry with the shared exponential backoff (5s → 60s cap).
 *
 * Collaborators are injected so the draining logic is unit-testable with fakes.
 */
internal class AdBeaconQueue(
    private val store: BeaconStore,
    private val sender: BeaconSender,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = SimulaScope,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxPendingEnqueues: Int = DEFAULT_MAX_PENDING_ENQUEUES,
) {
    private val mutex = Mutex()
    private var isProcessing = false
    private var retryJob: Job? = null
    private var storageRecoveryJob: Job? = null
    private val pendingEnqueues = LinkedHashMap<String, PendingBeacon>()
    private var storageFailureCount = 0
    private var storageFailureReported = false

    /** Enqueue a beacon and start draining. Duplicate metadata is merged for the same action. */
    fun queue(impressionId: String, action: String, metadata: Map<String, String>? = null) {
        if (impressionId.isBlank()) return
        val metadataSnapshot = metadata?.takeIf { action == "seen" }?.let { normalizeExtraParameters(it) }
        scope.launch {
            val shouldProcess = mutex.withLock {
                val key = beaconActionKey(impressionId, action)
                val existing = pendingEnqueues[key]
                if (existing == null && pendingEnqueues.size >= maxPendingEnqueues.coerceAtLeast(0)) {
                    Telemetry.recordError(
                        signature = "durable_queue:pending_full",
                        breadcrumb = "queue=beacon",
                    )
                    return@withLock false
                }
                pendingEnqueues[key] = if (existing == null) {
                    PendingBeacon(
                        impressionId = impressionId,
                        action = action,
                        metadata = metadataSnapshot,
                        createdTimestamp = clock(),
                    )
                } else if (!metadataSnapshot.isNullOrEmpty()) {
                    existing.copy(
                        metadata = normalizeExtraParameters(existing.metadata.orEmpty() + metadataSnapshot),
                    )
                } else {
                    existing
                }
                if (storageRecoveryJob?.isActive == true) {
                    false
                } else {
                    recoverStorageLocked()
                }
            }
            if (shouldProcess) processQueue()
        }
    }

    /** Called with [mutex] held. Persists every pending action in one SQLite transaction. */
    private fun recoverStorageLocked(scheduleOnFailure: Boolean = true): Boolean {
        val durable = when (val loaded = store.load()) {
            is DurableLoadResult.Loaded -> loaded.value
            DurableLoadResult.Failed -> {
                noteStorageFailure()
                if (scheduleOnFailure) scheduleStorageRecoveryLocked()
                return false
            }
        }
        val records = ArrayList<PendingBeacon>(pendingEnqueues.size)
        for ((key, pending) in pendingEnqueues) {
            val existing = durable.firstOrNull {
                beaconActionKey(it.impressionId, it.action) == key
            }
            if (existing == null) {
                records += pending
            } else if (!pending.metadata.isNullOrEmpty()) {
                val merged = normalizeExtraParameters(existing.metadata.orEmpty() + pending.metadata)
                if (merged != existing.metadata) {
                    records += existing.copy(metadata = merged, rowId = UUID.randomUUID().toString())
                }
            }
        }
        val persistence = store.persist(records)
        if (persistence == DurableMutationResult.Failed) {
            noteStorageFailure()
            if (scheduleOnFailure) scheduleStorageRecoveryLocked()
            return false
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
                    recoverStorageLocked(scheduleOnFailure = false)
                }
            }
            mutex.withLock {
                storageRecoveryJob = null
            }
            // Storage may recover before network backoff expires; normal processing must arm
            // the earliest eligibility wake rather than treating this as an early retry wake.
            processQueue(scheduleIneligibleWake = true)
        }
    }

    private fun noteStorageFailure() {
        storageFailureCount++
        if (!storageFailureReported) {
            storageFailureReported = true
            Telemetry.recordError(
                signature = "durable_queue:storage_failed",
                breadcrumb = "queue=beacon",
            )
        }
    }

    private fun noteStorageSuccess() {
        storageFailureCount = 0
        storageFailureReported = false
    }

    /** Drains any persisted beacons eligible under their backoff (call at startup to recover). */
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
                val selection: DurableLoadResult<PendingBeacon?> = mutex.withLock {
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

                val delivered = try {
                    val code = sender.send(task.impressionId, task.action, task.metadata)
                    when {
                        code in 200..299 -> true // accepted
                        code in 400..499 && code != 408 && code != 429 -> true // permanent client error → drop
                        else -> false // 5xx / 408 / 429 → retry
                    }
                } catch (_: Exception) {
                    false // connectivity failure → retry (server never received it)
                }

                if (delivered) {
                    reconcileMutation { store.remove(task) }
                } else {
                    // If metadata replaced this exact revision while it was in flight, retry the
                    // replacement immediately instead of backing off work that was never attempted.
                    bailedForBackoff = reconcileMutation {
                        store.replaceIfRevision(
                            task,
                            task.copy(retryCount = task.retryCount + 1, lastAttemptTimestamp = clock()),
                        )
                    } == DurableMutationResult.Applied
                }
                madeProgress = true
                if (bailedForBackoff) break
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
 * Process-wide [AdBeaconQueue] wired to the real SQLite store + `SimulaApiClient`.
 * Built once from [init] at SDK init (which has the app context + api key); ad surfaces then call
 * [enqueue] with no context/key threading. Kept OFF the telemetry pipeline (its batching/sampling/
 * event cap are wrong for billing); the diagnostic `impression_fired`/`click_fired` events emitted
 * here are interim visibility into beacon firing, separate from the durable beacon itself.
 */
internal object AdBeaconManager {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var engine: AdBeaconQueue? = null

    /** Build the queue (idempotent). Call once from `SimulaAds.initialize`. */
    fun init(appContext: Context, apiKey: String) {
        if (engine != null) return
        synchronized(this) {
            if (engine != null) return
            engine = AdBeaconQueue(
                store = SqliteBeaconStore(appContext, json),
                sender = ApiBeaconSender(apiKey),
            )
        }
    }

    /** Drain beacons left pending by a prior process. Call at startup after [init]. */
    fun triggerProcessQueue(launchSettledGate: LaunchSettledGate = ProcessLaunchSettledGate) {
        engine?.trigger(launchSettledGate)
    }

    /**
     * Durably enqueue an impression-action beacon ([action] = `shown` / `seen` / `click`) and emit a
     * diagnostic lifecycle event for the billing-relevant ones. A no-op before [init] (beacons only
     * fire while an ad is showing, which requires init).
     */
    fun enqueue(
        impressionId: String,
        action: String,
        adFormat: String? = null,
        adUnitId: String? = null,
        metadata: Map<String, String>? = null,
    ) {
        if (impressionId.isBlank()) return
        when (action) {
            "seen" -> Telemetry.recordLifecycle(
                stage = "impression_fired", adFormat = adFormat, adUnitId = adUnitId, adId = impressionId,
            )
            "click" -> Telemetry.recordLifecycle(
                stage = "click_fired", adFormat = adFormat, adUnitId = adUnitId, adId = impressionId,
            )
        }
        engine?.queue(impressionId, action, metadata)
    }
}

/** Real sender: a no-body impression beacon, surfacing the HTTP status so the queue can retry/drop. */
private class ApiBeaconSender(private val apiKey: String) : BeaconSender {
    override suspend fun send(impressionId: String, action: String, metadata: Map<String, String>?): Int =
        SimulaApiClient.sendImpressionBeacon(impressionId, action, apiKey, metadata)
}

/** WAL SQLite rows keyed by the stable `(impressionId, action)` action key. */
internal class SqliteBeaconStore internal constructor(
    private val rows: DurableQueueRows,
    private val legacy: LegacyQueueSource,
    private val json: Json,
    private val clock: () -> Long,
) : BeaconStore {
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

    override fun load(): DurableLoadResult<List<PendingBeacon>> {
        return when (val loaded = rows.load()) {
            is DurableLoadResult.Loaded -> {
                val decoded = ArrayList<PendingBeacon>(loaded.value.size)
                for (row in loaded.value) {
                    val beacon = runCatching { json.decodeFromString<PendingBeacon>(row.payload) }.getOrNull()
                        ?: return DurableLoadResult.Failed
                    if (beaconActionKey(beacon.impressionId, beacon.action) != row.key || beacon.rowId != row.rowId) {
                        return DurableLoadResult.Failed
                    }
                    decoded += beacon
                }
                DurableLoadResult.Loaded(decoded)
            }
            DurableLoadResult.Failed -> DurableLoadResult.Failed
        }
    }

    override fun save(queue: List<PendingBeacon>): DurableMutationResult = mutation {
        rows.replaceAll(queue.map(::row))
    }

    override fun upsert(beacon: PendingBeacon): DurableMutationResult = mutation {
        rows.upsert(row(beacon))
    }

    override fun persist(records: List<PendingBeacon>): DurableMutationResult = mutation {
        rows.upsertAll(records.map(::row))
    }

    override fun remove(beacon: PendingBeacon): DurableMutationResult = mutation {
        rows.removeIfRevision(beaconActionKey(beacon.impressionId, beacon.action), beacon.rowId)
    }

    override fun replaceIfRevision(
        previous: PendingBeacon,
        replacement: PendingBeacon,
    ): DurableMutationResult = mutation {
        rows.replaceIfRevision(
            beaconActionKey(previous.impressionId, previous.action), previous.rowId, row(replacement),
        )
    }

    private fun mutation(block: () -> DurableMutationResult): DurableMutationResult =
        runCatching(block).getOrDefault(DurableMutationResult.Failed)

    private fun row(beacon: PendingBeacon): DurableQueueRow {
        val normalized = if (beacon.createdTimestamp > 0L) beacon else beacon.copy(createdTimestamp = clock())
        return DurableQueueRow(
            key = beaconActionKey(normalized.impressionId, normalized.action),
            rowId = normalized.rowId,
            createdAt = normalized.createdTimestamp,
            payload = json.encodeToString(normalized),
        )
    }

    private fun migrateLegacy() {
        val encoded = legacy.read() ?: return
        val pending = runCatching { json.decodeFromString<List<PendingBeacon>>(encoded) }.getOrNull() ?: return
        val migratedRows = runCatching { pending.map(::row) }.getOrNull() ?: return
        if (mutation { rows.import(migratedRows) } == DurableMutationResult.Applied) legacy.clear()
    }

    internal companion object {
        const val DB_NAME = "simula_ad_sdk_beacons.db"
        const val TABLE = "pending_beacons"
        const val LEGACY_PREFS = "simula_ad_sdk_beacon_prefs"
        const val LEGACY_KEY = "pending_beacons"
    }
}
