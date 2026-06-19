package ad.simula.ad.sdk.nativead

import android.graphics.Rect as AndroidRect
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
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
    onVisibilityRatio: ((Float) -> Unit)? = null,
    onImpression: (ViewabilityStats) -> Unit,
): Modifier = composed {
    val currentOnImpression by rememberUpdatedState(onImpression)
    val currentOnVisibility by rememberUpdatedState(onVisibilityRatio)
    // Latest layout geometry, refreshed by onGloballyPositioned and sampled by the loop below.
    val coords = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // The Compose host view. Its on-screen location reflects scrolling done by a NON-Compose
    // ancestor (e.g. a React Native list), which findRootCoordinates() cannot observe.
    val hostView = LocalView.current
    // Per-slot scratch buffers, reused across every sample so the ~6.7Hz poll allocates nothing while
    // the slot is on screen. Safe to share: the loop runs single-threaded on the main dispatcher and
    // both Android calls below overwrite the full contents each sample.
    val hostLoc = remember { IntArray(2) }
    val windowRect = remember { AndroidRect() }

    LaunchedEffect(enabled, thresholdFraction, minVisibleMillis) {
        if (!enabled) return@LaunchedEffect
        var fired = false
        // Accrue dwell only while the host is foregrounded: a backgrounded window isn't viewable, and
        // repeatOnLifecycle cancels the loop on background (so a fresh dwell restarts on return — the
        // same foreground-only gating the interstitial/rewarded timers use). The loop keeps running
        // AFTER the impression fires (dwell flips to null) purely to forward the visible fraction to
        // the creative via [onVisibilityRatio], so a video/animation can keep reacting to scroll.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var dwell = if (fired) null else ViewabilityDwell(thresholdFraction, minVisibleMillis)
            // Exposure aggregate for the once-per-impression `viewability` telemetry, accrued over this
            // foreground run up to the impression fire. Reset on each RESUMED re-entry (matches the dwell).
            val measureStartMs = SystemClock.elapsedRealtime()
            var samples = 0
            var sumFraction = 0f
            var peakFraction = 0f
            var totalVisibleMs = 0L
            while (true) {
                delay(ViewabilityDwell.SAMPLE_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                val fraction = coords.value?.let { visibleFraction(it, hostView, hostLoc, windowRect) } ?: 0f
                currentOnVisibility?.invoke(fraction)
                val activeDwell = dwell ?: continue // already fired → just keep forwarding visibility
                samples++
                sumFraction += fraction
                if (fraction > peakFraction) peakFraction = fraction
                if (fraction > 0f) totalVisibleMs += ViewabilityDwell.SAMPLE_INTERVAL_MS
                if (activeDwell.sample(fraction, now)) {
                    fired = true
                    dwell = null // stop feeding the dwell; the loop keeps forwarding visibility
                    currentOnImpression(
                        ViewabilityStats(
                            timeToViewableMs = now - measureStartMs,
                            peakExposure = peakFraction,
                            avgExposure = if (samples > 0) sumFraction / samples else 0f,
                            totalVisibleMs = totalVisibleMs,
                        ),
                    )
                }
            }
        }
    }

    onGloballyPositioned {
        coords.value = it
        // Forward visibility on every layout/scroll frame: a Compose LazyColumn repositions this node
        // each frame, so this is the per-frame signal. The 150ms loop above additionally covers scroll
        // driven by a NON-Compose ancestor (a React Native list), which never repositions this node.
        // Both feed the same throttling relay downstream, so duplicate values cost nothing.
        currentOnVisibility?.invoke(visibleFraction(it, hostView, hostLoc, windowRect))
    }
}

/**
 * Once-per-impression viewability exposure aggregate, measured over the continuous foreground run that
 * led to the impression. [timeToViewableMs] is from when measurement began (creative painted /
 * foreground entry) to the impression fire; [peakExposure] / [avgExposure] are 0..1; [totalVisibleMs]
 * is the cumulative time any part of the slot was on screen. Streamed visibility is far too
 * high-volume for the batch telemetry pipeline, so this MRC-shaped summary is what's recorded instead.
 */
internal data class ViewabilityStats(
    val timeToViewableMs: Long,
    val peakExposure: Float,
    val avgExposure: Float,
    val totalVisibleMs: Long,
)

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
 * Fraction (0..1) of [coords]' area currently visible on screen. Measured in SCREEN pixels — the
 * `host`'s on-screen location plus the slot's offset within the Compose root — then intersected with
 * the visible window rect. Area-based, matching OMID's "≥50% of the creative".
 *
 * Why screen-relative rather than `findRootCoordinates`-relative: a `NativeAdSlot` may be hosted in
 * its own `ComposeView` whose scrolling is owned by a NON-Compose ancestor (e.g. a React Native
 * `FlatList`). The Compose root is then that per-slot host, which the slot always fills — so a
 * root-relative fraction reads ~1.0 even while the row is scrolled off screen, firing the impression
 * for never-seen rows. `View.getLocationOnScreen` DOES reflect that ancestor's scroll, so anchoring
 * to the screen is correct for both a Compose `LazyColumn` and a React Native list.
 */
private fun visibleFraction(
    coords: LayoutCoordinates,
    host: View,
    hostLoc: IntArray,
    window: AndroidRect,
): Float {
    if (!coords.isAttached) return 0f
    val w = coords.size.width
    val h = coords.size.height
    if (w == 0 || h == 0) return 0f

    // Slot's top-left in screen pixels: the host's on-screen origin + the slot's offset in the root.
    // hostLoc/window are caller-owned scratch buffers, fully overwritten by the two calls below.
    host.getLocationOnScreen(hostLoc)
    val offsetInRoot = coords.positionInRoot()
    val slotLeft = hostLoc[0] + offsetInRoot.x
    val slotTop = hostLoc[1] + offsetInRoot.y
    val slotRight = slotLeft + w
    val slotBottom = slotTop + h

    // Visible window rect in screen pixels (excludes the status bar / system insets).
    host.getWindowVisibleDisplayFrame(window)

    val visibleW = (slotRight.coerceAtMost(window.right.toFloat()) -
        slotLeft.coerceAtLeast(window.left.toFloat())).coerceAtLeast(0f)
    val visibleH = (slotBottom.coerceAtMost(window.bottom.toFloat()) -
        slotTop.coerceAtLeast(window.top.toFloat())).coerceAtLeast(0f)
    return ((visibleW * visibleH) / (w.toFloat() * h.toFloat())).coerceIn(0f, 1f)
}
