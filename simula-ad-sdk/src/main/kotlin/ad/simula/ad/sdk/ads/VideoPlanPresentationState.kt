package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.SkOverlayConfig
import ad.simula.ad.sdk.model.VideoAudioSessionState
import ad.simula.ad.sdk.model.VideoInstallOverlayClock
import ad.simula.ad.sdk.telemetry.Telemetry
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay

internal data class VideoTelemetryContext(
    val adFormat: String,
    val adUnitId: String?,
    val adId: String?,
    val serveId: String?,
    val impressionId: String?,
    val style: String?,
    val skoverlayEnabled: Boolean?,
    val skoverlayDelaySeconds: Int?,
    val clipIndex: Int?,
    val pool: String?,
)

internal data class VideoPlaybackTelemetry(
    val context: VideoTelemetryContext,
    val videoPositionS: Double,
    val muted: Boolean,
    val durationS: Double?,
    val watchedS: Double,
    val secondsUnmuted: Double,
    val secondsMuted: Double,
) {
    fun record(
        stage: String,
        quartile: Int? = null,
        reason: String? = null,
        pausedMs: Double? = null,
        msToNextStepReady: Double? = null,
        secondsSinceVideoStart: Double? = null,
        on: String? = null,
        visibleS: Double? = null,
        error: String? = null,
    ) {
        Telemetry.recordVideoLifecycle(
            stage = stage,
            adFormat = context.adFormat,
            adUnitId = context.adUnitId,
            adId = context.adId,
            serveId = context.serveId,
            impressionId = context.impressionId,
            style = context.style,
            skoverlayEnabled = context.skoverlayEnabled,
            skoverlayDelaySeconds = context.skoverlayDelaySeconds,
            clipIndex = context.clipIndex,
            videoPositionS = videoPositionS,
            muted = muted,
            pool = context.pool,
            durationS = durationS,
            quartile = quartile,
            reason = reason,
            pausedMs = pausedMs,
            watchedS = watchedS,
            secondsUnmuted = secondsUnmuted,
            secondsMuted = secondsMuted,
            msToNextStepReady = msToNextStepReady,
            secondsSinceVideoStart = secondsSinceVideoStart,
            on = on,
            visibleS = visibleS,
            error = error,
        )
    }
}

internal data class VideoHandoffTiming(
    val msToNextStepReady: Double,
    val secondsSinceVideoStart: Double,
    val on: String = "next_step",
)

/** State shared by every slot in one resolved V2 presentation. */
internal class VideoPlanPresentationState(
    videoPlanV2: Boolean = false,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
) {
    val audio = VideoAudioSessionState(videoPlanV2)
    private val overlayClock = VideoInstallOverlayClock()
    var active by mutableStateOf(videoPlanV2)
        private set
    var overlayStarted by mutableStateOf(false)
        private set
    private var overlayFinished = false
    private var foreground = false
    private var blocked = true
    private var overlayConfig: SkOverlayConfig? = null
    private var pendingHandoff: PendingHandoff? = null
    private var currentTelemetry: VideoPlaybackTelemetry? = null
    private var currentVideoStartedAtMs: Long? = null

    @Synchronized
    fun activateVideoPlanV2() {
        active = true
        audio.activateVideoPlanV2()
    }

    @Synchronized
    fun firstVideoFrame(config: SkOverlayConfig?) {
        if (!active) return
        if (currentVideoStartedAtMs == null) currentVideoStartedAtMs = clockMs()
        overlayStarted = true
        if (overlayConfig == null) overlayConfig = config
        val effective = overlayConfig
        if (effective == null || !effective.enabled) {
            overlayFinished = true
            return
        }
        overlayClock.start(effective.delaySeconds, clockMs(), foreground && !blocked)
    }

    @Synchronized
    fun updateEligibility(foreground: Boolean, blocked: Boolean): Boolean {
        this.foreground = foreground
        this.blocked = blocked
        return overlayClock.update(clockMs(), foreground && !blocked)
    }

    @Synchronized
    fun overlayReady(): Boolean = overlayFinished || overlayClock.ready

    @Synchronized
    fun overlayRemainingMs(): Long? = overlayClock.remainingMs().takeIf { overlayClock.started && !overlayClock.ready }

    @Synchronized
    fun beginHandoff(telemetry: VideoPlaybackTelemetry) {
        if (!active || pendingHandoff != null) return
        currentTelemetry = null
        pendingHandoff = PendingHandoff(
            telemetry = telemetry,
            startedAtMs = clockMs(),
            videoStartedAtMs = currentVideoStartedAtMs ?: clockMs(),
        )
        currentVideoStartedAtMs = null
    }

    @Synchronized
    fun retainCurrentTelemetry(telemetry: VideoPlaybackTelemetry) {
        if (active) currentTelemetry = telemetry
    }

    fun nextStepReady(): VideoHandoffTiming? {
        val pending = synchronized(this) {
            val retained = pendingHandoff ?: return null
            pendingHandoff = null
            retained
        }
        val nowMs = clockMs()
        val timing = VideoHandoffTiming(
            msToNextStepReady = (nowMs - pending.startedAtMs).coerceAtLeast(0L).toDouble(),
            secondsSinceVideoStart = (nowMs - pending.videoStartedAtMs).coerceAtLeast(0L) / 1_000.0,
        )
        pending.telemetry.record(
            stage = VIDEO_STAGE_HANDOFF,
            msToNextStepReady = timing.msToNextStepReady,
            secondsSinceVideoStart = timing.secondsSinceVideoStart,
            on = timing.on,
        )
        return timing
    }

    fun closePendingHandoff(reason: String) {
        val pending = synchronized(this) {
            val retained = pendingHandoff ?: return
            pendingHandoff = null
            retained
        }
        pending.telemetry.record(stage = VIDEO_STAGE_CLOSE, reason = reason)
    }

    fun close(telemetry: VideoPlaybackTelemetry, reason: String) {
        synchronized(this) {
            currentTelemetry = null
            currentVideoStartedAtMs = null
        }
        telemetry.record(stage = VIDEO_STAGE_CLOSE, reason = reason)
    }

    fun closeCurrent(reason: String) {
        val telemetry = synchronized(this) {
            val retained = currentTelemetry ?: return
            currentTelemetry = null
            currentVideoStartedAtMs = null
            retained
        }
        telemetry.record(stage = VIDEO_STAGE_CLOSE, reason = reason)
    }

    private data class PendingHandoff(
        val telemetry: VideoPlaybackTelemetry,
        val startedAtMs: Long,
        val videoStartedAtMs: Long,
    )
}

@Composable
internal fun VideoPlanOverlayClockEffect(
    state: VideoPlanPresentationState,
    blocked: () -> Boolean,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentBlocked by rememberUpdatedState(blocked)
    val active = state.active
    val started = state.overlayStarted
    LaunchedEffect(state, lifecycleOwner, active, started) {
        if (!active || !started) return@LaunchedEffect
        while (!state.overlayReady()) {
            val foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            state.updateEligibility(foreground, currentBlocked())
            delay(state.overlayRemainingMs()?.coerceIn(50L, 250L) ?: 250L)
        }
    }
}
