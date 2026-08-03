package ad.simula.ad.sdk.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SimulaScopeTest {

    @Test
    fun `failure hook reports at most once`() {
        var reports = 0
        var reportedType: Class<out Throwable>? = null
        val hook = SimulaScopeFailureHook().apply {
            install {
                reports++
                reportedType = it.javaClass
            }
        }

        hook.report(IllegalStateException())
        hook.report(IllegalArgumentException())

        assertEquals(1, reports)
        assertEquals(IllegalStateException::class.java, reportedType)
    }

    @Test
    fun `failure hook swallows reporter failures`() {
        val hook = SimulaScopeFailureHook().apply { install { error("telemetry failed") } }

        hook.report(IllegalStateException())
    }
}
