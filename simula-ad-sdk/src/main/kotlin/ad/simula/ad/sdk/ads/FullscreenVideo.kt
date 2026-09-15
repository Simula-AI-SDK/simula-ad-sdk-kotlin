package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.image.CachedAsyncImage
import ad.simula.ad.sdk.model.admittedVideoUrl
import ad.simula.ad.sdk.model.RenderAttemptGate
import ad.simula.ad.sdk.model.VideoPositionAccumulator
import ad.simula.ad.sdk.model.VideoPositionSample
import ad.simula.ad.sdk.model.videoCtaInteractionAllowed
import ad.simula.ad.sdk.model.videoMuteInteractionAllowed
import ad.simula.ad.sdk.telemetry.Telemetry
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal const val VIDEO_PREPARE_TIMEOUT_MS = 10_000L
internal const val VIDEO_STAGE_START = "video_start"
internal const val VIDEO_STAGE_COMPLETE = "video_complete"
internal const val VIDEO_STAGE_FAIL = "video_fail"
private const val VIDEO_PREPARED_RETENTION_MS = 5 * 60_000L

/** Process-wide one-entry prewarm. A Ready ad can prepare without retaining one player per ad object. */
internal object FullscreenVideoPreparer {
    private val main = Handler(Looper.getMainLooper())
    private var entry: Entry? = null

    fun prepare(rawUrl: String?) {
        val url = admittedVideoUrl(rawUrl) ?: return
        onMain {
            if (entry?.url == url) return@onMain
            releaseEntry()
            runCatching {
                val player = MediaPlayer()
                val timeout = Runnable {
                    if (entry?.player === player) releaseEntry()
                }
                val candidate = Entry(url, player, timeout)
                entry = candidate
                player.setAudioAttributes(videoAudioAttributes())
                player.setVolume(0f, 0f)
                player.setOnPreparedListener {
                    if (entry !== candidate) return@setOnPreparedListener
                    candidate.ready = true
                    main.removeCallbacks(timeout)
                    main.postDelayed(timeout, VIDEO_PREPARED_RETENTION_MS)
                }
                player.setOnErrorListener { _, _, _ ->
                    if (entry === candidate) releaseEntry()
                    true
                }
                player.setDataSource(url)
                main.postDelayed(timeout, VIDEO_PREPARE_TIMEOUT_MS)
                player.prepareAsync()
            }.onFailure { releaseEntry() }
        }
    }

    fun claim(url: String): MediaPlayer? {
        if (Looper.myLooper() != Looper.getMainLooper()) return null
        val current = entry ?: return null
        if (current.url != url || !current.ready) {
            if (current.url == url) releaseEntry()
            return null
        }
        entry = null
        main.removeCallbacks(current.timeout)
        runCatching { current.player.setOnPreparedListener(null) }
        runCatching { current.player.setOnErrorListener(null) }
        return current.player
    }

    private fun releaseEntry() {
        val current = entry ?: return
        entry = null
        main.removeCallbacks(current.timeout)
        runCatching { current.player.setOnPreparedListener(null) }
        runCatching { current.player.setOnErrorListener(null) }
        runCatching { current.player.reset() }
        runCatching { current.player.release() }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private data class Entry(
        val url: String,
        val player: MediaPlayer,
        val timeout: Runnable,
        var ready: Boolean = false,
    )
}

private fun videoAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
    .build()

/** One bounded MediaPlayer/TextureView presentation. All framework calls stay on the main thread. */
@Composable
internal fun FullscreenVideo(
    url: String,
    posterUrl: String?,
    adFormat: String,
    adUnitId: String? = null,
    adId: String? = null,
    serveId: String? = null,
    prewarmNextUrl: String? = null,
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
                            controller.attachSurface(value)
                        }

