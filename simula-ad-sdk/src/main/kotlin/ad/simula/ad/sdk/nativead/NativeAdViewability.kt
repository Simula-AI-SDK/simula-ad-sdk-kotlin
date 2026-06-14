package ad.simula.ad.sdk.nativead

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest

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
 * Implementation: [onGloballyPositioned] recomputes the visible fraction on every layout/scroll
 * change and collapses it to a boolean (≥ [thresholdFraction]). A coroutine watches that boolean and
 * arms a [minVisibleMillis] dwell timer; `distinctUntilChanged` + `collectLatest` mean the timer runs
 * uninterrupted while the slot stays visible and is cancelled (reset) the instant it drops below
 * threshold. Fires at most once. The work runs only while the slot is composed, so a scrolled-away
 * slot costs nothing.
 */
internal fun Modifier.trackNativeAdViewability(
    enabled: Boolean,
    thresholdFraction: Float = 0.5f,
    minVisibleMillis: Long = 1_000L,
    onImpression: () -> Unit,
): Modifier = composed {
    val currentOnImpression by rememberUpdatedState(onImpression)
    val visible = remember { mutableStateOf(false) }

    LaunchedEffect(enabled, thresholdFraction, minVisibleMillis) {
        if (!enabled) return@LaunchedEffect
        var fired = false
        snapshotFlow { visible.value }
            .distinctUntilChanged()
            .collectLatest { isVisible ->
                if (isVisible && !fired) {
                    // Held continuously? A drop below threshold cancels this block (collectLatest),
                    // so reaching the end means ≥threshold for the full dwell window.
                    delay(minVisibleMillis)
                    fired = true
                    currentOnImpression()
                }
            }
    }

    onGloballyPositioned { coords ->
        visible.value = enabled && visibleFraction(coords) >= thresholdFraction
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
