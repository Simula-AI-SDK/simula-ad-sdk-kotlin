package ad.simula.ad.sdk.ads

/** Bounded FIFO; scheduling and work always happen outside the monitor. */
internal class VideoPlayerCommandQueue(
    private val schedule: (() -> Unit) -> Unit,
    private val capacity: Int = 32,
) {
    private val lock = Any()
    private val commands = ArrayDeque<() -> Unit>()
    private var scheduled = false
    private var closed = false

    fun submit(command: () -> Unit): Boolean {
        val start = synchronized(lock) {
            if (closed || commands.size >= capacity) return false
            commands.addLast(command)
            if (scheduled) false else { scheduled = true; true }
        }
        if (start) schedule(::drain)
        return true
    }

    /** One reserved terminal command, even at capacity; accepted work retains FIFO ownership. */
    fun close(cleanup: () -> Unit) {
        val start = synchronized(lock) {
            if (closed) return
            closed = true
            commands.addLast(cleanup)
            if (scheduled) false else { scheduled = true; true }
        }
        if (start) schedule(::drain)
    }

    private fun drain() {
        while (true) {
            val command = synchronized(lock) {
                if (commands.isEmpty()) { scheduled = false; null } else commands.removeFirst()
            } ?: return
            runCatching(command)
        }
    }
}
