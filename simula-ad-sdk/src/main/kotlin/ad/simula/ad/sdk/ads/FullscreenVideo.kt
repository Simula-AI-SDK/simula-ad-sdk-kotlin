package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.image.CachedAsyncImage
import ad.simula.ad.sdk.model.RenderAttemptGate
import ad.simula.ad.sdk.model.VideoAspectFitTransform
import ad.simula.ad.sdk.model.VideoFailureCode
import ad.simula.ad.sdk.model.VideoCompletionGate
import ad.simula.ad.sdk.model.VideoNearEndCompletionDetector
import ad.simula.ad.sdk.model.VideoDimensions
import ad.simula.ad.sdk.model.VideoPositionAccumulator
import ad.simula.ad.sdk.model.VideoPositionSample
import ad.simula.ad.sdk.model.VideoPreparationPhase
import ad.simula.ad.sdk.model.VideoReadinessDeadline
import ad.simula.ad.sdk.model.VideoUiProgressCoalescer
import ad.simula.ad.sdk.model.admittedVideoUrl
import ad.simula.ad.sdk.model.videoAspectFitTransform
import ad.simula.ad.sdk.model.videoCtaInteractionAllowed
import ad.simula.ad.sdk.model.videoMuteInteractionAllowed
import ad.simula.ad.sdk.model.videoMuteActionLabel
import ad.simula.ad.sdk.model.videoMuteControlVisible
import ad.simula.ad.sdk.model.videoPreparationClaimPolicy
import ad.simula.ad.sdk.model.videoReadinessTimeoutCode
import ad.simula.ad.sdk.model.videoMediaErrorCode
import ad.simula.ad.sdk.model.resolveVideoDimensions
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

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
internal const val VIDEO_STAGE_COMPLETE = "video_complete"
internal const val VIDEO_STAGE_FAIL = "video_fail"
private const val VIDEO_PREPARED_RETENTION_MS = 5 * 60_000L
private const val VIDEO_POSITION_POLL_MS = 100L
private const val VIDEO_UI_PROGRESS_INTERVAL_MS = 250L

internal fun <T> continueAfterVideoPositionPoll(
    read: () -> T?,
    consume: (T) -> Boolean,
): Boolean {
    val value = runCatching(read).getOrNull() ?: return true
    return runCatching { consume(value) }.getOrDefault(false)
}

internal fun dispatchNaturalVideoCompletion(
    onCompleted: () -> Unit,
    emitFinalProgress: () -> Unit,
) {
    runCatching(onCompleted)
    runCatching(emitFinalProgress)
}

@Composable
internal fun smoothVideoProgress(target: Float): Float {
    val progress by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = tween(VIDEO_UI_PROGRESS_INTERVAL_MS.toInt(), easing = LinearEasing),
        label = "video_progress",
    )
    return progress
}

internal data class PreparedVideoLease(
    val player: MediaPlayer,
    val phase: VideoPreparationPhase,
    val readinessDeadlineMs: Long,
)

/** One process-wide idle preparation. The active player is owned exclusively by its controller. */
internal object FullscreenVideoPreparer {
    private val main = Handler(Looper.getMainLooper())
    private var entry: Entry? = null

    fun prepare(rawUrl: String?) {
        val url = admittedVideoUrl(rawUrl) ?: return
        onMain {
            if (entry?.url == url) return@onMain
            releaseEntry()
            val startedAtMs = SystemClock.elapsedRealtime()
            runCatching {
                val player = MediaPlayer()
                val candidate = Entry(
                    url = url,
                    player = player,
                    readinessDeadlineMs = startedAtMs + VIDEO_READINESS_TIMEOUT_MS,
                )
                entry = candidate
                player.setAudioAttributes(videoAudioAttributes())
                player.setVolume(0f, 0f)
                player.isLooping = false
                player.setOnPreparedListener {
                    if (entry !== candidate) return@setOnPreparedListener
                    candidate.phase = VideoPreparationPhase.PREPARED
                    main.removeCallbacks(candidate.timeout)
                    candidate.timeout = Runnable {
                        if (entry === candidate) releaseEntry()
                    }
                    main.postDelayed(candidate.timeout, VIDEO_PREPARED_RETENTION_MS)
                }
                player.setOnErrorListener { _, _, _ ->
                    if (entry === candidate) releaseEntry()
                    true
                }
                candidate.timeout = Runnable {
                    if (entry === candidate) releaseEntry()
                }
                player.setDataSource(url)
                main.postDelayed(candidate.timeout, VIDEO_READINESS_TIMEOUT_MS)
                player.prepareAsync()
            }.onFailure { releaseEntry() }
        }
    }

