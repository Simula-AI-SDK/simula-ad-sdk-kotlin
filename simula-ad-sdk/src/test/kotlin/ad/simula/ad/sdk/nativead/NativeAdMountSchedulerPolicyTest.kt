package ad.simula.ad.sdk.nativead

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAdMountSchedulerPolicyTest {

    @Test
    fun `each admission tick returns at most one eligible mount`() {
        val queue = NativeMountAdmissionQueue<String>()
        queue.add("first")
        queue.add("second")
        queue.add("third")

        assertEquals("first", queue.takeNext { true })
        assertEquals("second", queue.takeNext { true })
        assertEquals("third", queue.takeNext { true })
        assertNull(queue.takeNext { true })
    }

    @Test
    fun `retained reattach priority moves ahead of queued cold mounts`() {
        val queue = NativeMountAdmissionQueue<String>()
        queue.add("cold-first")
        queue.add("cold-second")
        queue.add("retained-first", prioritize = true)
        queue.add("retained-second", prioritize = true)

        assertEquals("retained-first", queue.takeNext { true })
        assertEquals("retained-second", queue.takeNext { true })
        assertEquals("cold-first", queue.takeNext { true })
        assertEquals("cold-second", queue.takeNext { true })
    }

    @Test
    fun `cancelled mounts are skipped without consuming an admission`() {
        val queue = NativeMountAdmissionQueue<Pair<String, Boolean>>()
        queue.add("left-composition" to false)
        queue.add("visible-slot" to true)
        queue.add("next-slot" to true)

        assertEquals("visible-slot", queue.takeNext { it.second }?.first)
        assertEquals("next-slot", queue.takeNext { it.second }?.first)
        assertNull(queue.takeNext { it.second })
    }

    @Test
    fun `scheduler failure can drain all pending mounts`() {
        val queue = NativeMountAdmissionQueue<String>()
        queue.add("first")
        queue.add("second")

        assertEquals(listOf("first", "second"), queue.drain())
        assertNull(queue.takeNext { true })
    }

    @Test
    fun `scheduler dedupes callbacks grants once per frame and reschedules`() = runBlocking {
        val frames = ArrayDeque<() -> Unit>()
        val scheduler = NativeMountFrameScheduler(
            postFrame = { callback -> frames.addLast(callback); true },
            postFallback = { false },
        )
        val first = CompletableDeferred<Boolean>()
        val second = CompletableDeferred<Boolean>()
        val third = CompletableDeferred<Boolean>()

        scheduler.enqueue(first)
        scheduler.enqueue(second)
        scheduler.enqueue(third)

        assertEquals(1, frames.size)
        frames.removeFirst().invoke()
        assertTrue(first.await())
        assertFalse(second.isCompleted)
        assertFalse(third.isCompleted)
        assertEquals(1, frames.size)

        frames.removeFirst().invoke()
        assertTrue(second.await())
        assertFalse(third.isCompleted)
        assertEquals(1, frames.size)

        frames.removeFirst().invoke()
        assertTrue(third.await())
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `scheduler skips cancelled requests without consuming a frame grant`() = runBlocking {
        val frames = ArrayDeque<() -> Unit>()
        val scheduler = NativeMountFrameScheduler(
            postFrame = { callback -> frames.addLast(callback); true },
            postFallback = { false },
        )
        val cancelled = CompletableDeferred<Boolean>()
        val eligible = CompletableDeferred<Boolean>()
        scheduler.enqueue(cancelled)
        scheduler.enqueue(eligible)
        cancelled.cancel()

        frames.removeFirst().invoke()

        assertTrue(cancelled.isCancelled)
        assertTrue(eligible.await())
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `scheduler falls back when frame posting fails and still reschedules`() = runBlocking {
        val fallbacks = ArrayDeque<() -> Unit>()
        var frameAttempts = 0
        val scheduler = NativeMountFrameScheduler(
            postFrame = { frameAttempts++; throw IllegalStateException("frame scheduler unavailable") },
            postFallback = { callback -> fallbacks.addLast(callback); true },
        )
        val first = CompletableDeferred<Boolean>()
        val second = CompletableDeferred<Boolean>()

        scheduler.enqueue(first)
        scheduler.enqueue(second)
        assertEquals(1, frameAttempts)
        assertEquals(1, fallbacks.size)

        fallbacks.removeFirst().invoke()
        assertTrue(first.await())
        assertFalse(second.isCompleted)
        assertEquals(2, frameAttempts)
        assertEquals(1, fallbacks.size)

        fallbacks.removeFirst().invoke()
        assertTrue(second.await())
        assertTrue(fallbacks.isEmpty())
    }

    @Test
    fun `scheduler rejection completes every queued request as ineligible`() = runBlocking {
        val scheduler = NativeMountFrameScheduler(
            postFrame = { false },
            postFallback = { false },
        )
        val request = CompletableDeferred<Boolean>()

        scheduler.enqueue(request)

        assertFalse(request.await())
    }
}
