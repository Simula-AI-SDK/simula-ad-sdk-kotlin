package ad.simula.ad.sdk.nativead

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Admits at most one fresh native-ad AndroidView mount per display frame. */
internal object NativeAdMountScheduler {
    private const val FALLBACK_FRAME_DELAY_MS = 16L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = NativeMountAdmissionQueue<CompletableDeferred<Boolean>>()
    private var callbackScheduled = false
    private val frameCallback = Choreographer.FrameCallback { grantNext() }
    private val fallbackCallback = Runnable { grantNext() }

    /** Returns false when the platform cannot schedule either a frame or main-loop fallback. */
    suspend fun awaitPermit(): Boolean {
        val request = CompletableDeferred<Boolean>()
        return try {
            val enqueued = withContext(Dispatchers.Main.immediate) {
                if (!request.isActive) {
                    false
                } else {
                    pending.add(request)
                    scheduleNext()
                    true
                }
            }
            if (enqueued) request.await() else false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        } finally {
            // A disposed LaunchedEffect cancels its request. The next frame skips it without mounting.
            request.cancel()
        }
    }

    @MainThread
    private fun scheduleNext() {
        if (callbackScheduled || pending.isEmpty()) return
        callbackScheduled = true
        if (runCatching { Choreographer.getInstance().postFrameCallback(frameCallback) }.isSuccess) return

        val fallbackAccepted = runCatching {
            mainHandler.postDelayed(fallbackCallback, FALLBACK_FRAME_DELAY_MS)
        }.getOrDefault(false)
        if (!fallbackAccepted) {
            callbackScheduled = false
            pending.drain().forEach { it.complete(false) }
        }
    }

    @MainThread
    private fun grantNext() {
        callbackScheduled = false
        var request = pending.takeNext { it.isActive }
        while (request != null && !request.complete(true)) {
            request = pending.takeNext { it.isActive }
        }
        scheduleNext()
    }
}

/** Main-thread queue policy extracted for deterministic JVM coverage. */
internal class NativeMountAdmissionQueue<T> {
    private val items = ArrayDeque<T>()

    fun add(item: T) {
        items.addLast(item)
    }

    fun isEmpty(): Boolean = items.isEmpty()

    fun takeNext(isEligible: (T) -> Boolean): T? {
        while (items.isNotEmpty()) {
            val item = items.removeFirst()
            if (isEligible(item)) return item
        }
        return null
    }

    fun drain(): List<T> = buildList {
        while (items.isNotEmpty()) add(items.removeFirst())
    }
}
