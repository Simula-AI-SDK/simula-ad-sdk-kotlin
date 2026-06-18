package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.telemetry.Telemetry
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    var retryCount: Int = 0,
    var lastAttemptTimestamp: Long = 0L,
)

/** Persists the pending-beacon queue. Abstracted so the queue engine is unit-testable. */
internal interface BeaconStore {
    fun load(): List<PendingBeacon>
    fun save(queue: List<PendingBeacon>)
}

/** Sends one impression-action beacon; returns the HTTP status, or throws on a connectivity failure. */
internal interface BeaconSender {
    suspend fun send(impressionId: String, action: String): Int
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
) {
    private val mutex = Mutex()
    private var isProcessing = false

    /** Enqueue a beacon and start draining. Safe to call repeatedly — duplicates are ignored. */
    fun queue(impressionId: String, action: String) {
        if (impressionId.isBlank()) return
        scope.launch {
            mutex.withLock {
                val list = store.load().toMutableList()
                if (list.none { it.impressionId == impressionId && it.action == action }) {
                    list.add(PendingBeacon(impressionId, action))
                    store.save(list)
                }
            }
            processQueue()
        }
    }

    /** Drains any persisted beacons eligible under their backoff (call at startup to recover). */
    fun trigger() {
        scope.launch { processQueue() }
    }

    private suspend fun processQueue() {
        mutex.withLock {
            if (isProcessing) return
            isProcessing = true
        }
        var bailedForBackoff = false
        try {
            while (true) {
                val task = mutex.withLock {
                    val now = clock()
                    store.load().firstOrNull {
                        now - it.lastAttemptTimestamp >= rewardVerificationBackoffMs(it.retryCount)
                    }
                } ?: break

                val delivered = try {
                    val code = sender.send(task.impressionId, task.action)
                    when {
                        code in 200..299 -> true // accepted
                        code in 400..499 && code != 408 && code != 429 -> true // permanent client error → drop
                        else -> false // 5xx / 408 / 429 → retry
                    }
                } catch (_: Exception) {
                    false // connectivity failure → retry (server never received it)
                }

                if (delivered) {
                    removeTask(task)
                } else {
                    recordAttempt(task)
                    bailedForBackoff = true
                }
                if (bailedForBackoff) break
            }
        } finally {
            // Clear the claim and decide re-drain under the same lock (closes the enqueue-just-as-
            // drain-finished race). NonCancellable so a cancellation can't strand isProcessing.
            val reDrain = withContext(NonCancellable) {
                mutex.withLock {
                    isProcessing = false
                    if (bailedForBackoff) {
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

    private suspend fun removeTask(task: PendingBeacon) = mutex.withLock {
        store.save(store.load().filterNot { it.impressionId == task.impressionId && it.action == task.action })
    }

    private suspend fun recordAttempt(task: PendingBeacon) = mutex.withLock {
        val queue = store.load().toMutableList()
        val idx = queue.indexOfFirst { it.impressionId == task.impressionId && it.action == task.action }
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
 * Process-wide [AdBeaconQueue] wired to the real `SharedPreferences` store + `SimulaApiClient`.
 * Built once from [init] at SDK init (which has the app context + api key); ad surfaces then call
 * [enqueue] with no context/key threading. Kept OFF the telemetry pipeline (its batching/sampling/
 * event cap are wrong for billing); the diagnostic `impression_fired`/`click_fired` events emitted
 * here are interim visibility into beacon firing, separate from the durable beacon itself.
 */
internal object AdBeaconManager {
    private const val PREFS_NAME = "simula_ad_sdk_beacon_prefs"
    private const val KEY_PENDING_QUEUE = "pending_beacons"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var engine: AdBeaconQueue? = null

    /** Build the queue (idempotent). Call once from `SimulaAds.initialize`. */
    fun init(appContext: Context, apiKey: String) {
        if (engine != null) return
        synchronized(this) {
            if (engine != null) return
            engine = AdBeaconQueue(
                store = SharedPrefsBeaconStore(appContext, json, PREFS_NAME, KEY_PENDING_QUEUE),
                sender = ApiBeaconSender(apiKey),
            )
        }
    }

    /** Drain beacons left pending by a prior process. Call at startup after [init]. */
    fun triggerProcessQueue() {
        engine?.trigger()
    }

    /**
     * Durably enqueue an impression-action beacon ([action] = `shown` / `seen` / `click`) and emit a
     * diagnostic lifecycle event for the billing-relevant ones. A no-op before [init] (beacons only
     * fire while an ad is showing, which requires init).
     */
    fun enqueue(impressionId: String, action: String, adFormat: String? = null, adUnitId: String? = null) {
        if (impressionId.isBlank()) return
        when (action) {
            "seen" -> Telemetry.recordLifecycle(
                stage = "impression_fired", adFormat = adFormat, adUnitId = adUnitId, adId = impressionId,
            )
            "click" -> Telemetry.recordLifecycle(
                stage = "click_fired", adFormat = adFormat, adUnitId = adUnitId, adId = impressionId,
            )
        }
        engine?.queue(impressionId, action)
    }
}

/** Real sender: a no-body impression beacon, surfacing the HTTP status so the queue can retry/drop. */
private class ApiBeaconSender(private val apiKey: String) : BeaconSender {
    override suspend fun send(impressionId: String, action: String): Int =
        SimulaApiClient.sendImpressionBeacon(impressionId, action, apiKey)
}

/** Real store: a single `SharedPreferences` entry holding the JSON-encoded queue. */
private class SharedPrefsBeaconStore(
    private val context: Context,
    private val json: Json,
    private val prefsName: String,
    private val key: String,
) : BeaconStore {
    override fun load(): List<PendingBeacon> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(key, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<PendingBeacon>>(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun save(queue: List<PendingBeacon>) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putString(key, json.encodeToString(queue)).apply()
    }
}
