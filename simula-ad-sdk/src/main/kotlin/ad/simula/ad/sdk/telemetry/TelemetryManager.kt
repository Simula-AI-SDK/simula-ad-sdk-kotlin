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
    // Resolved fresh on each flush (off the UI path). Must be best-effort/non-throwing.
    private val connectionTypeProvider: () -> String? = { null },
    // Compact diagnostics breadcrumb (memory/webview-pool/image-cache), sampled on flush. Best-effort.
    private val diagnosticsProvider: () -> String? = { null },
    enabled: Boolean = true,
    sampleRate: Double = 1.0,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = SimulaScope,
    private val random: () -> Double = { Random.nextDouble() },
    // Dev-only sink: when set (devMode), each recorded event is logged here (already redacted).
    private val debugLog: ((String) -> Unit)? = null,
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

    // Aux session state for the funnel / time-to-first-ad / experiment, guarded by a plain lock
    // (recordLifecycle is non-suspend, so it can't take the coroutine `mutex`).
    private val auxLock = Any()
    private val createdAtMs: Long = clock()
    private var firstAdRecorded = false
    // Per-format funnel counters: [filled, nofill, failed, impressions, clicks]. Reset on emit (deltas).
    private val funnel = LinkedHashMap<String, IntArray>()
    private var experimentId: String? = null
    private var variantId: String? = null

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

    fun recordOperation(
        name: String,
        durationMs: Long,
        success: Boolean,
        failureClass: String? = null,
        breadcrumb: String? = null,
    ) = enqueuePerf(
        newEvent(TYPE_OPERATION, name).copy(
            durationMs = durationMs,
            success = success,
            failureClass = failureClass,
            breadcrumb = breadcrumb,
        ),
    )

    fun recordLifecycle(
        stage: String,
        adFormat: String?,
        adUnitId: String?,
        adId: String?,
        serveId: String?,
        durationMs: Long?,
        errorCode: String?,
        trigger: String? = null,
        cacheSource: String? = null,
    ) {
        accumulate(stage, adFormat, cacheSource, errorCode)
        enqueuePerf(
            newEvent(TYPE_LIFECYCLE, name = stage).copy(
                adFormat = adFormat,
                adUnitId = adUnitId,
                adId = adId,
                serveId = serveId,
                durationMs = durationMs,
                errorCode = errorCode,
                trigger = trigger,
                cacheSource = cacheSource,
            ),
        )
    }

    /** Set the session experiment assignment for the envelope (last assignment wins). */
    fun setExperiment(experimentId: String?, variantId: String?) {
        if (experimentId.isNullOrBlank() && variantId.isNullOrBlank()) return
        synchronized(auxLock) {
            this.experimentId = experimentId
            this.variantId = variantId
        }
    }

    /**
     * Fold a lifecycle event into the per-format funnel and detect the first ad load. Unconditional
     * (not perf-sampled) so the funnel reflects real activity; cheap (a map increment under a lock).
     * Cache re-renders (`cacheSource == "cache"`) are excluded from `filled` so they don't inflate it.
     */
    private fun accumulate(stage: String, adFormat: String?, cacheSource: String?, errorCode: String?) {
        val fmt = adFormat ?: return
        val firstAd: Boolean
        synchronized(auxLock) {
            val c = funnel.getOrPut(fmt) { IntArray(5) }
            when (stage) {
                "load_success" -> if (cacheSource != "cache") c[0]++
                "load_fail" -> if (errorCode == "no_fill") c[1]++ else c[2]++
                "displayed" -> c[3]++
                "click" -> c[4]++
            }
            firstAd = stage == "load_success" && !firstAdRecorded
            if (firstAd) firstAdRecorded = true
        }
        // One-shot time-to-first-ad (init → first successful load). Emitted off the lock.
        if (firstAd) recordOperation("time_to_first_ad", clock() - createdAtMs, success = true)
    }

    /**
     * Record a handled error. [signature] is the dedup key (e.g. `domain:code`); identical
     * signatures aggregate with a count instead of flooding the buffer. [message] is
     * truncated; never pass raw URLs/tokens/PII.
     */
    fun recordError(signature: String, errorCode: String? = null, message: String? = null, breadcrumb: String? = null) {
        if (!isEnabled) return
        val event = newEvent(TYPE_ERROR, name = signature).copy(
            errorCode = errorCode,
            // Sanitize at the source so secrets are stripped from BOTH the dev log and the
            // payload sent to the backend (exception text can embed URLs/tokens).
            message = redact(message),
            breadcrumb = breadcrumb,
            count = 1,
        )
        debugLog?.invoke(formatForLog(event))
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
        // Emit the session funnel deltas + a diagnostics sample as part of this (background) flush.
        emitFunnelSummary()
        emitDiagnostics()
        scope.launch {
            mutex.withLock { store.save(snapshot()) }
            flush()
        }
    }

    /** Emit one `funnel_summary` operation per active format (cumulative since the last emit), then
     * reset — so the backend can sum the deltas without double-counting across multiple backgrounds. */
    private fun emitFunnelSummary() {
        val snapshot: List<Pair<String, IntArray>> = synchronized(auxLock) {
            if (funnel.isEmpty()) return
            val out = funnel.map { it.key to it.value }
            funnel.clear()
            out
        }
        for ((fmt, c) in snapshot) {
            val requested = c[0] + c[1] + c[2]
            recordOperation(
                name = "funnel_summary",
                durationMs = 0,
                success = true,
                breadcrumb = "fmt=$fmt;req=$requested;fill=${c[0]};nofill=${c[1]};fail=${c[2]};imp=${c[3]};clk=${c[4]}",
            )
        }
    }

    /** Sample best-effort runtime diagnostics (memory / webview-pool / image-cache) onto a meta-ish
     * operation event. No-op when the provider yields nothing. */
    private fun emitDiagnostics() {
        val line = diagnosticsProvider() ?: return
        recordOperation(name = "diagnostics", durationMs = 0, success = true, breadcrumb = line)
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun newEvent(type: String, name: String) =
        TelemetryEvent(type = type, name = name, eventId = UUID.randomUUID().toString(), timestamp = clock())

    /** Compact one-line view for the dev console. Carries only non-sensitive event fields —
     * never the envelope's apiKey/ppid/advertising-id — and the message is already redacted. */
    private fun formatForLog(e: TelemetryEvent): String = buildString {
        append(e.type).append(' ').append(e.name)
        e.statusCode?.let { append(" status=").append(it) }
        e.durationMs?.let { append(" dur=").append(it).append("ms") }
        e.failureClass?.let { append(" fail=").append(it) }
        e.requestBytes?.let { append(" reqB=").append(it) }
        e.responseBytes?.let { append(" respB=").append(it) }
        e.success?.let { append(" ok=").append(it) }
        e.cacheHit?.let { append(" cache=").append(it) }
        e.adFormat?.let { append(" fmt=").append(it) }
        e.adUnitId?.let { append(" unit=").append(it) }
        e.adId?.let { append(" ad=").append(it) }
        e.serveId?.let { append(" serve=").append(it) }
        e.errorCode?.let { append(" code=").append(it) }
        e.count?.let { append(" count=").append(it) }
        e.message?.let { append(" msg=").append(it) }
    }

    /** Strips likely secrets from free-text (URL query strings, bearer tokens, key/secret
     * assignments) and caps length. Applied to error messages before they're stored or logged. */
    private fun redact(message: String?): String? {
        if (message == null) return null
        var r = message
        r = QUERY_RE.replace(r, "?…")
        r = BEARER_RE.replace(r, "Bearer ***")
        r = SECRET_RE.replace(r) { "${it.groupValues[1]}${it.groupValues[2]}***" }
        return r.take(MAX_MESSAGE_LEN)
    }

    private fun enqueuePerf(event: TelemetryEvent) {
        if (!isEnabled || !perfSampledIn) return
        debugLog?.invoke(formatForLog(event))
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
        val events: List<TelemetryEvent>
        mutex.withLock {
            if (isFlushing) return
            if (buffer.isEmpty() && errorAgg.isEmpty()) return
            isFlushing = true
            pendingBuffer = buffer.toList()
            // Deep-ish copy of error events so the in-flight count can't drift the payload.
            val errorEvents = errorAgg.values.map { it.copy() }
            pendingErrors = errorEvents.associate { it.name to (it.count ?: 1) }
            droppedSnap = droppedCount
            // Snapshot the events under the lock (the buffers are mutable shared state); the actual
            // JSON serialization happens AFTER releasing the lock so encoding a large batch can't
            // block concurrent record/flush calls. envelope() reads only external providers
            // (session/ppid/gaid) + immutable context, none of which are guarded by this mutex.
            events = ArrayList<TelemetryEvent>(pendingBuffer.size + errorEvents.size + 1).apply {
                addAll(pendingBuffer)
                addAll(errorEvents)
                if (droppedSnap > 0) add(newEvent(TYPE_META, "dropped").copy(count = droppedSnap))
            }
        }

        // Stamp wall-clock staleness per event at flush time, then serialize outside the
        // critical section (see above). Copies leave the buffered originals untouched for retries.
        val stampClock = clock()
        val stamped = events.map { if (it.eventAgeMs == null) it.copy(eventAgeMs = stampClock - it.timestamp) else it }
        val body = json.encodeToString(envelope(stamped))

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
        connectionType = connectionTypeProvider(),
        experimentId = synchronized(auxLock) { experimentId },
        variantId = synchronized(auxLock) { variantId },
        events = events,
    )

    private companion object {
        const val MAX_BUFFER = 200
        const val FLUSH_THRESHOLD = 20
        const val FLUSH_INTERVAL_MS = 30_000L
        const val MAX_ERROR_SIGNATURES = 50
        const val MAX_MESSAGE_LEN = 300

        // Redaction patterns for free-text error messages.
        val QUERY_RE = Regex("\\?\\S*")
        val BEARER_RE = Regex("(?i)bearer\\s+\\S+")
        val SECRET_RE = Regex("(?i)(api[_-]?key|token|secret|password)([=:])\\S+")
    }
}

/** Exponential backoff for failed telemetry batches: 2s, 4s, 8s … capped at 60s. */
internal fun telemetryBackoffMs(retryCount: Int): Long {
    if (retryCount <= 0) return 0L
    return minOf((2.0.pow(retryCount) * 1000.0).toLong(), 60_000L)
}
