package ad.simula.ad.sdk.telemetry

import ad.simula.ad.sdk.core.SimulaScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.pow
import kotlin.random.Random

/**
 * Static per-process/device context attached to every telemetry batch. Android-free
 * (the facade builds it from `Build`/`Context`) so the engine stays JVM-testable.
 */
internal data class TelemetryContext(
    val sdkVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val hostAppId: String,
    val devMode: Boolean,
    val platform: String = "android",
)

/**
 * Batches handled-error + performance telemetry and delivers it to the Simula backend,
 * off the UI path. Models the durable, conflict-free design of
 * [ad.simula.ad.sdk.network.RewardVerificationQueue]:
 *
 * - **Durable**: the buffer is persisted via [TelemetryStore]; errors persist immediately
 *   (most likely to precede process death), perf events on each flush. Recovered on start.
 * - **Batched**: flushes at [FLUSH_THRESHOLD] events, every [FLUSH_INTERVAL_MS], or eagerly
 *   on an error. Failed batches retry with exponential backoff.
 * - **Bounded**: the buffer caps at [MAX_BUFFER] (oldest dropped) and distinct error
 *   signatures at [MAX_ERROR_SIGNATURES]; both surface a `dropped` meta event rather than
 *   silently truncating.
 * - **Sampled / killable**: perf is sampled per session at [sampleRate]; the whole pipeline
 *   honors [enabled] (host opt-out always wins; the server can additionally disable it).
 *
 * Collaborators (store, sender, clock, scope, random, providers) are injected so the
 * engine is exercised with deterministic virtual time and in-memory fakes.
 */
