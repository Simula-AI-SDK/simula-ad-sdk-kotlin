package ad.simula.ad.sdk.core

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SimulaScope backstop: an uncaught task failure is reported through
 * [uncaughtExceptionReporter] and terminally consumed (the supervisor keeps the scope
 * alive; the failure never reaches a crash handler). Real time (`runBlocking`) because
 * the scope's IO dispatcher is real — a virtual test clock could outrun it.
 */
class SimulaScopeTest {

    @Test
    fun `an uncaught task failure is reported and the scope survives`() = runBlocking {
        val reported = AtomicReference<Throwable?>(null)
        uncaughtExceptionReporter = { reported.set(it) }
        try {
            SimulaScope.launch { throw IllegalStateException("boom") }

            // The failure propagates to the context handler on a real IO thread.
            withTimeout(2_000) {
                while (reported.get() == null) delay(10)
            }

            assertEquals("boom", reported.get()?.message)
            assertTrue(
                "the supervisor must keep the scope alive after a child failure",
                SimulaScope.coroutineContext[Job]!!.isActive,
            )
        } finally {
            uncaughtExceptionReporter = null
        }
    }

    @Test
    fun `an unwired reporter still swallows the failure (no crash)`() = runBlocking {
        uncaughtExceptionReporter = null
        // Must not throw, cancel the scope, or propagate.
        SimulaScope.launch { throw RuntimeException("silent") }
        delay(100)
        assertTrue(SimulaScope.coroutineContext[Job]!!.isActive)
    }
}