    /** Matching claims transfer listener ownership even while prepareAsync is still in flight. */
    fun claim(url: String, claimedAtMs: Long): PreparedVideoLease? {
        if (Looper.myLooper() != Looper.getMainLooper()) return null
        val current = entry?.takeIf { it.url == url } ?: return null
        entry = null
        main.removeCallbacks(current.timeout)
        detachPlayerListeners(current.player)
        val policy = videoPreparationClaimPolicy(
            phase = current.phase,
            originalDeadlineMs = current.readinessDeadlineMs,
            claimedAtMs = claimedAtMs,
            totalReadinessMs = VIDEO_READINESS_TIMEOUT_MS,
        )
        return PreparedVideoLease(current.player, policy.phase, policy.readinessDeadlineMs)
    }

    private fun releaseEntry() {
        val current = entry ?: return
        entry = null
        main.removeCallbacks(current.timeout)
        detachPlayerListeners(current.player)
        releasePlayerOffMain(current.player)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private data class Entry(
        val url: String,
        val player: MediaPlayer,
        val readinessDeadlineMs: Long,
        var phase: VideoPreparationPhase = VideoPreparationPhase.PREPARING,
        var timeout: Runnable = Runnable {},
    )
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
}

private fun releasePlayerOffMain(player: MediaPlayer) {
    SimulaScope.launch { runCatching { player.release() } }
}

@Composable
internal fun FullscreenVideo(
    url: String,
    posterUrl: String?,
    adFormat: String,
    adUnitId: String? = null,
    adId: String? = null,
    serveId: String? = null,
    prewarmNextUrl: String? = null,
    configuredGateSeconds: Int = 0,
    initialPlayedMs: Long = 0L,
    ctaEnabled: Boolean = true,
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
    var firstFrameRendered by remember(url) { mutableStateOf(false) }
    var completed by remember(url) { mutableStateOf(false) }
    var muted by remember(url) { mutableStateOf(true) }
    val controller = remember(url) {
        NativeVideoController(
            context = context,
            telemetry = VideoTelemetryContext(adFormat, adUnitId, adId, serveId),
            configuredGateSeconds = configuredGateSeconds,
            initialPlayedMs = initialPlayedMs,
        )
    }

    controller.onReady = { durationMs ->
        firstFrameRendered = true
        currentOnReady(durationMs)
    }
    controller.onProgress = { sample, durationMs ->
        currentOnProgress(sample.positionMs, durationMs, sample.advancedMs)
    }
    controller.onMutedChanged = { muted = it }
    controller.onCompleted = {
        completed = true
        currentOnCompleted()
    }
    controller.onError = currentOnError

    DisposableEffect(controller, url) {
        controller.setLifecycleActive(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        controller.prepare(url)
        FullscreenVideoPreparer.prepare(prewarmNextUrl)
        onDispose { controller.release() }
    }
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
        val ctaModifier = if (ctaEnabled && videoCtaInteractionAllowed(firstFrameRendered)) {
            Modifier.clickable(onClick = onCta)
        } else {
            Modifier.consumeVideoTouches()
        }
        Box(Modifier.fillMaxSize().then(ctaModifier))
        if (videoMuteControlVisible(completed)) {
            VideoMuteControl(
                muted = muted,
                enabled = videoMuteInteractionAllowed(firstFrameRendered, playerActive = true),
                onClick = controller::toggleMuted,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
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

internal data class VideoTelemetryContext(
    val adFormat: String,
    val adUnitId: String?,
    val adId: String?,
    val serveId: String?,
)

private class NativeVideoController(
    context: Context,
    private val telemetry: VideoTelemetryContext,
    configuredGateSeconds: Int,
    initialPlayedMs: Long,
) {
    var onReady: (Long) -> Unit = {}
    var onProgress: (VideoPositionSample, Long) -> Unit = { _, _ -> }
    var onMutedChanged: (Boolean) -> Unit = {}
    var onCompleted: () -> Unit = {}
    var onError: () -> Unit = {}

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val renderGate = RenderAttemptGate()
    private val position = VideoPositionAccumulator(initialPlayedMs)
    private val progressCoalescer = VideoUiProgressCoalescer(VIDEO_UI_PROGRESS_INTERVAL_MS)
    private val nearEndCompletion = VideoNearEndCompletionDetector()
    private val completionGate = VideoCompletionGate()
    private val configuredGateMs = configuredGateSeconds.coerceAtLeast(0) * 1_000L
    private var renderToken = 0L
    private var player: MediaPlayer? = null
    private var textureView: TextureView? = null
    private var surface: Surface? = null
    private var readinessDeadline: VideoReadinessDeadline? = null
    private var lifecycleActive = false
    private var prepared = false
    private var firstFrameRendered = false
    private var completed = false
    private var muted = true
    private var released = false
    private var failed = false
    private var videoDimensions = VideoDimensions()
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var pendingAdvancedMs = 0L
    private var lastDeliveredTotalMs = initialPlayedMs.coerceAtLeast(0L)
    private var lastDeliveredPositionMs = 0L
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change <= 0) setMuted(true)
    }
    private val readinessTimeout = Runnable {
        fail(videoReadinessTimeoutCode(prepared))
    }
    private val playbackTimeout = Runnable { handlePlaybackTimeout() }
    private val positionPoll = object : Runnable {
        override fun run() {
            if (!lifecycleActive || released || failed || !firstFrameRendered) return
            val continuePolling = continueAfterVideoPositionPoll(
                read = { emitProgress(force = false) },
                consume = { progress ->
                    val isPlaying = runCatching { player?.isPlaying == true }.getOrElse {
                        fail(VideoFailureCode.PLAYBACK_ERROR)
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

    fun prepare(rawUrl: String) {
        if (released || player != null) return
        val url = admittedVideoUrl(rawUrl)
        if (url == null) {
            fail(VideoFailureCode.PREPARE_FAILED)
            return
        }
        renderToken = renderGate.begin()
        val token = renderToken
        val nowMs = SystemClock.elapsedRealtime()
        val warmed = FullscreenVideoPreparer.claim(url, nowMs)
        val deadlineMs = warmed?.readinessDeadlineMs ?: nowMs + VIDEO_READINESS_TIMEOUT_MS
        readinessDeadline = VideoReadinessDeadline(deadlineMs)
        if (!lifecycleActive) readinessDeadline?.pause(nowMs)
        runCatching {
            val mediaPlayer = warmed?.player ?: MediaPlayer()
            player = mediaPlayer
            prepared = warmed?.phase == VideoPreparationPhase.PREPARED
            if (prepared) seedVideoDimensions(mediaPlayer)
            configurePlayer(mediaPlayer, token)
            if (warmed == null) {
                mediaPlayer.setAudioAttributes(videoAudioAttributes())
                mediaPlayer.setVolume(0f, 0f)
                mediaPlayer.isLooping = false
                mediaPlayer.setDataSource(url)
                mediaPlayer.prepareAsync()
            }
            scheduleReadinessTimeout(nowMs)
            if (prepared) startIfPossible()
        }.onFailure { fail(VideoFailureCode.PREPARE_FAILED) }
    }

    private fun configurePlayer(mediaPlayer: MediaPlayer, token: Long) {
        mediaPlayer.setOnPreparedListener {
            if (!ownsCallback(token)) return@setOnPreparedListener
            prepared = true
            seedVideoDimensions(it)
            scheduleReadinessTimeout(SystemClock.elapsedRealtime())
            startIfPossible()
        }
        mediaPlayer.setOnCompletionListener {
            completePlayback(token)
        }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            if (ownsCallback(token)) {
                fail(videoMediaErrorCode(prepared))
            }
            true
        }
        mediaPlayer.setOnInfoListener { _, what, _ ->
            if (!ownsCallback(token)) return@setOnInfoListener false
            when (what) {
                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> admitFirstFrame(token)
                MediaPlayer.MEDIA_INFO_BUFFERING_START, MediaPlayer.MEDIA_INFO_BUFFERING_END -> resetPlaybackTimeout()
            }
            false
        }
        mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
            if (!ownsCallback(token)) return@setOnVideoSizeChangedListener
            videoDimensions = resolveVideoDimensions(videoDimensions, width, height)
            applyTextureTransform()
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
        pause()
        runCatching { player?.setSurface(null) }
        runCatching { surface?.release() }
        surface = null
        textureView = null
        surfaceWidth = 0
        surfaceHeight = 0
    }

    fun setLifecycleActive(active: Boolean) {
        if (released || lifecycleActive == active) return
        lifecycleActive = active
        val nowMs = SystemClock.elapsedRealtime()
        if (active) {
            readinessDeadline?.resume(nowMs)
            scheduleReadinessTimeout(nowMs)
            startIfPossible()
        } else {
            readinessDeadline?.pause(nowMs)
            handler.removeCallbacks(readinessTimeout)
            pause()
            if (!muted) setMuted(true)
        }
    }

    fun toggleMuted() {
        if (!videoMuteInteractionAllowed(firstFrameRendered, playerActive = !released && !failed)) return
        setMuted(if (muted) !requestAudioFocus() else true)
    }

    fun release() {
        if (released) return
        if (!failed && !completed && firstFrameRendered) emitProgress(force = true)
        released = true
        failed = true
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

    private fun ownsCallback(token: Long): Boolean = token == renderToken && !released && !failed

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
            mediaPlayer.start()
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
        if (!ownsCallback(token)) return
        if (!firstFrameRendered) {
            fail(VideoFailureCode.FIRST_FRAME_TIMEOUT)
            return
        }
        val transition = completionGate.complete()
        if (!transition.accepted) return
        completed = true
        dispatchNaturalVideoCompletion(
            onCompleted = onCompleted,
            emitFinalProgress = { emitProgress(force = true, completed = true) },
        )
        if (transition.cancelPlaybackTimeout) cancelPlaybackCallbacks()
        recordLifecycle(VIDEO_STAGE_COMPLETE)
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
            val current = runCatching { mediaPlayer.currentPosition.toLong() }.getOrDefault(0L)
            val currentSample = position.sample(current)
            val completedSample = position.complete(durationMs)
            completedSample.copy(advancedMs = currentSample.advancedMs + completedSample.advancedMs)
        } else {
            val current = runCatching { mediaPlayer.currentPosition.toLong() }.getOrNull() ?: return null
            position.sample(current)
        }
        pendingAdvancedMs += sample.advancedMs
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
        val mediaPlayer = player ?: return
        runCatching { if (mediaPlayer.isPlaying) mediaPlayer.pause() }
    }

    private fun cancelPlaybackCallbacks() {
        handler.removeCallbacks(positionPoll)
        handler.removeCallbacks(playbackTimeout)
    }

    private fun resetPlaybackTimeout() {
        if (!lifecycleActive || !firstFrameRendered || released || failed) return
        handler.removeCallbacks(playbackTimeout)
        handler.postDelayed(playbackTimeout, VIDEO_PLAYBACK_TIMEOUT_MS)
    }

    private fun setMuted(value: Boolean) {
        muted = value
        runCatching { player?.setVolume(if (value) 0f else 1f, if (value) 0f else 1f) }
        if (value) abandonAudioFocus()
        runCatching { onMutedChanged(value) }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        return runCatching {
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
    }

    private fun recordLifecycle(stage: String, errorCode: String? = null) {
        Telemetry.recordLifecycle(
            stage = stage,
            adFormat = telemetry.adFormat,
            adUnitId = telemetry.adUnitId,
            adId = telemetry.adId,
            serveId = telemetry.serveId,
            errorCode = errorCode,
        )
    }

    private fun fail(code: VideoFailureCode) {
        if (failed || released) return
        if (firstFrameRendered) emitProgress(force = true)
        failed = true
        renderGate.fail(renderToken)
        handler.removeCallbacks(readinessTimeout)
        cancelPlaybackCallbacks()
        recordLifecycle(VIDEO_STAGE_FAIL, code.wire)
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
