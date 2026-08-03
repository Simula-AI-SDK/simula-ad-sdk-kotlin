package ad.simula.ad.sdk.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SimulaScopeTest {

    @Test
    fun `failure hook reports once per signature`() {
        var reports = 0
        val reportedTypes = mutableListOf<Class<out Throwable>>()
        val hook = SimulaScopeFailureHook().apply {
            install { _, throwable ->
                reports++
                reportedTypes += throwable.javaClass
            }
        }

        hook.report(IllegalStateException())
        hook.report(IllegalStateException())
        hook.report(IllegalArgumentException())

        assertEquals(2, reports)
        assertEquals(listOf(IllegalStateException::class.java, IllegalArgumentException::class.java), reportedTypes)
    }

    @Test
    fun `failure hook swallows reporter failures`() {
        val hook = SimulaScopeFailureHook().apply { install { _, _ -> error("telemetry failed") } }

        hook.report(IllegalStateException())
    }
}
