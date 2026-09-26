package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** All MediaPlayer calls, including construction, reads and disposal, have one Looper owner. */
private object VideoPlayerThread {
    private const val MAX_PLAYERS = 3
    private val owners = AtomicInteger()
    private val lock = Any()
    private val waiting = ArrayDeque<() -> Unit>()
    private var handler: Handler? = null
    private var starting = false

    fun acquire(): Boolean {
        ensureStarted()
        while (true) {
            val count = owners.get()
            if (count >= MAX_PLAYERS) return false
            if (owners.compareAndSet(count, count + 1)) return true
        }
    }

    fun release() { owners.decrementAndGet() }

    fun post(work: () -> Unit) {
        synchronized(lock) {
            handler?.let { it.post { work() }; return }
            // At most one drain per admitted owner can wait for the shared Looper.
            waiting.addLast(work)
        }
        ensureStarted()
    }

    fun ensureStarted() {
        synchronized(lock) {
            if (handler != null || starting || waiting.isEmpty()) return
            starting = true
        }
        runCatching {
            SimulaScope.launch {
                retryVideoPlayerThreadStart {
                    val thread = object : HandlerThread("simula-video") {
                        override fun onLooperPrepared() {
                            val ready = Handler(looper)
                            synchronized(lock) {
                                handler = ready
                                starting = false
                                while (waiting.isNotEmpty()) {
                                    val work = waiting.removeFirst()
                                    ready.post { work() }
                                }
                            }
                        }
                    }
                    thread.start()
                }
            }.invokeOnCompletion { failure ->
                if (failure != null) synchronized(lock) { if (handler == null) starting = false }
            }
        }.onFailure {
            // A later acquire/close can retry even if coroutine dispatch itself was unavailable.
            synchronized(lock) { if (handler == null) starting = false }
        }
    }

}

