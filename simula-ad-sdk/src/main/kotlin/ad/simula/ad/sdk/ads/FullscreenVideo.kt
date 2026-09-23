package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.image.CachedAsyncImage
import ad.simula.ad.sdk.model.RenderAttemptGate
import ad.simula.ad.sdk.model.VideoAspectFitTransform
import ad.simula.ad.sdk.model.VideoFailureCode
import ad.simula.ad.sdk.model.VideoLifecycleReason
import ad.simula.ad.sdk.model.VideoCompletionGate
import ad.simula.ad.sdk.model.VideoNearEndCompletionDetector
import ad.simula.ad.sdk.model.VideoDimensions
import ad.simula.ad.sdk.model.VideoPositionAccumulator
import ad.simula.ad.sdk.model.VideoPositionSample
import ad.simula.ad.sdk.model.VideoReadinessDeadline
import ad.simula.ad.sdk.model.VideoUiProgressCoalescer
import ad.simula.ad.sdk.model.VideoAudioWatchAccounting
import ad.simula.ad.sdk.model.VideoChromeStyle
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.VideoMuteControlPlacement
import ad.simula.ad.sdk.model.VideoPreFirstFrameFailureAction
import ad.simula.ad.sdk.model.VideoQuartileTracker
import ad.simula.ad.sdk.model.VideoStallBudget
import ad.simula.ad.sdk.model.VideoSegment
import ad.simula.ad.sdk.model.videoAspectFitTransform
import ad.simula.ad.sdk.model.videoAudioFocusLossPolicy
import ad.simula.ad.sdk.model.videoCtaInteractionAllowed
import ad.simula.ad.sdk.model.videoChromeObstructionClearance
import ad.simula.ad.sdk.model.videoMuteInteractionAllowed
import ad.simula.ad.sdk.model.videoMuteActionLabel
import ad.simula.ad.sdk.model.videoMuteControlVisible
import ad.simula.ad.sdk.model.videoMuteControlPlacement
import ad.simula.ad.sdk.model.videoPreFirstFrameFailureAction
import ad.simula.ad.sdk.model.videoDesiredMutedAfterTap
import ad.simula.ad.sdk.model.videoDesiredMutedAfterLifecycleDeactivation
import ad.simula.ad.sdk.model.videoReadinessTimeoutCode
import ad.simula.ad.sdk.model.videoMediaErrorCode
import ad.simula.ad.sdk.model.initialVideoDesiredMuted
import ad.simula.ad.sdk.model.resolveVideoDimensions
import ad.simula.ad.sdk.model.resolvedVideoChromeStyle
import ad.simula.ad.sdk.model.effectiveVideoMuted
import ad.simula.ad.sdk.telemetry.Telemetry
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import java.io.File

internal fun videoCtaRoute(
    trackingUrl: String?,
    storeUrl: String?,
    destination: String,
): ad.simula.ad.sdk.network.PrimaryCtaRoute? {
    for (target in listOfNotNull(trackingUrl, storeUrl).distinct()) {
        when (val plan = CreativeCtaRouter.primaryCtaTapPlan(
            tappedUrl = target,
            creativeBaseUrl = null,
            trackingUrl = trackingUrl,
            destination = destination,
        )) {
            is CreativeCtaRouter.PrimaryCtaTapPlan.Route -> return plan.route
            else -> Unit
        }
    }
    return null
}

internal const val VIDEO_READINESS_TIMEOUT_MS = 10_000L
internal const val VIDEO_PLAYBACK_TIMEOUT_MS = 10_000L
internal const val VIDEO_STAGE_START = "video_start"
internal const val VIDEO_STAGE_DURATION = "video_duration"
internal const val VIDEO_STAGE_COMPLETE = "video_complete"
internal const val VIDEO_STAGE_FAIL = "video_fail"
internal const val VIDEO_STAGE_MUTE_TOGGLE = "video_mute_toggle"
internal const val VIDEO_STAGE_PAUSE = "video_pause"
internal const val VIDEO_STAGE_RESUME = "video_resume"
internal const val VIDEO_STAGE_CLOSE = "video_close"
internal const val VIDEO_STAGE_HANDOFF = "video_handoff"
private const val VIDEO_POSITION_POLL_MS = 100L
private const val VIDEO_UI_PROGRESS_INTERVAL_MS = 250L
private const val VIDEO_CHROME_OUTER_PADDING_DP = 16

internal fun canonicalFullscreenAdFormat(adFormat: String): String = when (adFormat) {
    "interstitial_fallback" -> "interstitial"
    "rewarded_fallback" -> "rewarded"
    else -> adFormat
}

internal fun <T> continueAfterVideoPositionPoll(
    read: () -> T?,
    onReadFailure: () -> Boolean = { true },
    consume: (T) -> Boolean,
): Boolean {
    val value = runCatching(read).getOrNull()
        ?: return runCatching(onReadFailure).getOrDefault(false)
    return runCatching { consume(value) }.getOrDefault(false)
}

internal fun dispatchNaturalVideoCompletion(
    onCompleted: () -> Unit,
    emitFinalProgress: () -> Unit,
) {
    runCatching(onCompleted)
    runCatching(emitFinalProgress)
}

internal fun detachVideoSurfaceInOrder(
    pausePlayback: () -> Unit,
    detachPlayerSurface: () -> Unit,
    clearSurfaceOwnership: () -> Unit,
    releaseSurface: () -> Unit,
) {
    runCatching(pausePlayback)
    runCatching(detachPlayerSurface)
    runCatching(clearSurfaceOwnership)
    runCatching(releaseSurface)
}

internal fun videoScreenAwakeEligible(
    firstFrameRendered: Boolean,
    terminal: Boolean,
    foreground: Boolean,
    presentationBlocked: Boolean,
    prepared: Boolean = true,
    surfaceAttached: Boolean = true,
    playing: Boolean = true,
): Boolean = firstFrameRendered && prepared && surfaceAttached && playing &&
    !terminal && foreground && !presentationBlocked

