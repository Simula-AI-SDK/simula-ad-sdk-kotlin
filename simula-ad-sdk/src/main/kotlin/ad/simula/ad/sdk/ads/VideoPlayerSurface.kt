package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import android.graphics.SurfaceTexture
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/** Returning false from TextureView destruction transfers texture disposal to this shared lease. */
internal class VideoPlayerSurface(val texture: SurfaceTexture) {
    val surface = Surface(texture)
    private val playerReleased = AtomicBoolean()
    private val ownership = VideoSurfaceOwnership {
        SimulaScope.launch { runCatching { texture.release() } }
    }

    fun releaseView() = ownership.releaseView()

    /** Call on the renderer owner, only after setSurface/release has detached this surface. */
    fun releasePlayer() {
        if (!playerReleased.compareAndSet(false, true)) return
        runCatching { surface.release() }
        ownership.releasePlayer()
    }
}
