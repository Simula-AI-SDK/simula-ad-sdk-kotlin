package ad.simula.ad.sdk.telemetry

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** First-wins process origin used for monotonic time since either public SDK entry point. */
internal class MonotonicSdkEntryOrigin(
    private val nanoTime: () -> Long,
) {
    private val originNanos = AtomicReference<Long?>(null)

    fun markEntry(): Long {
        val candidate = nanoTime()
        originNanos.compareAndSet(null, candidate)
        return originNanos.get() ?: candidate
    }

    fun elapsedMs(): Long? {
        val origin = originNanos.get() ?: return null
        return ((nanoTime() - origin).coerceAtLeast(0L) / NANOS_PER_MILLISECOND)
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal val ProcessSdkEntryOrigin = MonotonicSdkEntryOrigin(System::nanoTime)

/**
 * First-wins process task. The first block is reserved lazily in [scope], so cancelling a caller
 * only abandons that caller's wait; later callers still start/await the same process-owned work.
 */
internal class FirstWinsProcessTask<T>(
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var task: Deferred<Result<T>>? = null

    /** Atomically reserve the first block without starting it. This performs no suspension or I/O. */
    fun claim(block: suspend () -> T): FirstWinsProcessTaskClaim<T> {
        val shared = synchronized(lock) {
            task ?: scope.async(start = CoroutineStart.LAZY) { runCatching { block() } }.also { task = it }
        }
        return FirstWinsProcessTaskClaim(shared)
    }

    suspend fun runOnce(block: suspend () -> T): T = claim(block).startAndAwait()
}

internal class FirstWinsProcessTaskClaim<T> internal constructor(
    private val task: Deferred<Result<T>>,
) {
    /** Start outside the owner's monitor. The task remains owned by the process scope. */
    fun start() {
        task.start()
    }

    /** Await the shared first-wins result without holding a monitor. */
    suspend fun await(): T = task.await().getOrThrow()

    suspend fun startAndAwait(): T {
        start()
        return await()
    }
}

/** Saturating process-local count so duplicate calls cannot create unbounded telemetry state. */
internal class BoundedCounter(
    private val maxCount: Int,
) {
    private var pending = 0
    private var claimedCount = 0
    private var claimedToken = 0L
    private var nextToken = 1L

    @Synchronized
    fun increment() {
        if (pending + claimedCount < maxCount) pending++
    }

    @Synchronized
    fun claim(): BoundedCounterClaim? {
        if (claimedCount > 0 || pending <= 0) return null
        val token = nextToken++
        claimedToken = token
        claimedCount = pending
        pending = 0
        return BoundedCounterClaim(token, claimedCount)
    }

    @Synchronized
    fun acknowledge(claim: BoundedCounterClaim): Boolean {
        if (claim.token != claimedToken || claim.count != claimedCount) return false
        claimedToken = 0L
        claimedCount = 0
        return true
    }

    @Synchronized
    fun release(claim: BoundedCounterClaim): Boolean {
        if (claim.token != claimedToken || claim.count != claimedCount) return false
        pending = (pending.toLong() + claimedCount).coerceAtMost(maxCount.toLong()).toInt()
        claimedToken = 0L
        claimedCount = 0
        return true
    }

    @Synchronized
    internal fun pendingCount(): Int = pending
}

internal class BoundedCounterClaim internal constructor(
    internal val token: Long,
    val count: Int,
)

/**
 * Move one counter claim into durable telemetry. The claim remains owned by the counter until the
 * recorder confirms persistence; increments arriving meanwhile remain pending and drain next.
 */
internal enum class TelemetryPersistenceOutcome {
    Persisted,
    RetryableFailure,
    Disabled,
    Unavailable,
}

/**
 * Moves bounded process counters into durable telemetry and owns retry scheduling. At most one
 * recovery delay is active; retryable storage failures release the exact claim and retry without
 * requiring another public initialize call. Disabled/unavailable pipelines retain the bounded
 * count but deliberately do not loop.
 */
internal class BoundedCounterRecoveryDrain(
    private val counter: BoundedCounter,
    private val scope: CoroutineScope,
    private val sleep: suspend (Long) -> Unit,
    private val backoffMs: (Int) -> Long = ::telemetryBackoffMs,
    private val maxAutomaticAttempts: Int = MAX_DUPLICATE_PERSISTENCE_RECOVERY_ATTEMPTS,
    private val recordDurably: (count: Int, onPersisted: (TelemetryPersistenceOutcome) -> Unit) -> Unit,
) {
    private val lock = Any()
    private var recoveryScheduled = false
    private var automaticAttempts = 0
    private var cycleExhausted = false

    fun drain() {
        synchronized(lock) {
            if (recoveryScheduled) return
            if (cycleExhausted) {
                cycleExhausted = false
                automaticAttempts = 0
            }
        }
        drainAttempt()
    }

    private fun drainAttempt() {
        val claim = counter.claim() ?: return
        val started = runCatching {
            recordDurably(claim.count) { outcome ->
                when (outcome) {
                    TelemetryPersistenceOutcome.Persisted -> {
                        if (counter.acknowledge(claim)) {
                            synchronized(lock) {
                                automaticAttempts = 0
                                cycleExhausted = false
                            }
                            drain()
                        }
                    }
                    TelemetryPersistenceOutcome.RetryableFailure -> {
                        if (counter.release(claim)) scheduleRecovery()
                    }
                    TelemetryPersistenceOutcome.Disabled,
                    TelemetryPersistenceOutcome.Unavailable,
                    -> if (counter.release(claim)) stopAutomaticCycle()
                }
            }
        }.isSuccess
        if (!started && counter.release(claim)) scheduleRecovery()
    }

    private fun scheduleRecovery() {
        val delayMs = synchronized(lock) {
            if (recoveryScheduled) return
            if (automaticAttempts >= maxAutomaticAttempts) {
                cycleExhausted = true
                return
            }
            recoveryScheduled = true
            automaticAttempts++
            backoffMs(automaticAttempts)
        }
        scope.launch {
            sleep(delayMs)
            synchronized(lock) { recoveryScheduled = false }
            drainAttempt()
        }
    }

    private fun stopAutomaticCycle() {
        synchronized(lock) { cycleExhausted = true }
    }
}

internal const val MAX_DUPLICATE_PERSISTENCE_RECOVERY_ATTEMPTS = 3
internal const val DUPLICATE_INITIALIZE_META_NAME = "duplicate_initialize"
internal val DuplicateImperativeInitializeCounter = BoundedCounter(maxCount = 1_000_000)