internal fun videoSegmentAtPosition(segments: List<VideoSegment>, positionMs: Long): VideoSegment? {
    val seconds = positionMs.coerceAtLeast(0L) / 1_000.0
    return segments.firstOrNull { seconds >= it.startSeconds && seconds < it.endSeconds }
        ?: segments.lastOrNull()?.takeIf { seconds >= it.endSeconds }
}

@Composable
private fun KeepVideoScreenAwake(visibleVideoPlaying: Boolean) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle, visibleVideoPlaying) {
        val previous = view.keepScreenOn
        fun update() {
            view.keepScreenOn = previous ||
                (visibleVideoPlaying && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            view.keepScreenOn = previous
        }
    }
}

internal fun shouldBeginVideoHandoff(
    videoPlanV2: Boolean,
    hasNextStep: () -> Boolean,
): Boolean = videoPlanV2 && runCatching(hasNextStep).getOrDefault(false)

@Composable
internal fun smoothVideoProgress(target: Float): Float {
    val progress by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = tween(VIDEO_UI_PROGRESS_INTERVAL_MS.toInt(), easing = LinearEasing),
        label = "video_progress",
    )
    return progress
}

private fun videoAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
    .build()

private fun detachPlayerListeners(player: MediaPlayer) {
    runCatching { player.setOnPreparedListener(null) }
    runCatching { player.setOnCompletionListener(null) }
    runCatching { player.setOnErrorListener(null) }
    runCatching { player.setOnInfoListener(null) }
    runCatching { player.setOnVideoSizeChangedListener(null) }
    runCatching { player.setOnBufferingUpdateListener(null) }
}

private fun releasePlayerOffMain(player: MediaPlayer) {
    SimulaScope.launch { runCatching { player.release() } }
}

@Composable
internal fun FullscreenVideo(
    file: File? = null,
    posterUrl: String?,
    adFormat: String,
    adUnitId: String? = null,
    adId: String? = null,
    serveId: String? = null,
    configuredGateSeconds: Int = 0,
    initialPlayedMs: Long = 0L,
    ctaEnabled: Boolean = true,
    ctaLabel: String? = null,
    appIconUrl: String? = null,
    appName: String? = null,
    subtitle: String? = null,
    chromeStyle: VideoChromeStyle = VideoChromeStyle.CORNER_CTA,
    effectiveClosePosition: ClosePosition = ClosePosition.TOP_RIGHT,
    bottomProgressBarObstructed: Boolean,
    videoPool: String? = null,
    playbackSlotIdentity: VideoPlaybackSlotIdentity,
    clipIndex: Int? = null,
    segments: List<VideoSegment> = emptyList(),
    skoverlayEnabled: Boolean? = null,
    skoverlayDelaySeconds: Int? = null,
    videoPlanV2: Boolean = false,
    videoPlanState: VideoPlanPresentationState = remember(videoPlanV2) {
        VideoPlanPresentationState(videoPlanV2)
    },
    presentationBlocked: Boolean = false,
    willHandoff: () -> Boolean = { false },
    modifier: Modifier = Modifier,
    onReady: (Long) -> Unit,
    onProgress: (Long, Long, Long) -> Unit,
    onCompleted: () -> Unit,
    onError: () -> Unit,
    onCta: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnReady by rememberUpdatedState(onReady)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnCompleted by rememberUpdatedState(onCompleted)
    val currentOnError by rememberUpdatedState(onError)
    val currentWillHandoff by rememberUpdatedState(willHandoff)
    var firstFrameRendered by remember(file, playbackSlotIdentity) { mutableStateOf(false) }
    var completed by remember(file, playbackSlotIdentity) { mutableStateOf(false) }
    var playbackEligible by remember(file, playbackSlotIdentity) { mutableStateOf(false) }
    val initialDesiredMuted = initialVideoDesiredMuted(videoPlanV2, videoPlanState.audio.desiredMuted)
    var muted by remember(file, playbackSlotIdentity, videoPlanV2, videoPlanState) {
        mutableStateOf(initialDesiredMuted)
    }
    val resolvedStyle = remember(chromeStyle, appName, appIconUrl) {
        resolvedVideoChromeStyle(chromeStyle, appName, appIconUrl)
    }
    val controller = remember(file, playbackSlotIdentity, videoPlanV2, videoPlanState) {
        NativeVideoController(
            context = context,
            telemetry = VideoTelemetryContext(
                adFormat = canonicalFullscreenAdFormat(adFormat),
                adUnitId = adUnitId,
                adId = adId,
                serveId = serveId,
                impressionId = serveId ?: adId,
                clipIndex = clipIndex,
                style = resolvedStyle.wire,
                skoverlayEnabled = skoverlayEnabled,
                skoverlayDelaySeconds = skoverlayDelaySeconds,
                pool = videoPool,
            ),
            configuredGateSeconds = configuredGateSeconds,
            initialPlayedMs = initialPlayedMs,
            videoPlanV2 = videoPlanV2,
            videoPlanState = videoPlanState,
            playbackSlotIdentity = playbackSlotIdentity,
            desiredMuted = initialDesiredMuted,
            hasNextStep = { currentWillHandoff() },
            segments = segments,
        )
    }

    controller.onReady = { durationMs ->
        firstFrameRendered = true
        videoPlanState.firstVideoFrame(
            ad.simula.ad.sdk.model.SkOverlayConfig(
                enabled = skoverlayEnabled == true,
                timing = ad.simula.ad.sdk.model.OverlayTiming.DELAYED,
                delaySeconds = skoverlayDelaySeconds ?: 3,
            ).takeIf { skoverlayEnabled != null },
        )
        videoPlanState.nextStepReady()
        currentOnReady(durationMs)
    }
    controller.onProgress = { sample, durationMs ->
        currentOnProgress(sample.positionMs, durationMs, sample.advancedMs)
    }
    controller.onEffectiveMutedChanged = { muted = it }
    controller.onPlaybackEligibilityChanged = { playbackEligible = it }
    controller.onDesiredMutedChanged = { videoPlanState.audio.updateFromTap(videoPlanV2, it) }
    controller.onCompleted = {
        completed = true
        currentOnCompleted()
    }
    controller.onError = currentOnError
    controller.setPresentationBlocked(presentationBlocked)
    val guardedOnCta = {
        if (videoCtaInteractionAllowed(videoPlanV2, firstFrameRendered, completed)) onCta()
    }

    DisposableEffect(controller, file) {
        if (controller.registerPlaybackGeneration()) {
            controller.setLifecycleActive(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            controller.prepare(file)
        }
        onDispose { controller.release() }
    }
    KeepVideoScreenAwake(playbackEligible)
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.setLifecycleActive(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> controller.setLifecycleActive(false)
                Lifecycle.Event.ON_DESTROY -> controller.release()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).also { texture ->
                    texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                            controller.attachSurface(texture, value, width, height)
                        }

                        override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) {
                            controller.updateSurfaceSize(texture, width, height)
                        }

                        override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                            controller.detachSurface(texture)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
                    }
                    texture.surfaceTexture?.takeIf { texture.isAvailable }?.let {
                        controller.attachSurface(texture, it, texture.width, texture.height)
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { texture ->
                texture.surfaceTextureListener = null
                controller.detachSurface(texture)
            },
        )
        if ((!firstFrameRendered || completed) && !posterUrl.isNullOrBlank()) {
            CachedAsyncImage(
                model = posterUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        val ctaModifier = if (ctaEnabled &&
            videoCtaInteractionAllowed(videoPlanV2, firstFrameRendered, completed)
        ) {
            Modifier.clickable(onClick = guardedOnCta)
        } else {
            Modifier.consumeVideoTouches()
        }
        Box(Modifier.fillMaxSize().then(ctaModifier))
        if (videoPlanV2 && ctaEnabled && firstFrameRendered && !completed) {
            val obstructionClearance = videoChromeObstructionClearance(
                effectiveClosePosition = effectiveClosePosition,
                resolvedStyle = resolvedStyle,
                bottomProgressBarObstructed = bottomProgressBarObstructed,
            )
            val additionalStartPadding = (
                obstructionClearance.minimumStartFromSafeEdgeDp - VIDEO_CHROME_OUTER_PADDING_DP
            ).coerceAtLeast(0)
            val additionalBottomPadding = (
                obstructionClearance.minimumBottomFromSafeEdgeDp - VIDEO_CHROME_OUTER_PADDING_DP
            ).coerceAtLeast(0)
            VideoCtaChrome(
                style = resolvedStyle,
                cta = ctaLabel?.takeIf { it.isNotBlank() } ?: "Install",
                appIconUrl = appIconUrl,
                appName = appName,
                subtitle = subtitle,
                onClick = guardedOnCta,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(start = additionalStartPadding.dp, bottom = additionalBottomPadding.dp)
                    .padding(VIDEO_CHROME_OUTER_PADDING_DP.dp),
            )
        }
        if (videoMuteControlVisible(completed)) {
            val muteModifier = when (videoMuteControlPlacement(videoPlanV2, ctaEnabled, effectiveClosePosition)) {
                VideoMuteControlPlacement.TOP_LEFT ->
                    Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(16.dp)
                VideoMuteControlPlacement.TOP_RIGHT ->
                    Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(16.dp)
                VideoMuteControlPlacement.BOTTOM_RIGHT ->
                    Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp)
            }
            VideoMuteControl(
                muted = muted,
                enabled = videoMuteInteractionAllowed(firstFrameRendered, playerActive = true),
                onClick = controller::toggleMuted,
                modifier = muteModifier,
            )
        }
    }
}

