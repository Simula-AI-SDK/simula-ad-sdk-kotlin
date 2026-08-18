package ad.simula.ad.sdk.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Suspends launch-adjacent network work until the host process has had a quiet window. */
internal fun interface LaunchSettledGate {
    suspend fun awaitSettled()

    companion object {
        /** Test/default seam: callers that do not opt into launch gating remain immediate. */
        val Open = LaunchSettledGate { }
    }
}

internal const val LAUNCH_QUIET_WINDOW_MS = 5_000L

/**
 * One-shot launch gate. Its timer starts at construction, never blocks the caller, and remains
 * permanently open after settling so late calls do not pay another delay.
 */
internal class DelayedLaunchSettledGate(
    scope: CoroutineScope = SimulaScope,
    quietWindowMs: Long = LAUNCH_QUIET_WINDOW_MS,
    sleeper: suspend (Long) -> Unit = { delay(it) },
) : LaunchSettledGate {
    private val settled = CompletableDeferred<Unit>()

    init {
        scope.launch {
            runCatching { sleeper(quietWindowMs.coerceAtLeast(0L)) }
            settled.complete(Unit)
        }
    }

    override suspend fun awaitSettled() {
        settled.await()
    }
}

/** The one production launch timer shared by imperative startup and declarative IPv4 calls. */
internal object ProcessLaunchSettledGate : LaunchSettledGate {
    private val delegate = DelayedLaunchSettledGate()

    override suspend fun awaitSettled() {
        delegate.awaitSettled()
    }
}
