package ad.simula.ad.sdk.ads

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VideoPlayerCommandQueueTest {
    @Test
    fun `commands are deferred bounded and terminal cleanup is reserved`() {
        val scheduled = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val queue = VideoPlayerCommandQueue(schedule = { scheduled.addLast(it) }, capacity = 2)
        assertTrue(queue.submit { events += "attach" })
        assertTrue(queue.submit { events += "prepare" })
        assertFalse(queue.submit { events += "overflow" })
        queue.close { events += "release" }
        queue.close { events += "duplicate release" }
        assertFalse(queue.submit { events += "after release" })
        assertTrue(events.isEmpty())
        assertEquals(1, scheduled.size)
        scheduled.removeFirst().invoke()
        assertEquals(listOf("attach", "prepare", "release"), events)
    }

    @Test
    fun `throwing command cannot strand cleanup or prevent reuse of the queue`() {
        val scheduled = ArrayDeque<() -> Unit>()
        val queue = VideoPlayerCommandQueue(schedule = { scheduled.addLast(it) })
        var released = false
        queue.submit { error("native failure") }
        scheduled.removeFirst().invoke()
        queue.close { released = true }
        assertEquals(1, scheduled.size)
        scheduled.removeFirst().invoke()
        assertTrue(released)
    }

    @Test
    fun `blocked native operation never blocks caller or grows scheduled work`() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val queue = VideoPlayerCommandQueue(schedule = { executor.execute(it) }, capacity = 2)
        try {
            assertTrue(queue.submit { entered.countDown(); unblock.await(5, TimeUnit.SECONDS) })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(queue.submit {})
            assertTrue(queue.submit {})
            assertFalse(queue.submit {})
            queue.close { cleaned.countDown() }
            assertEquals(1L, cleaned.count)
            unblock.countDown()
            assertTrue(cleaned.await(2, TimeUnit.SECONDS))
        } finally {
            unblock.countDown()
            executor.shutdownNow()
        }
    }
}