@Composable
private fun VideoCtaChrome(
    style: VideoChromeStyle,
    cta: String,
    appIconUrl: String?,
    appName: String?,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (style) {
        VideoChromeStyle.CORNER_CTA -> CtaPill(cta, onClick, modifier.fillMaxWidth(), alignEnd = true)
        VideoChromeStyle.FLOATING_PILL -> IdentityChrome(
            cta = cta,
            appIconUrl = appIconUrl,
            appName = appName.orEmpty(),
            subtitle = subtitle,
            onClick = onClick,
            modifier = modifier.background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(50)),
            compact = true,
        )
        VideoChromeStyle.BOTTOM_BAR -> IdentityChrome(
            cta = cta,
            appIconUrl = appIconUrl,
            appName = appName.orEmpty(),
            subtitle = subtitle,
            onClick = onClick,
            modifier = modifier.fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(18.dp)),
        )
        VideoChromeStyle.BOTTOM_CARD -> IdentityChrome(
            cta = cta,
            appIconUrl = appIconUrl,
            appName = appName.orEmpty(),
            subtitle = subtitle,
            onClick = onClick,
            modifier = modifier.fillMaxWidth().background(Color(0xFFF5F5F5), RoundedCornerShape(24.dp)),
            darkText = true,
        )
        VideoChromeStyle.FEED_CARD -> FeedChrome(
            cta = cta,
            appIconUrl = appIconUrl,
            appName = appName.orEmpty(),
            subtitle = subtitle,
            onClick = onClick,
            modifier = modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.76f), RoundedCornerShape(16.dp)),
        )
    }
}

