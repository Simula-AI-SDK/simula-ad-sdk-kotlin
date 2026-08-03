package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.ads.SimulaAdError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test

class NativeAdControllerTest {
    @Test
    fun `provider resolver is authoritative when global imperative API is uninitialized`() = runTest {
        val error = runCatching {
            NativeAdController.load(
                ensureSession = { null },
                adUnitId = "native-unit",
                position = 0,
            )
        }.exceptionOrNull()

        assertSame(SimulaAdError.NoSession, error)
    }
}
