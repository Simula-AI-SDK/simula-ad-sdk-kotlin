package ad.simula.ad.sdk.telemetry

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred

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

/** Coroutine-aware first-wins completion gate. No monitor is held while the owner works or losers wait. */
internal class FirstWinsCompletion {
    private val lock = Any()
    private var completion: CompletableDeferred<Unit>? = null

    suspend fun runOnce(block: suspend () -> Unit) {
        val entry = synchronized(lock) {
            val existing = completion
            if (existing != null) {
                Entry(owner = false, completion = existing)
            } else {
                val created = CompletableDeferred<Unit>()
                completion = created
                Entry(owner = true, completion = created)
            }
        }
        if (!entry.owner) {
            entry.completion.await()
            return
        }
        try {
            block()
        } finally {
            entry.completion.complete(Unit)
        }
    }

    private class Entry(
        val owner: Boolean,
        val completion: CompletableDeferred<Unit>,
    )
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
internal fun transferBoundedCounter(
    counter: BoundedCounter,
    recordDurably: (count: Int, onPersisted: (Boolean) -> Unit) -> Unit,
) {
    val claim = counter.claim() ?: return
    val started = runCatching {
        recordDurably(claim.count) { persisted ->
            if (persisted) {
                if (counter.acknowledge(claim)) transferBoundedCounter(counter, recordDurably)
            } else {
                counter.release(claim)
            }
        }
    }.isSuccess
    if (!started) counter.release(claim)
}

internal const val DUPLICATE_INITIALIZE_META_NAME = "duplicate_initialize"
internal val DuplicateImperativeInitializeCounter = BoundedCounter(maxCount = 1_000_000)