@Composable
private fun FeedChrome(
    cta: String,
    appIconUrl: String?,
    appName: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(14.dp)) {
        IdentityTitle(appIconUrl, appName, subtitle, Color.White)
        Spacer(Modifier.size(10.dp))
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
        ) {
            Text(cta, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun IdentityTitle(appIconUrl: String?, appName: String, subtitle: String?, foreground: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!appIconUrl.isNullOrBlank()) {
            CachedAsyncImage(
                model = appIconUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp).background(Color(0xFFE5E7EB), RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(10.dp))
        }
        Column {
            Text(appName, color = foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = foreground.copy(alpha = 0.72f), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CtaPill(cta: String, onClick: () -> Unit, modifier: Modifier, alignEnd: Boolean = false) {
    Box(modifier = modifier, contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.Center) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
        ) { Text(cta, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun IdentityChrome(
    cta: String,
    appIconUrl: String?,
    appName: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier,
    darkText: Boolean = false,
    compact: Boolean = false,
) {
    val foreground = if (darkText) Color(0xFF171717) else Color.White
    Row(
        modifier = modifier.padding(if (compact) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!appIconUrl.isNullOrBlank()) {
            CachedAsyncImage(
                model = appIconUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(if (compact) 44.dp else 52.dp)
                    .background(Color(0xFFE5E7EB), RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = appName,
                color = foreground,
                fontSize = if (compact) 15.sp else 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = foreground.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
        ) { Text(cta, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun VideoMuteControl(
    muted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = videoMuteActionLabel(muted)
    val interaction = if (enabled) Modifier.clickable(onClick = onClick) else Modifier.consumeVideoTouches()
    Canvas(
        modifier = modifier
            .size(48.dp)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .then(interaction),
    ) {
        drawCircle(Color.Black.copy(alpha = 0.65f), radius = size.minDimension / 2f)
        val center = Offset(size.width / 2f, size.height / 2f)
        val icon = 24.dp.toPx()
        val left = center.x - icon / 2f
        val top = center.y - icon / 2f
        val speaker = Path().apply {
            moveTo(left + icon * 0.1f, top + icon * 0.38f)
            lineTo(left + icon * 0.34f, top + icon * 0.38f)
            lineTo(left + icon * 0.58f, top + icon * 0.16f)
            lineTo(left + icon * 0.58f, top + icon * 0.84f)
            lineTo(left + icon * 0.34f, top + icon * 0.62f)
            lineTo(left + icon * 0.1f, top + icon * 0.62f)
            close()
        }
        drawPath(speaker, Color.White)
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        if (muted) {
            drawLine(
                Color.White,
                Offset(left + icon * 0.68f, top + icon * 0.34f),
                Offset(left + icon * 0.94f, top + icon * 0.66f),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
            drawLine(
                Color.White,
                Offset(left + icon * 0.94f, top + icon * 0.34f),
                Offset(left + icon * 0.68f, top + icon * 0.66f),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        } else {
            drawArc(
                Color.White,
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(left + icon * 0.48f, top + icon * 0.25f),
                size = androidx.compose.ui.geometry.Size(icon * 0.34f, icon * 0.5f),
                style = stroke,
            )
            drawArc(
                Color.White,
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(left + icon * 0.42f, top + icon * 0.12f),
                size = androidx.compose.ui.geometry.Size(icon * 0.54f, icon * 0.76f),
                style = stroke,
            )
        }
    }
}

private fun Modifier.consumeVideoTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent().changes.forEach { it.consume() }
    }
}

private class NativeVideoController(
    context: Context,
    private val telemetry: VideoTelemetryContext,
    configuredGateSeconds: Int,
    initialPlayedMs: Long,
    private val videoPlanV2: Boolean,
    private val videoPlanState: VideoPlanPresentationState,
    private val playbackSlotIdentity: VideoPlaybackSlotIdentity,
    desiredMuted: Boolean,
    private val hasNextStep: () -> Boolean,
    private val segments: List<VideoSegment>,
) {
    var onReady: (Long) -> Unit = {}
    var onProgress: (VideoPositionSample, Long) -> Unit = { _, _ -> }
    var onEffectiveMutedChanged: (Boolean) -> Unit = {}
    var onPlaybackEligibilityChanged: (Boolean) -> Unit = {}
    var onDesiredMutedChanged: (Boolean) -> Unit = {}
    var onCompleted: () -> Unit = {}
    var onError: () -> Unit = {}

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val renderGate = RenderAttemptGate()
    private val position = VideoPositionAccumulator(initialPlayedMs)
    private val progressCoalescer = VideoUiProgressCoalescer(VIDEO_UI_PROGRESS_INTERVAL_MS)
    private val nearEndCompletion = VideoNearEndCompletionDetector()
    private val completionGate = VideoCompletionGate()
    private val stallBudget = VideoStallBudget()
    private val clipAudioWatch = VideoAudioWatchAccounting()
    private val quartiles = VideoQuartileTracker()
    private val configuredGateMs = configuredGateSeconds.coerceAtLeast(0) * 1_000L
    private var playbackGeneration: Long? = null
    private var renderToken = 0L
    private var player: MediaPlayer? = null
    private var textureView: TextureView? = null
    private var surface: Surface? = null
    private var readinessDeadline: VideoReadinessDeadline? = null
    private var lifecycleActive = false
    private var prepared = false
    private var playing = false
    private var firstFrameRendered = false
    private var completed = false
    private var desiredMuted = desiredMuted
    private var effectiveMuted = desiredMuted
    private var presentationBlocked = false
    private var bufferingProgressSincePoll = false
    private var lastBufferingPercent = 0
    private var released = false
    private var failed = false
    private var videoDimensions = VideoDimensions()
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var pendingAdvancedMs = 0L
    private var lastDeliveredTotalMs = initialPlayedMs.coerceAtLeast(0L)
    private var lastDeliveredPositionMs = 0L
    private var lastVideoPositionMs = 0L
    private var lastVideoDurationMs = 0L
    private var focusRequest: AudioFocusRequest? = null
    private var audioFocusHeld = false
    private var pausedAtMs: Long? = null
    private var startedAtMs: Long? = null
    private var terminalClaimOwned = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusHeld = true
                applyEffectiveMuted(effectiveVideoMuted(desiredMuted, audioFocusHeld = true))
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                val policy = videoAudioFocusLossPolicy(videoPlanV2, this.desiredMuted)
                this.desiredMuted = policy.desiredMuted
                audioFocusHeld = false
                applyEffectiveMuted(true)
                if (policy.abandonFocus) abandonAudioFocus()
            }
        }
    }
    private val readinessTimeout = Runnable {
        fail(videoReadinessTimeoutCode(prepared))
    }
    private val playbackTimeout = Runnable { handlePlaybackTimeout() }
    private val positionPoll = object : Runnable {
        override fun run() {
            if (!lifecycleActive || released || failed || !firstFrameRendered) return
            if (!ownsPresentationWork()) {
                releaseAfterLostTerminalClaim()
                return
            }
            val continuePolling = continueAfterVideoPositionPoll(
                read = { emitProgress(force = false) },
                onReadFailure = {
                    if (!videoPlanV2) {
                        true
                    } else {
                        bufferingProgressSincePoll = false
                        val expired = stallBudget.observe(
                            nowMs = SystemClock.elapsedRealtime(),
                            eligible = lifecycleActive && !presentationBlocked,
                            healthyProgress = false,
                        )
                        if (expired) {
                            if (nearEndCompletion.onPlaybackTimeout(
                                    durationMs = lastVideoDurationMs,
                                    positionMs = lastVideoPositionMs,
                                    firstFrameRendered = firstFrameRendered,
                                    playerActive = player != null && lifecycleActive && !released && !failed,
                                )
                            ) {
                                completePlayback(renderToken)
                            } else {
                                fail(VideoFailureCode.PLAYBACK_TIMEOUT)
                            }
                        }
                        !expired && !completed && !released && !failed
                    }
                },
                consume = { progress ->
                    val isPlaying = runCatching { player?.isPlaying == true }.getOrElse {
                        fail(VideoFailureCode.PLAYBACK_ERROR)
                        return@continueAfterVideoPositionPoll false
                    }
                    updatePlaying(isPlaying)
                    val nowMs = SystemClock.elapsedRealtime()
                    val healthyProgress = progress.sample.advancedMs > 0L || bufferingProgressSincePoll
                    bufferingProgressSincePoll = false
                    if (videoPlanV2 && stallBudget.observe(
                            nowMs = nowMs,
                            eligible = lifecycleActive && !presentationBlocked,
                            healthyProgress = healthyProgress,
                        )
                    ) {
                        if (nearEndCompletion.onPlaybackTimeout(
                                durationMs = progress.durationMs,
                                positionMs = progress.sample.positionMs,
                                firstFrameRendered = firstFrameRendered,
                                playerActive = player != null && lifecycleActive && !released && !failed,
                            )
                        ) {
                            completePlayback(renderToken)
                        } else {
                            fail(VideoFailureCode.PLAYBACK_TIMEOUT)
                        }
                        return@continueAfterVideoPositionPoll false
                    }
                    if (nearEndCompletion.observe(
                            durationMs = progress.durationMs,
                            positionMs = progress.sample.positionMs,
                            firstFrameRendered = firstFrameRendered,
                            playerActive = player != null && lifecycleActive && !released && !failed,
                            isPlaying = isPlaying,
                        )
                    ) {
                        completePlayback(renderToken)
                        false
                    } else {
                        !completed && !released && !failed
                    }
                },
            )
            if (continuePolling && lifecycleActive && !released && !failed && firstFrameRendered) {
                handler.postDelayed(this, VIDEO_POSITION_POLL_MS)
            }
        }
    }

    fun registerPlaybackGeneration(): Boolean {
        if (!videoPlanV2) return !released
        if (released || playbackGeneration != null) return false
        val registration = videoPlanState.registerPlaybackGeneration(playbackSlotIdentity)
        playbackGeneration = registration.generation
        when (videoPlaybackReplayAction(registration.retainedTerminalOutcome)) {
            VideoPlaybackReplayAction.PREPARE -> return true
            VideoPlaybackReplayAction.COMPLETE -> {
                completed = true
                runCatching(onCompleted)
            }
            VideoPlaybackReplayAction.FAIL -> {
                failed = true
                runCatching(onError)
            }
            VideoPlaybackReplayAction.STAY_STOPPED -> failed = true
        }
        return false
    }

    fun prepare(file: File?) {
        if (released || player != null) return
        if (videoPlanV2 && playbackGeneration == null) return
        if (!ownsPresentationWork()) {
            releaseAfterLostTerminalClaim()
            return
        }
        if (file == null) {
            fail(VideoFailureCode.PREPARE_FAILED)
            return
        }
        renderToken = renderGate.begin()
        val token = renderToken
        val nowMs = SystemClock.elapsedRealtime()
        val deadlineMs = nowMs + VIDEO_READINESS_TIMEOUT_MS
        readinessDeadline = VideoReadinessDeadline(deadlineMs)
        if (!lifecycleActive) readinessDeadline?.pause(nowMs)
        runCatching {
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            configurePlayer(mediaPlayer, token)
            mediaPlayer.setVolume(if (effectiveMuted) 0f else 1f, if (effectiveMuted) 0f else 1f)
            mediaPlayer.setAudioAttributes(videoAudioAttributes())
            mediaPlayer.isLooping = false
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.prepareAsync()
            scheduleReadinessTimeout(nowMs)
        }.onFailure { fail(VideoFailureCode.PREPARE_FAILED) }
    }

    private fun configurePlayer(mediaPlayer: MediaPlayer, token: Long) {
        mediaPlayer.setOnPreparedListener {
            if (!ownsCallback(token)) return@setOnPreparedListener
            prepared = true
            publishPlaybackEligibility()
            seedVideoDimensions(it)
            scheduleReadinessTimeout(SystemClock.elapsedRealtime())
            startIfPossible()
        }
        mediaPlayer.setOnCompletionListener {
            completePlayback(token)
        }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            if (token == renderToken && !released && !failed) {
                fail(videoMediaErrorCode(prepared))
            }
            true
        }
        mediaPlayer.setOnInfoListener { _, what, _ ->
            if (!ownsCallback(token)) return@setOnInfoListener false
            when (what) {
                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> admitFirstFrame(token)
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                    if (!videoPlanV2) resetPlaybackTimeout()
                }
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                    if (!videoPlanV2) resetPlaybackTimeout()
                }
            }
            false
        }
        mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
            if (!ownsCallback(token)) return@setOnVideoSizeChangedListener
            videoDimensions = resolveVideoDimensions(videoDimensions, width, height)
            applyTextureTransform()
        }
        mediaPlayer.setOnBufferingUpdateListener { _, percent ->
            if (!ownsCallback(token)) return@setOnBufferingUpdateListener
            val bounded = percent.coerceIn(0, 100)
            if (bounded > lastBufferingPercent) {
                lastBufferingPercent = bounded
                bufferingProgressSincePoll = true
            }
        }
        surface?.let(mediaPlayer::setSurface)
    }

    fun attachSurface(texture: TextureView, surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        runCatching {
            textureView = texture
            surfaceWidth = width.coerceAtLeast(0)
            surfaceHeight = height.coerceAtLeast(0)
            surface?.release()
            surface = Surface(surfaceTexture)
            player?.setSurface(surface)
            applyTextureTransform()
            startIfPossible()
        }.onFailure {
            fail(videoMediaErrorCode(prepared))
        }
    }

    fun updateSurfaceSize(texture: TextureView, width: Int, height: Int) {
        if (textureView !== texture || released) return
        surfaceWidth = width.coerceAtLeast(0)
        surfaceHeight = height.coerceAtLeast(0)
        applyTextureTransform()
    }

    fun detachSurface(texture: TextureView) {
        if (textureView !== texture) return
        val detachedSurface = surface
        detachVideoSurfaceInOrder(
            pausePlayback = ::pause,
            detachPlayerSurface = { player?.setSurface(null) },
            clearSurfaceOwnership = {
                surface = null
                textureView = null
                surfaceWidth = 0
                surfaceHeight = 0
                updatePlaying(false)
                publishPlaybackEligibility()
            },
            releaseSurface = { detachedSurface?.release() },
        )
    }

    fun setLifecycleActive(active: Boolean) {
        if (released || lifecycleActive == active) return
        lifecycleActive = active
        val nowMs = SystemClock.elapsedRealtime()
        if (active) {
            readinessDeadline?.resume(nowMs)
            scheduleReadinessTimeout(nowMs)
            startIfPossible()
            val pausedAt = pausedAtMs
            if (videoPlanV2 && firstFrameRendered && pausedAt != null) {
                pausedAtMs = null
                recordLifecycle(
                    stage = VIDEO_STAGE_RESUME,
                    pausedMs = (nowMs - pausedAt).coerceAtLeast(0L).toDouble(),
                )
            }
        } else {
            readinessDeadline?.pause(nowMs)
            handler.removeCallbacks(readinessTimeout)
            if (firstFrameRendered && !completed && !failed) emitProgress(force = true)
            pause()
            stallBudget.observe(nowMs, eligible = false, healthyProgress = false)
            if (videoPlanV2 && firstFrameRendered && pausedAtMs == null && !completed && !failed) {
                pausedAtMs = nowMs
                recordLifecycle(VIDEO_STAGE_PAUSE, reason = VideoLifecycleReason.BACKGROUNDED)
            }
            desiredMuted = videoDesiredMutedAfterLifecycleDeactivation(videoPlanV2, desiredMuted)
            applyEffectiveMuted(true)
            abandonAudioFocus()
        }
        publishPlaybackEligibility()
    }

    fun setPresentationBlocked(blocked: Boolean) {
        if (presentationBlocked == blocked) return
        presentationBlocked = blocked
        if (blocked) {
            stallBudget.observe(SystemClock.elapsedRealtime(), eligible = false, healthyProgress = false)
        }
        publishPlaybackEligibility()
    }

    fun toggleMuted() {
        if (!videoMuteInteractionAllowed(firstFrameRendered, playerActive = !released && !failed)) return
        desiredMuted = videoDesiredMutedAfterTap(effectiveMuted)
        onDesiredMutedChanged(desiredMuted)
        if (desiredMuted) {
            applyEffectiveMuted(true)
            abandonAudioFocus()
        } else {
            applyEffectiveMuted(effectiveVideoMuted(desiredMuted = false, audioFocusHeld = requestAudioFocus()))
        }
        if (videoPlanV2) recordLifecycle(VIDEO_STAGE_MUTE_TOGGLE)
    }

    fun release() {
        if (released) return
        if (!failed && !completed && firstFrameRendered && ownsPresentationWork()) emitProgress(force = true)
        released = true
        failed = true
        playing = false
        publishPlaybackEligibility()
        renderGate.fail(renderToken)
        handler.removeCallbacks(readinessTimeout)
        cancelPlaybackCallbacks()
        abandonAudioFocus()
        val detachedPlayer = player
        player = null
        val detachedTexture = textureView
        textureView = null
        runCatching { detachedTexture?.surfaceTextureListener = null }
        if (detachedPlayer != null) {
            detachPlayerListeners(detachedPlayer)
            runCatching { detachedPlayer.pause() }
            runCatching { detachedPlayer.setSurface(null) }
        }
        runCatching { surface?.release() }
        surface = null
        readinessDeadline = null
        if (detachedPlayer != null) releasePlayerOffMain(detachedPlayer)
    }

    private fun ownsCallback(token: Long): Boolean =
        token == renderToken && !released && !failed && ownsPresentationWork()

    private fun ownsPresentationWork(): Boolean =
        !videoPlanV2 || terminalClaimOwned || playbackGeneration?.let(videoPlanState::isPlaybackGenerationOpen) == true

    private fun claimPresentationTerminal(outcome: VideoPlaybackTerminalOutcome): Boolean {
        if (!videoPlanV2) return true
        val generation = playbackGeneration ?: return false
        if (!videoPlanState.claimPlaybackTerminal(generation, outcome)) return false
        terminalClaimOwned = true
        return true
    }

    private fun releaseAfterLostTerminalClaim() {
        failed = true
        release()
    }

    private fun scheduleReadinessTimeout(nowMs: Long) {
        if (!lifecycleActive || firstFrameRendered || released || failed) return
        handler.removeCallbacks(readinessTimeout)
        val remainingMs = readinessDeadline?.remainingMs(nowMs) ?: 0L
        if (remainingMs <= 0L) readinessTimeout.run() else handler.postDelayed(readinessTimeout, remainingMs)
    }

    private fun startIfPossible() {
        if (!prepared || !lifecycleActive || surface == null || released || failed) return
        runCatching {
            val mediaPlayer = player ?: return
            // Acquire focus before the first rendered frame so contract 2 starts without a late volume jump.
            val focusHeld = !desiredMuted && requestAudioFocus()
            applyEffectiveMuted(effectiveVideoMuted(desiredMuted, focusHeld))
            mediaPlayer.start()
            updatePlaying(runCatching { mediaPlayer.isPlaying }.getOrDefault(false))
            scheduleReadinessTimeout(SystemClock.elapsedRealtime())
            if (firstFrameRendered) {
                schedulePositionPolling()
                resetPlaybackTimeout()
            }
        }.onFailure { fail(VideoFailureCode.PLAYBACK_ERROR) }
    }

    private fun admitFirstFrame(token: Long) {
        if (!renderGate.ready(token)) return
        firstFrameRendered = true
        publishPlaybackEligibility()
        startedAtMs = SystemClock.elapsedRealtime()
        handler.removeCallbacks(readinessTimeout)
        emitProgress(force = true)
        runCatching { onReady(durationMs()) }
        recordLifecycle(VIDEO_STAGE_START)
        schedulePositionPolling()
        resetPlaybackTimeout()
    }

    private fun schedulePositionPolling() {
        handler.removeCallbacks(positionPoll)
        if (lifecycleActive && firstFrameRendered && !released && !failed) handler.post(positionPoll)
    }

    private fun completePlayback(token: Long) {
        if (token != renderToken || released || failed) return
        if (!firstFrameRendered) {
            fail(VideoFailureCode.FIRST_FRAME_TIMEOUT)
            return
        }
        val transition = completionGate.complete()
        if (!transition.accepted) return
        if (!claimPresentationTerminal(VideoPlaybackTerminalOutcome.COMPLETED)) {
            releaseAfterLostTerminalClaim()
            return
        }
        completed = true
        playing = false
        publishPlaybackEligibility()
        dispatchNaturalVideoCompletion(
            onCompleted = onCompleted,
            emitFinalProgress = { emitProgress(force = true, completed = true) },
        )
        if (transition.cancelPlaybackTimeout) cancelPlaybackCallbacks()
        recordLifecycle(VIDEO_STAGE_COMPLETE)
        if (videoPlanV2) {
            val snapshot = telemetrySnapshot()
            if (shouldBeginVideoHandoff(videoPlanV2, hasNextStep)) {
                videoPlanState.beginHandoff(snapshot, VideoLifecycleReason.COMPLETED)
            } else {
                videoPlanState.close(snapshot, reason = VideoLifecycleReason.COMPLETED)
            }
        }
        release()
    }

    private fun handlePlaybackTimeout() {
        if (!lifecycleActive || released || failed || !firstFrameRendered || player == null) return
        val progress = emitProgress(force = false)
        if (progress == null) {
            fail(VideoFailureCode.PLAYBACK_ERROR)
            return
        }
        if (progress.sample.advancedMs > 0L) return
        if (nearEndCompletion.onPlaybackTimeout(
                durationMs = progress.durationMs,
                positionMs = progress.sample.positionMs,
                firstFrameRendered = firstFrameRendered,
                playerActive = true,
            )
        ) {
            completePlayback(renderToken)
        } else {
            fail(VideoFailureCode.PLAYBACK_TIMEOUT)
        }
    }

    private fun emitProgress(force: Boolean, completed: Boolean = false): PlayerProgress? {
        val mediaPlayer = player ?: return null
        val durationMs = durationMs()
        val sample = if (completed) {
            val currentAdvancedMs = runCatching { mediaPlayer.currentPosition.toLong() }
                .getOrNull()
                ?.let(position::sample)
                ?.advancedMs
                ?: 0L
            val completedSample = position.complete(durationMs)
            completedSample.copy(advancedMs = currentAdvancedMs + completedSample.advancedMs)
        } else {
            val current = runCatching { mediaPlayer.currentPosition.toLong() }.getOrNull() ?: return null
            position.sample(current)
        }
        pendingAdvancedMs += sample.advancedMs
        lastVideoPositionMs = maxOf(lastVideoPositionMs, sample.positionMs)
        if (durationMs > 0L) lastVideoDurationMs = durationMs
        videoPlanState.addEligibleMediaDelta(videoPlanV2, sample.advancedMs, effectiveMuted)
        clipAudioWatch.add(sample.advancedMs, effectiveMuted)
        if (videoPlanV2) {
            quartiles.crossed(sample.positionMs, durationMs).forEach { quartile ->
                recordLifecycle(VIDEO_STAGE_DURATION, quartile = quartile)
            }
            playbackGeneration?.let { videoPlanState.retainCurrentTelemetry(it, telemetrySnapshot()) }
        }
        if (sample.advancedMs > 0L) resetPlaybackTimeout()
        val gateDurationMs = if (durationMs > 0L) minOf(configuredGateMs, durationMs) else configuredGateMs
        val gateCrossed = lastDeliveredTotalMs < gateDurationMs && sample.totalPlayedMs >= gateDurationMs
        val midpointMs = durationMs / 2L
        val midpointCrossed = durationMs > 0L && lastDeliveredPositionMs < midpointMs && sample.positionMs >= midpointMs
        val progress = PlayerProgress(sample, durationMs)
        if (!progressCoalescer.shouldEmit(
                nowMs = SystemClock.elapsedRealtime(),
                force = force,
                gateCrossed = gateCrossed,
                midpointCrossed = midpointCrossed,
            )
        ) return progress
        val delivered = sample.copy(advancedMs = pendingAdvancedMs)
        pendingAdvancedMs = 0L
        lastDeliveredTotalMs = sample.totalPlayedMs
        lastDeliveredPositionMs = sample.positionMs
        runCatching { onProgress(delivered, durationMs) }
        return progress
    }

    private fun durationMs(): Long = runCatching { player?.duration?.toLong() ?: 0L }
        .getOrDefault(0L)
        .coerceAtLeast(0L)

    private fun applyTextureTransform() {
        val texture = textureView ?: return
        val transform = videoAspectFitTransform(
            videoDimensions.width,
            videoDimensions.height,
            surfaceWidth,
            surfaceHeight,
        )
        runCatching { texture.setTransform(centeredTextureMatrix(transform, surfaceWidth, surfaceHeight)) }
    }

    /** Prepared leases may have emitted their size before listener ownership transferred. */
    private fun seedVideoDimensions(mediaPlayer: MediaPlayer) {
        val width = runCatching { mediaPlayer.videoWidth }.getOrDefault(0)
        val height = runCatching { mediaPlayer.videoHeight }.getOrDefault(0)
        videoDimensions = resolveVideoDimensions(videoDimensions, width, height)
        applyTextureTransform()
    }

    private fun pause() {
        cancelPlaybackCallbacks()
        val mediaPlayer = player
        runCatching { if (mediaPlayer?.isPlaying == true) mediaPlayer.pause() }
        updatePlaying(false)
    }

    private fun cancelPlaybackCallbacks() {
        handler.removeCallbacks(positionPoll)
        handler.removeCallbacks(playbackTimeout)
    }

    private fun resetPlaybackTimeout() {
        if (!lifecycleActive || !firstFrameRendered || released || failed) return
        handler.removeCallbacks(playbackTimeout)
        if (videoPlanV2) return
        handler.postDelayed(playbackTimeout, VIDEO_PLAYBACK_TIMEOUT_MS)
    }

    private fun applyEffectiveMuted(value: Boolean) {
        if (effectiveMuted == value) return
        effectiveMuted = value
        runCatching { player?.setVolume(if (value) 0f else 1f, if (value) 0f else 1f) }
        runCatching { onEffectiveMutedChanged(value) }
    }

    private fun requestAudioFocus(): Boolean {
        if (audioFocusHeld) return true
        val manager = audioManager ?: return false
        val granted = runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(videoAudioAttributes())
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                focusRequest = request
                manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }.getOrDefault(false)
        audioFocusHeld = granted
        return granted
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest?.let(manager::abandonAudioFocusRequest)
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(focusListener)
            }
        }
        focusRequest = null
        audioFocusHeld = false
    }

    private fun recordLifecycle(
        stage: String,
        quartile: Int? = null,
        reason: VideoLifecycleReason? = null,
        pausedMs: Double? = null,
        errorCode: String? = null,
    ) {
        if (videoPlanV2) {
            telemetrySnapshot().record(
                stage = stage,
                quartile = quartile,
                reason = reason,
                pausedMs = pausedMs,
                secondsSinceVideoStart = startedAtMs?.let {
                    (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) / 1_000.0
                },
                error = errorCode,
            )
        } else {
            Telemetry.recordLifecycle(
                stage = stage,
                adFormat = telemetry.adFormat,
                adUnitId = telemetry.adUnitId,
                adId = telemetry.adId,
                serveId = telemetry.serveId,
                errorCode = errorCode,
            )
        }
    }

    private fun telemetrySnapshot(): VideoPlaybackTelemetry {
        val totals = clipAudioWatch.totals()
        return VideoPlaybackTelemetry(
            context = activeSegment(lastVideoPositionMs)?.let { segment ->
                telemetry.copy(clipIndex = segment.clipIndex, pool = segment.videoPool)
            } ?: telemetry,
            videoPositionS = lastVideoPositionMs / 1_000.0,
            muted = effectiveMuted,
            durationS = maxOf(durationMs(), lastVideoDurationMs).takeIf { it > 0L }?.div(1_000.0),
            watchedS = (totals.mutedMs + totals.unmutedMs) / 1_000.0,
            secondsUnmuted = totals.unmutedMs / 1_000.0,
            secondsMuted = totals.mutedMs / 1_000.0,
        )
    }

    private fun activeSegment(positionMs: Long): VideoSegment? {
        return videoSegmentAtPosition(segments, positionMs)
    }

    private fun publishPlaybackEligibility() {
        runCatching {
            onPlaybackEligibilityChanged(
                videoScreenAwakeEligible(
                    firstFrameRendered = firstFrameRendered,
                    terminal = completed || failed || released,
                    foreground = lifecycleActive,
                    presentationBlocked = presentationBlocked,
                    prepared = prepared,
                    surfaceAttached = surface != null,
                    playing = playing,
                ),
            )
        }
    }

    private fun updatePlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        publishPlaybackEligibility()
    }

    private fun fail(code: VideoFailureCode) {
        if (failed || released) return
        if (!claimPresentationTerminal(VideoPlaybackTerminalOutcome.FAILED)) {
            releaseAfterLostTerminalClaim()
            return
        }
        if (firstFrameRendered) emitProgress(force = true)
        failed = true
        playing = false
        publishPlaybackEligibility()
        renderGate.fail(renderToken)
        handler.removeCallbacks(readinessTimeout)
        cancelPlaybackCallbacks()
        recordLifecycle(VIDEO_STAGE_FAIL, errorCode = code.wire)
        if (videoPlanV2) {
            val snapshot = telemetrySnapshot()
            val willHandoff = shouldBeginVideoHandoff(videoPlanV2, hasNextStep)
            if (!firstFrameRendered) {
                if (videoPreFirstFrameFailureAction(willHandoff) ==
                    VideoPreFirstFrameFailureAction.FAIL_EXPECTED_NEXT_STEP
                ) {
                    videoPlanState.resolvePendingHandoffForFailedNextStep()
                }
            } else if (willHandoff) {
                videoPlanState.beginHandoff(snapshot, VideoLifecycleReason.FAILED)
            } else {
                videoPlanState.close(snapshot, reason = VideoLifecycleReason.FAILED)
            }
        }
        Telemetry.recordError(
            signature = "video:playback_failed",
            errorCode = code.wire,
            breadcrumb = "surface=${telemetry.adFormat}",
        )
        runCatching(onError)
        release()
    }

    private data class PlayerProgress(
        val sample: VideoPositionSample,
        val durationMs: Long,
    )
}

private fun centeredTextureMatrix(
    transform: VideoAspectFitTransform,
    width: Int,
    height: Int,
): Matrix = Matrix().apply {
    val pivotX = width.coerceAtLeast(0) / 2f
    val pivotY = height.coerceAtLeast(0) / 2f
    setScale(transform.scaleX, transform.scaleY, pivotX, pivotY)
}