internal class TelemetryManager(
    private val ctx: TelemetryContext,
    private val store: TelemetryStore,
    private val sender: TelemetrySender,
    private val sessionIdProvider: () -> String?,
    private val primaryUserIdProvider: () -> String?,
    private val advertisingIdProvider: () -> String?,
    enabled: Boolean = true,
    sampleRate: Double = 1.0,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = SimulaScope,
    private val random: () -> Double = { Random.nextDouble() },
) {
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    private val mutex = Mutex()
    // Perf/network/lifecycle/operation events, FIFO.
    private val buffer = ArrayDeque<TelemetryEvent>()
    // Handled errors aggregated by signature (the event `name`) within the flush window.
    private val errorAgg = LinkedHashMap<String, TelemetryEvent>()
    private var droppedCount = 0
    private var isFlushing = false
    private var flushScheduled = false
    private var retryCount = 0

    @Volatile private var isEnabled: Boolean = enabled
    @Volatile private var perfSampledIn: Boolean = enabled && random() < sampleRate

    /** Recover any buffer left by a prior process, then attempt a flush. */
    fun start() {
        scope.launch {
            mutex.withLock {
                for (e in store.load()) {
                    if (e.type == TYPE_ERROR && e.name.isNotEmpty()) errorAgg[e.name] = e
                    else buffer.addLast(e)
                }
            }
            flush()
        }
    }

    /**
     * Apply a server-side telemetry directive (from `/session/create`). [enabled] false is a
     * runtime kill-switch; [sampleRate] re-rolls perf sampling for the rest of the session.
     * Has no effect once the host opted out (this manager would not exist).
     */
    fun applyServerConfig(enabled: Boolean, sampleRate: Double) {
        isEnabled = enabled
        perfSampledIn = enabled && random() < sampleRate.coerceIn(0.0, 1.0)
    }

    // ── Record entry points (cheap; offload to the scope) ──────────────────────

    fun recordNetwork(
        path: String,
        method: String,
        statusCode: Int?,
        durationMs: Long,
        requestBytes: Long,
        responseBytes: Long,
        failureClass: String?,
    ) = enqueuePerf(
        newEvent(TYPE_NETWORK, name = "$method $path").copy(
            statusCode = statusCode,
            durationMs = durationMs,
            requestBytes = requestBytes,
            responseBytes = responseBytes,
            failureClass = failureClass,
        ),
    )

    fun recordOperation(name: String, durationMs: Long, success: Boolean) =
        enqueuePerf(newEvent(TYPE_OPERATION, name).copy(durationMs = durationMs, success = success))

    fun recordLifecycle(
        stage: String,
        adFormat: String?,
        adUnitId: String?,
        adId: String?,
        serveId: String?,
        durationMs: Long?,
        errorCode: String?,
    ) = enqueuePerf(
        newEvent(TYPE_LIFECYCLE, name = stage).copy(
            adFormat = adFormat,
            adUnitId = adUnitId,
            adId = adId,
            serveId = serveId,
            durationMs = durationMs,
            errorCode = errorCode,
        ),
    )

    /**
     * Record a handled error. [signature] is the dedup key (e.g. `domain:code`); identical
     * signatures aggregate with a count instead of flooding the buffer. [message] is
     * truncated; never pass raw URLs/tokens/PII.
     */
    fun recordError(signature: String, errorCode: String? = null, message: String? = null, breadcrumb: String? = null) {
        if (!isEnabled) return
        val event = newEvent(TYPE_ERROR, name = signature).copy(
            errorCode = errorCode,
            message = message?.take(MAX_MESSAGE_LEN),
            breadcrumb = breadcrumb,
            count = 1,
        )
        scope.launch {
            mutex.withLock {
                val existing = errorAgg[signature]
                when {
                    existing != null -> existing.count = (existing.count ?: 1) + 1
                    errorAgg.size < MAX_ERROR_SIGNATURES -> errorAgg[signature] = event
                    else -> droppedCount++
                }
                store.save(snapshot()) // errors are durable immediately
            }
            flush() // eager — an error may immediately precede a crash/kill
        }
    }

    /** Persist + attempt a flush now (e.g. on app background). */
    fun flushNow() {
        scope.launch {
            mutex.withLock { store.save(snapshot()) }
            flush()
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun newEvent(type: String, name: String) =
        TelemetryEvent(type = type, name = name, eventId = UUID.randomUUID().toString(), timestamp = clock())

    private fun enqueuePerf(event: TelemetryEvent) {
        if (!isEnabled || !perfSampledIn) return
        scope.launch {
            val shouldFlush = mutex.withLock {
                buffer.addLast(event)
                while (buffer.size > MAX_BUFFER) {
                    buffer.removeFirst()
                    droppedCount++
                }
                buffer.size >= FLUSH_THRESHOLD
            }
            if (shouldFlush) flush() else scheduleTimedFlush()
        }
    }

    /** Buffer + aggregated errors as one list for persistence / recovery. */
    private fun snapshot(): List<TelemetryEvent> = buffer.toList() + errorAgg.values.toList()

    private suspend fun flush() {
        val pendingBuffer: List<TelemetryEvent>
        val pendingErrors: Map<String, Int>
        val droppedSnap: Int
        val body: String
        mutex.withLock {
            if (isFlushing) return
            if (buffer.isEmpty() && errorAgg.isEmpty()) return
            isFlushing = true
            pendingBuffer = buffer.toList()
            // Deep-ish copy of error events so the in-flight count can't drift the payload.
            val errorEvents = errorAgg.values.map { it.copy() }
            pendingErrors = errorEvents.associate { it.name to (it.count ?: 1) }
            droppedSnap = droppedCount
            val events = ArrayList<TelemetryEvent>(pendingBuffer.size + errorEvents.size + 1).apply {
                addAll(pendingBuffer)
                addAll(errorEvents)
                if (droppedSnap > 0) add(newEvent(TYPE_META, "dropped").copy(count = droppedSnap))
            }
            body = json.encodeToString(envelope(events))
        }

        val ack = try {
            sender.send(body)
        } catch (_: Exception) {
            TelemetryAck.RETRY
        }

        val reFlush = withContext(NonCancellable) {
            mutex.withLock {
                when (ack) {
                    TelemetryAck.ACCEPTED, TelemetryAck.DROP -> {
                        val ids = pendingBuffer.mapTo(HashSet()) { it.eventId }
                        buffer.removeAll { it.eventId in ids }
                        for ((sig, cnt) in pendingErrors) {
                            val cur = errorAgg[sig] ?: continue
                            val remaining = (cur.count ?: 0) - cnt
                            if (remaining <= 0) errorAgg.remove(sig) else cur.count = remaining
                        }
                        droppedCount = (droppedCount - droppedSnap).coerceAtLeast(0)
                        retryCount = 0
                        store.save(snapshot())
                        isFlushing = false
                        // Re-drain leftovers that arrived mid-send: perf only once it re-hits the
                        // batch threshold (stay batchy), but errors promptly (low-volume, valuable).
                        buffer.size >= FLUSH_THRESHOLD || errorAgg.isNotEmpty()
                    }

                    TelemetryAck.RETRY -> {
                        store.save(snapshot())
                        retryCount++
                        isFlushing = false
                        false
                    }
                }
            }
        }

        if (ack == TelemetryAck.RETRY) scheduleRetry() else if (reFlush) flush()
    }

    private fun scheduleTimedFlush() {
        scope.launch {
            val go = mutex.withLock { if (flushScheduled) false else { flushScheduled = true; true } }
            if (!go) return@launch
            delay(FLUSH_INTERVAL_MS)
            mutex.withLock { flushScheduled = false }
            flush()
        }
    }

    private fun scheduleRetry() {
        scope.launch {
            delay(telemetryBackoffMs(retryCount))
            flush()
        }
    }

    private fun envelope(events: List<TelemetryEvent>) = TelemetryEnvelope(
        sdkVersion = ctx.sdkVersion,
        platform = ctx.platform,
        osVersion = ctx.osVersion,
        deviceModel = ctx.deviceModel,
        hostAppId = ctx.hostAppId,
        devMode = ctx.devMode,
        sessionId = sessionIdProvider(),
        // PII providers are already consent-gated by the facade (re-checked at send time).
        primaryUserId = primaryUserIdProvider(),
        advertisingId = advertisingIdProvider(),
        events = events,
    )

    private companion object {
        const val MAX_BUFFER = 200
        const val FLUSH_THRESHOLD = 20
        const val FLUSH_INTERVAL_MS = 30_000L
        const val MAX_ERROR_SIGNATURES = 50
        const val MAX_MESSAGE_LEN = 300
    }
}

/** Exponential backoff for failed telemetry batches: 2s, 4s, 8s … capped at 60s. */
internal fun telemetryBackoffMs(retryCount: Int): Long {
    if (retryCount <= 0) return 0L
    return minOf((2.0.pow(retryCount) * 1000.0).toLong(), 60_000L)
}
