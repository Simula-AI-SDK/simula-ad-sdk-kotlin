package ad.simula.ad.sdk.nativead

import android.os.SystemClock
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * OMID-shaped viewability seam for native ads.
 *
 * The PRD mandates IAB OMID for impression counting (≥50% of the creative visible for ≥1 continuous
 * second; the timer resets if it scrolls out before 1s). The certified OMID binary is distributed
 * through IAB Tech Lab membership and isn't wired yet, so this is the stopgap that reproduces those
 * exact fire semantics with zero added dependencies.
 *
 * ## OMID swap point
 * When the certified SDK lands, replace the visibility math + the single [onImpression] fire here
 * with an `OMIDAdSession` scoped to the native WebView container: `nativeOwner` access mode,
 * `display` impression type, partner = Simula. The contract callers rely on is unchanged — exactly
 * one fire when the threshold is met — and the slot already co-fires `onImpression` with
 * `trackImpression` off the same call, satisfying the PRD's "co-fire, do not decouple" rule.
 *
 * ## Implementation
 * [onGloballyPositioned] only *caches* the slot's latest geometry; a foreground-gated loop **samples**
 * it on a fixed cadence and feeds the visible fraction to a [ViewabilityDwell] state machine that
 * owns the ≥threshold + ≥dwell decision. Polling (rather than firing straight from the layout
 * callback) is deliberate: layout/scroll callbacks are not a reliable "is it on screen right now"
 * signal — an overlay or dialog dismissing, a sibling resizing, a parent fade, or the [enabled] flip
 * landing without a fresh layout can all change effective visibility without ever repositioning *this*
 * node, so an event-only gate silently under-fires. Sampling also lets [ViewabilityDwell] apply
 * hysteresis around the threshold so a creative parked right at 50% can't flip in/out on sub-pixel
 * jitter and keep resetting the dwell. Fires at most once. `repeatOnLifecycle(RESUMED)` means a
 * backgrounded window accrues nothing (and breaks continuity), and a scrolled-away slot that leaves
 * composition cancels the loop entirely, so it costs nothing.
 */
internal fun Modifier.trackNativeAdViewability(
    enabled: Boolean,
    thresholdFraction: Float = 0.5f,
    minVisibleMillis: Long = 1_000L,
    onImpression: () -> Unit,
): Modifier = composed {
    val currentOnImpression by rememberUpdatedState(onImpression)
    // Latest layout geometry, refreshed by onGloballyPositioned and sampled by the loop below.
    val coords = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(enabled, thresholdFraction, minVisibleMillis) {
        if (!enabled) return@LaunchedEffect
        var fired = false
        // Accrue dwell only while the host is foregrounded: a backgrounded window isn't viewable, and
        // repeatOnLifecycle cancels the loop on background (so a fresh dwell restarts on return — the
        // same foreground-only gating the interstitial/rewarded timers use).
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (fired) return@repeatOnLifecycle
            val dwell = ViewabilityDwell(thresholdFraction, minVisibleMillis)
            while (true) {
                delay(ViewabilityDwell.SAMPLE_INTERVAL_MS)
                val fraction = coords.value?.let(::visibleFraction) ?: 0f
                if (dwell.sample(fraction, SystemClock.elapsedRealtime())) {
                    fired = true
                    currentOnImpression()
                    return@repeatOnLifecycle
                }
            }
        }
    }

    onGloballyPositioned { coords.value = it }
}

/**
 * Pure viewability dwell state machine: feed it visibility samples over time and it reports the single
 * moment the slot has been continuously viewable for [minVisibleMillis]. No Compose dependency, so the
 * threshold + dwell + hysteresis logic is unit-testable in isolation.
 *
 * Hysteresis: a sample becomes viewable once the visible fraction reaches [enterFraction], and stays
 * viewable until it falls below `enterFraction * `[EXIT_RATIO]. Without this band a creative parked
 * right at the threshold would flip in and out on sub-pixel layout jitter and keep resetting the
 * dwell, so the impression would never fire.
 */
internal class ViewabilityDwell(
    private val enterFraction: Float,
    private val minVisibleMillis: Long,
) {
    private var viewable = false
    private var viewableSinceMs = -1L

    /**
     * Record a [fraction] (0..1) visible sample taken at [nowMs] (a monotonic clock, e.g.
     * [SystemClock.elapsedRealtime]). Returns true exactly when continuous viewable time first reaches
     * [minVisibleMillis] — the caller fires the impression once and stops sampling. A drop below the
     * exit band resets the dwell so the next viewable run starts counting from zero.
     */
    fun sample(fraction: Float, nowMs: Long): Boolean {
        viewable = if (viewable) fraction >= enterFraction * EXIT_RATIO else fraction >= enterFraction
        if (!viewable) {
            viewableSinceMs = -1L
            return false
        }
        if (viewableSinceMs < 0L) viewableSinceMs = nowMs
        return nowMs - viewableSinceMs >= minVisibleMillis
    }

    companion object {
        /** Exit threshold as a fraction of the enter threshold (50% enter → 40% exit). */
        const val EXIT_RATIO = 0.8f

        /** How often the composable samples geometry while the slot is on screen. Fine enough that
         *  the ≥1s dwell is measured to ~1 frame, coarse enough to be negligible while idle. */
        const val SAMPLE_INTERVAL_MS = 150L
    }
}

/**
 * Fraction (0..1) of [coords]' area currently inside the visible window, clipped by every ancestor
 * (so a scrolling list's viewport bounds are honored). Area-based so it matches OMID's "≥50% of the
 * creative" rather than a pure vertical heuristic.
 */
private fun visibleFraction(coords: LayoutCoordinates): Float {
    if (!coords.isAttached) return 0f
    val w = coords.size.width
    val h = coords.size.height
    if (w == 0 || h == 0) return 0f

    val root = coords.findRootCoordinates()
    // Bounding box of the slot in root coordinates, clipped by ancestor clip bounds (the list).
    val box = root.localBoundingBoxOf(coords, clipBounds = true)
    val rootW = root.size.width.toFloat()
    val rootH = root.size.height.toFloat()

    // Intersect once more with the root's own visible bounds (the window).
    val left = box.left.coerceAtLeast(0f)
    val top = box.top.coerceAtLeast(0f)
    val right = box.right.coerceAtMost(rootW)
    val bottom = box.bottom.coerceAtMost(rootH)

    val visibleW = (right - left).coerceAtLeast(0f)
    val visibleH = (bottom - top).coerceAtLeast(0f)
    return ((visibleW * visibleH) / (w.toFloat() * h.toFloat())).coerceIn(0f, 1f)
}
