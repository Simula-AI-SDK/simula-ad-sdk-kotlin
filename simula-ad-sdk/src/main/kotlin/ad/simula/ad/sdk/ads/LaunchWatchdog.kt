package ad.simula.ad.sdk.ads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * [onDropped] runs exactly once. A genuine launch claims the token in `onCreate` well within
 * the window; a dropped launch never does.
 */
internal fun CoroutineScope.armLaunchWatchdog(
    timeoutMs: Long,
    isClaimed: () -> Boolean,
    onDropped: suspend () -> Unit,
) = launch {
    delay(timeoutMs)
    if (!isClaimed()) onDropped()
}