                        override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) = Unit

                        override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                            controller.detachSurface()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
                    }
                    texture.surfaceTexture?.takeIf { texture.isAvailable }?.let(controller::attachSurface)
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { controller.detachSurface() },
        )
        if ((!firstFrameRendered || completed) && !posterUrl.isNullOrBlank()) {
            CachedAsyncImage(
                model = posterUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .clickable {
                    if (videoCtaInteractionAllowed(firstFrameRendered)) onCta()
                },
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable {
                    if (videoMuteInteractionAllowed(firstFrameRendered, playerActive = !completed)) {
                        controller.toggleMuted()
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(if (muted) "Muted" else "Sound", color = Color.White, fontSize = 12.sp)
        }
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
) {
    var onReady: (Long) -> Unit = {}
    var onProgress: (VideoPositionSample, Long) -> Unit = { _, _ -> }
    var onMutedChanged: (Boolean) -> Unit = {}
    var onCompleted: () -> Unit = {}
    var onError: () -> Unit = {}

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val renderGate = RenderAttemptGate()
    private val position = VideoPositionAccumulator()
    private var renderToken = 0L
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var lifecycleActive = false
    private var prepared = false
    private var firstFrameRendered = false
    private var muted = true
    private var released = false
    private var failed = false
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change <= 0) setMuted(true)
    }
    private val preparationTimeout = Runnable { fail("preparation_timeout") }
    private val firstFrameTimeout = Runnable {
        if (renderGate.isPending(renderToken)) fail("playback_timeout")
    }
    private val playbackTimeout = Runnable { fail("playback_timeout") }
    private val positionPoll = object : Runnable {
        override fun run() {
            if (!lifecycleActive || released || failed || !firstFrameRendered) return
            emitProgress()
            handler.postDelayed(this, VIDEO_POSITION_POLL_MS)
        }
    }

    fun prepare(rawUrl: String) {
        if (released || player != null) return
        val url = admittedVideoUrl(rawUrl)
        if (url == null) {
            fail("playback_failed")
            return
        }
        renderToken = renderGate.begin()
        val token = renderToken
        val warmed = FullscreenVideoPreparer.claim(url)
        runCatching {
            (warmed ?: MediaPlayer()).also { mediaPlayer ->
                player = mediaPlayer
                configurePlayer(mediaPlayer, token)
                if (warmed != null) {
                    prepared = true
                    startIfPossible()
                    return@also
                }
                mediaPlayer.setAudioAttributes(videoAudioAttributes())
                mediaPlayer.setVolume(0f, 0f)
                mediaPlayer.isLooping = false
                mediaPlayer.setDataSource(url)
                handler.postDelayed(preparationTimeout, VIDEO_PREPARE_TIMEOUT_MS)
                mediaPlayer.prepareAsync()
            }
        }.onFailure { fail("playback_failed") }
    }

    private fun configurePlayer(mediaPlayer: MediaPlayer, token: Long) {
        mediaPlayer.setOnPreparedListener {
            if (released || failed || token != renderToken) return@setOnPreparedListener
            handler.removeCallbacks(preparationTimeout)
            prepared = true
            startIfPossible()
        }
        mediaPlayer.setOnCompletionListener {
            if (released || failed || token != renderToken) return@setOnCompletionListener
            if (!firstFrameRendered) {
                fail("playback_failed")
                return@setOnCompletionListener
            }
            emitCompletedProgress()
            cancelPlaybackCallbacks()
            recordLifecycle(VIDEO_STAGE_COMPLETE)
            runCatching(onCompleted)
            release()
        }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            if (token == renderToken) fail("playback_failed")
            true
        }
        mediaPlayer.setOnInfoListener { _, what, _ ->
            if (token != renderToken || released || failed) return@setOnInfoListener false
            when (what) {
                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> admitFirstFrame(token)
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> resetPlaybackTimeout()
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> resetPlaybackTimeout()
            }
            false
        }
        surface?.let(mediaPlayer::setSurface)
    }

    fun attachSurface(texture: SurfaceTexture) {
        if (released) return
        runCatching {
            surface?.release()
            surface = Surface(texture)
            player?.setSurface(surface)
            startIfPossible()
        }.onFailure { fail("playback_failed") }
    }

    fun detachSurface() {
        if (released) return
        pause()
        runCatching { player?.setSurface(null) }
        runCatching { surface?.release() }
        surface = null
    }

    fun setLifecycleActive(active: Boolean) {
        if (released) return
        lifecycleActive = active
        if (active) {
            startIfPossible()
        } else {
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
        released = true
        renderGate.fail(renderToken)
        handler.removeCallbacks(preparationTimeout)
        handler.removeCallbacks(firstFrameTimeout)
        cancelPlaybackCallbacks()
        abandonAudioFocus()
        runCatching { player?.setOnPreparedListener(null) }
        runCatching { player?.setOnCompletionListener(null) }
        runCatching { player?.setOnErrorListener(null) }
        runCatching { player?.setOnInfoListener(null) }
        runCatching { player?.setSurface(null) }
        runCatching { player?.reset() }
        runCatching { player?.release() }
        player = null
        runCatching { surface?.release() }
        surface = null
    }

    private fun startIfPossible() {
        if (!prepared || !lifecycleActive || surface == null || released || failed) return
        runCatching {
            val mediaPlayer = player ?: return
            mediaPlayer.start()
            if (!firstFrameRendered && renderGate.isPending(renderToken)) {
                handler.removeCallbacks(firstFrameTimeout)
                handler.postDelayed(firstFrameTimeout, VIDEO_PREPARE_TIMEOUT_MS)
            } else if (firstFrameRendered) {
                schedulePositionPolling()
                resetPlaybackTimeout()
            }
        }.onFailure { fail("playback_failed") }
    }

    private fun admitFirstFrame(token: Long) {
        if (!renderGate.ready(token)) return
        firstFrameRendered = true
        handler.removeCallbacks(firstFrameTimeout)
        runCatching { onReady(durationMs()) }
        recordLifecycle(VIDEO_STAGE_START)
        schedulePositionPolling()
        resetPlaybackTimeout()
    }

    private fun schedulePositionPolling() {
        handler.removeCallbacks(positionPoll)
        if (lifecycleActive && firstFrameRendered && !released && !failed) {
            handler.post(positionPoll)
        }
    }

    private fun emitProgress() {
        val mediaPlayer = player ?: return
        val current = runCatching { mediaPlayer.currentPosition.toLong() }.getOrNull() ?: return
        val sample = position.sample(current)
        if (sample.advancedMs > 0L) resetPlaybackTimeout()
        runCatching { onProgress(sample, durationMs()) }
    }

    private fun emitCompletedProgress() {
        val durationMs = durationMs()
        val sample = position.complete(durationMs)
        runCatching { onProgress(sample, durationMs) }
    }

    private fun durationMs(): Long = runCatching { player?.duration?.toLong() ?: 0L }
        .getOrDefault(0L)
        .coerceAtLeast(0L)

    private fun pause() {
        cancelPlaybackCallbacks()
        handler.removeCallbacks(firstFrameTimeout)
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
        handler.postDelayed(playbackTimeout, VIDEO_PREPARE_TIMEOUT_MS)
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

    private fun fail(reason: String) {
        if (failed || released) return
        failed = true
        renderGate.fail(renderToken)
        handler.removeCallbacks(preparationTimeout)
        handler.removeCallbacks(firstFrameTimeout)
        cancelPlaybackCallbacks()
        recordLifecycle(VIDEO_STAGE_FAIL, reason)
        Telemetry.recordError(
            signature = "video:playback_failed",
            errorCode = reason,
            breadcrumb = "surface=${telemetry.adFormat}",
        )
        runCatching(onError)
        release()
    }

    private companion object {
        const val VIDEO_POSITION_POLL_MS = 100L
    }
}
