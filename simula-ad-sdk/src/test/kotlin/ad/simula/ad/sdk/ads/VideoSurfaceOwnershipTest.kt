package ad.simula.ad.sdk.ads

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSurfaceOwnershipTest {
    @Test
    fun `destroyed view retains texture until queued player detach finishes`() {
        var disposals = 0
        val lease = VideoSurfaceOwnership { disposals++ }
        lease.releaseView()
        lease.releaseView()
        assertEquals(0, disposals)
        lease.releasePlayer()
        assertEquals(1, disposals)
        lease.releasePlayer()
        lease.releaseView()
        assertEquals(1, disposals)
    }

    @Test
    fun `player teardown leaves live view texture intact until view destruction`() {
        var disposals = 0
        val lease = VideoSurfaceOwnership { disposals++ }
        lease.releasePlayer()
        lease.releasePlayer()
        assertEquals(0, disposals)
        lease.releaseView()
        assertEquals(1, disposals)
    }
}
