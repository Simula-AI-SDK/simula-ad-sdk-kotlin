package ad.simula.ad.sdk.ads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hard bound between `startActivity` returning and the ad Activity claiming its handoff. */
internal const val LAUNCH_WATCHDOG_MS = 3_000L

/**
 * Arms the dropped-launch watchdog. On Android 10+ a background activity start is silently
 * DROPPED (no throw), so a "successful" `startActivity` can leave the launch token forever
 * unclaimed: the ad would sit in its Showing state (bricked — every later `load()`/`show()`
 * no-ops or reports `AlreadyShowing`) and the presentation, which retains the host's listener
 * through the callback bridge, would leak in the handoff for the process lifetime.
 *
 * After [timeoutMs], if the Activity still hasn't claimed the handoff ([isClaimed] is false),
 * [onDropped] runs exactly once — ON THE MAIN THREAD. The claim is RE-CHECKED on main before
 * the cleanup: the Activity claims its handoff in `onCreate` (main thread), so a claim that
 * landed after this scope's (background) first check but before the cleanup must win —
 * otherwise a live, visible presentation would be torn down and the host told the show
 * failed. The double-check also makes [timeoutMs] safe under a stalled main looper: a
 * >3 s stall queues the cleanup BEHIND the pending `onCreate`, which claims first and
 * neutralizes the cleanup.
 */
internal fun CoroutineScope.armLaunchWatchdog(
    timeoutMs: Long,
    isClaimed: () -> Boolean,
    onDropped: suspend () -> Unit,
) = launch {
    delay(timeoutMs)
    if (!isClaimed()) {
        withContext(Dispatchers.Main) {
            if (!isClaimed()) onDropped()
        }
    }
}
