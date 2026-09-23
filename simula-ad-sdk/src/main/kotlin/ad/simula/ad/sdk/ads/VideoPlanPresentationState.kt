package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.SkOverlayConfig
import ad.simula.ad.sdk.model.VideoAudioWatchAccounting
import ad.simula.ad.sdk.model.VideoAudioWatchTotals
import ad.simula.ad.sdk.model.VideoAudioSessionState
import ad.simula.ad.sdk.model.VideoInstallOverlayClock
import ad.simula.ad.sdk.model.VideoLifecycleReason
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
        reason: VideoLifecycleReason? = null,
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
            reason = reason?.wire,
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

internal enum class VideoPlaybackTerminalOutcome { COMPLETED, FAILED, USER }

internal enum class VideoPlaybackReplayAction { PREPARE, COMPLETE, FAIL, STAY_STOPPED }

internal sealed class VideoPlaybackSlotIdentity {
    data object Primary : VideoPlaybackSlotIdentity()
    data class Fallback(val sourceIndex: Int) : VideoPlaybackSlotIdentity()
}

internal fun videoPlaybackReplayAction(
    outcome: VideoPlaybackTerminalOutcome?,
): VideoPlaybackReplayAction = when (outcome) {
    VideoPlaybackTerminalOutcome.COMPLETED -> VideoPlaybackReplayAction.COMPLETE
    VideoPlaybackTerminalOutcome.FAILED -> VideoPlaybackReplayAction.FAIL
    VideoPlaybackTerminalOutcome.USER -> VideoPlaybackReplayAction.STAY_STOPPED
    null -> VideoPlaybackReplayAction.PREPARE
}

internal data class VideoPlaybackRegistration(
    val generation: Long,
    val retainedTerminalOutcome: VideoPlaybackTerminalOutcome?,
)

/** State shared by the primary contract-2 playback and its ordinary end-screen presentation. */
internal class VideoPlanPresentationState(
    videoPlanV2: Boolean = false,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
    private val terminalRecorder: (
        VideoPlaybackTelemetry,
        String,
        VideoLifecycleReason?,
        VideoHandoffTiming?,
    ) -> Unit = { telemetry, stage, reason, timing ->
        telemetry.record(
            stage = stage,
            reason = reason,
            msToNextStepReady = timing?.msToNextStepReady,
            secondsSinceVideoStart = timing?.secondsSinceVideoStart,
            on = timing?.on,
        )
    },
) {
    val audio = VideoAudioSessionState(videoPlanV2)
    private val audioWatch = VideoAudioWatchAccounting()
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
    private var playbackGeneration = 0L
    private var currentPlaybackGeneration: Long? = null
    private var currentPlaybackSlotIdentity: VideoPlaybackSlotIdentity? = null
    private var terminalOutcome: VideoPlaybackTerminalOutcome? = null

    @Synchronized
    fun registerPlaybackGeneration(
        playbackSlotIdentity: VideoPlaybackSlotIdentity,
    ): VideoPlaybackRegistration {
        val replacingCurrentSlot = currentPlaybackGeneration != null &&
            currentPlaybackSlotIdentity == playbackSlotIdentity
        playbackGeneration += 1L
        currentPlaybackGeneration = playbackGeneration
        currentPlaybackSlotIdentity = playbackSlotIdentity
        if (!replacingCurrentSlot) {
            terminalOutcome = null
            currentTelemetry = null
            currentVideoStartedAtMs = null
        }
        return VideoPlaybackRegistration(playbackGeneration, terminalOutcome)
    }

    @Synchronized
    fun claimPlaybackTerminal(generation: Long, outcome: VideoPlaybackTerminalOutcome): Boolean {
        if (currentPlaybackGeneration != generation || terminalOutcome != null) return false
        terminalOutcome = outcome
        return true
    }

    @Synchronized
    fun isPlaybackGenerationOpen(generation: Long): Boolean =
        currentPlaybackGeneration == generation && terminalOutcome == null

    @Synchronized
    fun addEligibleMediaDelta(videoPlanV2: Boolean, advancedMs: Long, muted: Boolean) {
        if (videoPlanV2) audioWatch.add(advancedMs, muted)
    }

    @Synchronized
    fun audioWatchTotals(): VideoAudioWatchTotals = audioWatch.totals()

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
    fun beginHandoff(telemetry: VideoPlaybackTelemetry, reason: VideoLifecycleReason) {
        if (!active || pendingHandoff != null) return
        currentTelemetry = null
        pendingHandoff = PendingHandoff(
            telemetry = telemetry,
            reason = reason,
            startedAtMs = clockMs(),
            videoStartedAtMs = currentVideoStartedAtMs ?: clockMs(),
        )
        currentVideoStartedAtMs = null
    }

    @Synchronized
    fun retainCurrentTelemetry(generation: Long, telemetry: VideoPlaybackTelemetry) {
        if (active && currentPlaybackGeneration == generation && terminalOutcome == null) {
            currentTelemetry = telemetry
        }
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
        recordTerminal(pending.telemetry, VIDEO_STAGE_HANDOFF, reason = pending.reason, timing = timing)
        return timing
    }

    fun closePendingHandoff(reasonOverride: VideoLifecycleReason? = null) {
        val pending = synchronized(this) {
            val retained = pendingHandoff ?: return
            pendingHandoff = null
            retained
        }
        recordTerminal(
            pending.telemetry,
            VIDEO_STAGE_CLOSE,
            reason = reasonOverride ?: pending.reason,
        )
    }

    fun resolvePendingHandoffForFailedNextStep() {
        closePendingHandoff(VideoLifecycleReason.NEXT_STEP_FAILED)
    }

    fun close(telemetry: VideoPlaybackTelemetry, reason: VideoLifecycleReason) {
        synchronized(this) {
            currentTelemetry = null
            currentVideoStartedAtMs = null
        }
        recordTerminal(telemetry, VIDEO_STAGE_CLOSE, reason = reason)
    }

    fun closeCurrent(reason: VideoLifecycleReason): Boolean {
        val telemetry = synchronized(this) {
            val generation = currentPlaybackGeneration ?: return false
            if (terminalOutcome != null) return true
            if (!claimPlaybackTerminal(generation, VideoPlaybackTerminalOutcome.USER)) return false
            val retained = currentTelemetry
            currentTelemetry = null
            currentVideoStartedAtMs = null
            retained
        }
        if (telemetry != null) recordTerminal(telemetry, VIDEO_STAGE_CLOSE, reason = reason)
        return true
    }

    private fun recordTerminal(
        telemetry: VideoPlaybackTelemetry,
        stage: String,
        reason: VideoLifecycleReason? = null,
        timing: VideoHandoffTiming? = null,
    ) {
        val totals = audioWatchTotals()
        terminalRecorder(
            telemetry.copy(
                watchedS = (totals.mutedMs + totals.unmutedMs) / 1_000.0,
                secondsUnmuted = totals.unmutedMs / 1_000.0,
                secondsMuted = totals.mutedMs / 1_000.0,
            ),
            stage,
            reason,
            timing,
        )
    }

    private data class PendingHandoff(
        val telemetry: VideoPlaybackTelemetry,
        val reason: VideoLifecycleReason,
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
