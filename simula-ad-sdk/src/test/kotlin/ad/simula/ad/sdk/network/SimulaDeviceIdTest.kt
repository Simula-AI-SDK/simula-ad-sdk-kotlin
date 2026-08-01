package ad.simula.ad.sdk.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulaDeviceIdTest {
    @Test
    fun `prime never resolves inline on the caller`() = runTest {
        var calls = 0
        val primer = DeviceIdPrimer<Unit>(this, retryDelayMs = 0) {
            calls++
            "android-id"
        }

        primer.prime(Unit)

        assertEquals(0, calls)
        assertNull(primer.value)
        runCurrent()
        assertEquals(1, calls)
        assertEquals("android-id", primer.value)
    }

    @Test
    fun `concurrent primes share one resolver flight and success is cached`() = runTest {
        var calls = 0
        val result = CompletableDeferred<String?>()
        val primer = DeviceIdPrimer<String>(this, retryDelayMs = 0) {
            calls++
            result.await()
        }

        primer.prime("first")
        runCurrent()
        primer.prime("second")
        runCurrent()

        assertEquals(1, calls)
        result.complete("android-id")
        advanceUntilIdle()
        primer.prime("third")
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals("android-id", primer.value)
    }

    @Test
    fun `blank result releases the flight for retry`() = runTest {
        val results = ArrayDeque(listOf("  ", "android-id"))
        var calls = 0
        val primer = DeviceIdPrimer<Unit>(this, retryDelayMs = 0) {
            calls++
            results.removeFirst()
        }

        primer.prime(Unit)
        advanceUntilIdle()
        assertNull(primer.value)

        primer.prime(Unit)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals("android-id", primer.value)
    }

    @Test
    fun `resolver exception releases the flight for retry`() = runTest {
        var calls = 0
        val primer = DeviceIdPrimer<Unit>(this, retryDelayMs = 0) {
            calls++
            if (calls == 1) throw IllegalStateException("resolver unavailable")
            "android-id"
        }

        primer.prime(Unit)
        advanceUntilIdle()
        assertNull(primer.value)

        primer.prime(Unit)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals("android-id", primer.value)
    }

    @Test
    fun `failed resolution is throttled until retry cooldown expires`() = runTest {
        var now = 1_000L
        var calls = 0
        val primer = DeviceIdPrimer<Unit>(this, retryDelayMs = 30_000L, nowMs = { now }) {
            calls++
            null
        }

        primer.prime(Unit)
        advanceUntilIdle()
        primer.prime(Unit)
        advanceUntilIdle()
        assertEquals(1, calls)

        now += 30_000L
        primer.prime(Unit)
        advanceUntilIdle()
        assertEquals(2, calls)
    }
}
