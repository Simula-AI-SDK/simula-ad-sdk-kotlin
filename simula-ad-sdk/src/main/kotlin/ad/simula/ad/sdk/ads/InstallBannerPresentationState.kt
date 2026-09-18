package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.OverlayTiming
import ad.simula.ad.sdk.model.SkOverlayConfig
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class InstallBannerPhase { HIDDEN, SCHEDULED, VISIBLE, DISMISSED }

internal data class InstallBannerSnapshot(
    val phase: InstallBannerPhase = InstallBannerPhase.HIDDEN,
    val deadlineMs: Long? = null,
) {
    val visible: Boolean get() = phase == InstallBannerPhase.VISIBLE
}

/** Deterministic presentation-lifetime policy with no Activity or Compose ownership. */
internal class InstallBannerStateMachine(
    private val enabled: Boolean,
    private val timing: OverlayTiming,
    private val delayMs: Long,
    private val clockMs: () -> Long,
) {
    private var state = InstallBannerSnapshot()

    @Synchronized
    fun snapshot(): InstallBannerSnapshot = state

    @Synchronized
    fun start(): Boolean {
        if (!enabled || state.phase != InstallBannerPhase.HIDDEN) return false
        state = when (timing) {
            OverlayTiming.ON_CLICK -> return false
            OverlayTiming.DURING_PLAY, OverlayTiming.DELAYED -> {
                val boundedDelayMs = delayMs.coerceAtLeast(0L)
                if (boundedDelayMs == 0L) {
                    InstallBannerSnapshot(InstallBannerPhase.VISIBLE)
                } else {
                    InstallBannerSnapshot(
                        phase = InstallBannerPhase.SCHEDULED,
                        deadlineMs = monotonicDeadline(clockMs(), boundedDelayMs),
                    )
                }
            }
        }
        return true
    }

    @Synchronized
    fun onPrimaryCtaAdmitted(): Boolean {
        if (!enabled || timing != OverlayTiming.ON_CLICK || state.phase != InstallBannerPhase.HIDDEN) {
            return false
        }
        state = InstallBannerSnapshot(InstallBannerPhase.VISIBLE)
        return true
    }

    @Synchronized
    fun delayedRemainingMs(): Long? {
        if (state.phase != InstallBannerPhase.SCHEDULED) return null
        val deadline = state.deadlineMs ?: return null
        val now = clockMs()
        return if (now >= deadline) 0L else deadline - now
    }

    @Synchronized
    fun onDelayedDeadlineReached(): Boolean {
        if (state.phase != InstallBannerPhase.SCHEDULED) return false
        val deadline = state.deadlineMs ?: return false
        if (clockMs() < deadline) return false
        state = InstallBannerSnapshot(InstallBannerPhase.VISIBLE)
        return true
    }

    @Synchronized
    fun dismiss(): Boolean {
        if (!enabled || state.phase == InstallBannerPhase.DISMISSED) return false
        state = InstallBannerSnapshot(InstallBannerPhase.DISMISSED)
        return true
    }

    private fun monotonicDeadline(nowMs: Long, delayMs: Long): Long =
        if (nowMs > Long.MAX_VALUE - delayMs) Long.MAX_VALUE else nowMs + delayMs
}

/** Presentation-owned observable adapter; replacement Activities observe the same snapshot. */
internal class InstallBannerPresentationState(
    config: SkOverlayConfig?,
    clockMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val machine = InstallBannerStateMachine(
        enabled = config?.enabled == true,
        timing = config?.timing ?: OverlayTiming.ON_CLICK,
        delayMs = (config?.delaySeconds ?: 0).coerceAtLeast(0) * 1_000L,
        clockMs = clockMs,
    )

    var snapshot by mutableStateOf(machine.snapshot())
        private set

    fun start(): Boolean = update(machine::start)

    fun onPrimaryCtaAdmitted(): Boolean = update(machine::onPrimaryCtaAdmitted)

    fun delayedRemainingMs(): Long? = machine.delayedRemainingMs()

    fun onDelayedDeadlineReached(): Boolean = update(machine::onDelayedDeadlineReached)

    fun dismiss(): Boolean = update(machine::dismiss)

    private fun update(transition: () -> Boolean): Boolean {
        val changed = transition()
        if (changed) snapshot = machine.snapshot()
        return changed
    }
}