/** Main-thread facade. UI reads only snapshots; pending reads and native commands are bounded. */
internal class AsyncVideoPlayer private constructor() {
    companion object {
        fun create(): AsyncVideoPlayer? {
            if (!VideoPlayerThread.acquire()) return null
            return runCatching { AsyncVideoPlayer() }.getOrElse { VideoPlayerThread.release(); null }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val commands = VideoPlayerCommandQueue(VideoPlayerThread::post)
    private val closed = AtomicBoolean()
    private var native: MediaPlayer? = null // Owner thread only.
    private var nativeReady = false // Owner thread only.
    private var nativeSurface: VideoPlayerSurface? = null
    private val failedSurfaces = ArrayList<VideoPlayerSurface>() // Owner thread only.
    private var refreshPending = false // Main thread only.
    var duration: Int = 0; private set
    var currentPosition: Int = 0; private set
    var isPlaying: Boolean = false; private set
    var videoWidth: Int = 0; private set
    var videoHeight: Int = 0; private set
    var onPrepared: () -> Unit = {}
    var onSeekComplete: () -> Unit = {}
    var onCompleted: () -> Unit = {}
    var onError: () -> Unit = {}
    var onInfo: (Int) -> Unit = {}
    var onVideoSize: (Int, Int) -> Unit = { _, _ -> }
    var onBuffering: (Int) -> Unit = {}

    fun prepare(file: File, attributes: AudioAttributes, muted: Boolean) = command {
        val player = MediaPlayer()
        native = player
        player.setOnPreparedListener { nativeReady = true; publish { onPrepared() } }
        player.setOnSeekCompleteListener { publish { onSeekComplete() } }
        player.setOnCompletionListener { publish { onCompleted() } }
        player.setOnErrorListener { _, _, _ -> deliver { onError() }; true }
        player.setOnInfoListener { _, what, _ -> publish { onInfo(what) }; false }
        player.setOnVideoSizeChangedListener { _, width, height -> deliver { onVideoSize(width, height) } }
        player.setOnBufferingUpdateListener { _, percent -> deliver { onBuffering(percent) } }
        player.setAudioAttributes(attributes)
        player.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
        player.isLooping = false
        player.setDataSource(file.absolutePath)
        nativeSurface?.let { player.setSurface(it.surface) }
        player.prepareAsync()
    }

    fun seekTo(position: Int) = command { native?.seekTo(position) }
    fun start() = command { native?.start(); publish() }
    fun pause(onPaused: () -> Unit = {}) = command {
        if (nativeReady) {
            if (native?.isPlaying == true) native?.pause()
            publish(onPaused)
        } else {
            deliver(onPaused)
        }
    }
    fun setVolume(left: Float, right: Float) = command { native?.setVolume(left, right) }

    /** Ownership transfers even during disposal; the old surface is released only after detaching. */
    fun setSurface(surface: VideoPlayerSurface?) {
        if (closed.get()) {
            if (surface != null) SimulaScope.launch { runCatching { surface.releasePlayer() } }
            return
        }
        if (!commands.submit {
                val old = nativeSurface
                val detached = runCatching { native?.setSurface(surface?.surface) }.isSuccess
                if (detached) {
                    nativeSurface = surface
                    if (old !== surface) old?.releasePlayer()
                } else {
                    // Retain both possible bindings until the native player has been released.
                    if (surface != null && surface !== old) failedSurfaces.add(surface)
                    deliver { onError() }
                }
            }
        ) {
            if (surface != null) SimulaScope.launch { runCatching { surface.releasePlayer() } }
            onError()
        }
    }

    fun refresh() {
        if (closed.get() || refreshPending) return
        refreshPending = true
        command { publish { refreshPending = false } }
    }

    fun release() {
        if (!closed.compareAndSet(false, true)) return
        onPrepared = {}; onSeekComplete = {}; onCompleted = {}; onError = {}
        onInfo = {}; onVideoSize = { _, _ -> }; onBuffering = {}
        commands.close {
            try {
                val player = native
                native = null
                runCatching { player?.setOnPreparedListener(null) }
                runCatching { player?.setOnSeekCompleteListener(null) }
                runCatching { player?.setOnCompletionListener(null) }
                runCatching { player?.setOnErrorListener(null) }
                runCatching { player?.setOnInfoListener(null) }
                runCatching { player?.setOnVideoSizeChangedListener(null) }
                runCatching { player?.setOnBufferingUpdateListener(null) }
                runCatching { player?.release() }
                nativeSurface?.releasePlayer()
                nativeSurface = null
                failedSurfaces.forEach { it.releasePlayer() }
                failedSurfaces.clear()
            } finally { VideoPlayerThread.release() }
        }
        VideoPlayerThread.ensureStarted()
    }

    private fun command(work: () -> Unit) {
        if (closed.get()) return
        if (!commands.submit {
                if (!closed.get()) runCatching(work).onFailure { deliver { onError() } }
            }
        ) onError()
    }

    private fun deliver(action: () -> Unit) {
        if (!closed.get()) main.post { if (!closed.get()) runCatching(action) }
    }

    private fun publish(action: () -> Unit = {}) {
        if (closed.get()) return
        val player = native ?: return
        if (!nativeReady) { deliver(action); return }
        val snapshot = runCatching {
            Snapshot(player.duration, player.currentPosition, player.isPlaying, player.videoWidth, player.videoHeight)
        }.getOrElse { deliver { refreshPending = false; onError() }; return }
        deliver {
            duration = snapshot.duration
            currentPosition = snapshot.position
            isPlaying = snapshot.playing
            videoWidth = snapshot.width
            videoHeight = snapshot.height
            action()
        }
    }

    private data class Snapshot(val duration: Int, val position: Int, val playing: Boolean, val width: Int, val height: Int)
}

/** One startup job retains the bounded owner drains while transient thread allocation recovers. */
internal suspend fun retryVideoPlayerThreadStart(start: () -> Unit) {
    var delayMs = 1_000L
    while (true) {
        if (runCatching(start).isSuccess) return
        delay(delayMs)
        delayMs = (delayMs * 2).coerceAtMost(60_000L)
    }
}
