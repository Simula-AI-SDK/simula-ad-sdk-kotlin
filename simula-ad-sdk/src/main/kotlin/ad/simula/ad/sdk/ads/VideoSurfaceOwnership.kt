package ad.simula.ad.sdk.ads

/** TextureView and the native renderer must both relinquish ownership before texture disposal. */
internal class VideoSurfaceOwnership(private val dispose: () -> Unit) {
    private var viewReleased = false
    private var playerReleased = false
    private var disposed = false

    fun releaseView() = update(view = true)
    fun releasePlayer() = update(view = false)

    private fun update(view: Boolean) {
        val shouldDispose = synchronized(this) {
            if (view) viewReleased = true else playerReleased = true
            if (viewReleased && playerReleased && !disposed) { disposed = true; true } else false
        }
        if (shouldDispose) dispose()
    }
}
