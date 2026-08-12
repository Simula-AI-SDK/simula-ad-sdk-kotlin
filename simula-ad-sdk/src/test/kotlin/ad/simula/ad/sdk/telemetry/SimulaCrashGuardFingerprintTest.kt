package ad.simula.ad.sdk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulaCrashGuardFingerprintTest {

    @Test
    fun `fnv1a fingerprint is deterministic fixed width and frame bounded`() {
        assertEquals("5863d9458c1038de", fnv1a64Hex(listOf("foo", "bar")))

        val tenFrames = (0 until 10).map { "nativead.C$it.m(C$it.kt:$it)" }
        assertEquals("2a9bdc700606d5ed", fnv1a64Hex(tenFrames))
        assertEquals(fnv1a64Hex(tenFrames.take(8)), fnv1a64Hex(tenFrames))
        assertEquals(16, fnv1a64Hex(tenFrames).length)
    }

    @Test
    fun `throwable canonicalization keeps only the first eight SDK frames across causes`() {
        val root = IllegalStateException("root").apply {
            stackTrace = arrayOf(
                StackTraceElement("com.host.Screen", "render", "Screen.kt", 1),
                *(0 until 7).map {
                    StackTraceElement("ad.simula.ad.sdk.nativead.Card$it", "render", "Card.kt", it)
                }.toTypedArray(),
            )
        }
        val cause = IllegalArgumentException("cause").apply {
            stackTrace = arrayOf(
                StackTraceElement("ad.simula.ad.sdk.minigame.Game", "load", "Game.kt", 88),
                StackTraceElement("ad.simula.ad.sdk.minigame.Game", "show", "Game.kt", 99),
            )
        }
        root.initCause(cause)

        val frames = canonicalSdkFrames(root)

        assertEquals(8, frames.size)
        assertTrue(frames.first().startsWith("nativead.Card0.render"))
        assertTrue(frames.last().startsWith("minigame.Game.load"))
        assertFalse(frames.any { it.contains("com.host") })
    }

    @Test
    fun `native trace canonicalization removes volatile addresses and ignores host threads`() {
        val trace = """
            "main"
              at com.host.Home.block(Home.kt:3)
              at ad.simula.ad.sdk.nativead.NativeAdSlot.render(NativeAdSlot.kt:42) 0x7ffeeabc
            "worker"
              at ad.simula.ad.sdk.minigame.GameWebView.load(GameWebView.kt:9)
        """.trimIndent()

        assertEquals(
            listOf(
                "ad.simula.ad.sdk.nativead.NativeAdSlot.render(NativeAdSlot.kt:42) 0x?",
                "ad.simula.ad.sdk.minigame.GameWebView.load(GameWebView.kt:9)",
            ),
            canonicalSdkFrames(trace),
        )
    }

    @Test
    fun `anr attribution requires an SDK frame on the main thread`() {
        val sdkWorkerOnly = """
            "main"
              at com.host.Home.block(Home.kt:3)
            "SimulaScope-worker"
              at ad.simula.ad.sdk.network.SimulaHttp.request(SimulaHttp.kt:8)
        """.trimIndent()
        val sdkOnMain = """
            "main"
              at ad.simula.ad.sdk.nativead.NativeAdSlot.render(NativeAdSlot.kt:42)
            "worker"
              at com.host.Background.run(Background.kt:4)
        """.trimIndent()

        assertFalse(anrMainThreadInvolvesSdk(sdkWorkerOnly))
        assertTrue(anrMainThreadInvolvesSdk(sdkOnMain))
    }

    @Test
    fun `generic aggregation remains name only while persisted context isolates sites`() {
        assertEquals("api:decode", telemetryErrorAggregationKey("api:decode", "surface=native", null))
        assertFalse(
            telemetryErrorAggregationKey(
                "exit:anr",
                "fatal=anr;fp=0123456789abcdef",
                listOf("nativead.A.block(A.kt:1)"),
            ) == telemetryErrorAggregationKey(
                "exit:anr",
                "fatal=anr;fp=fedcba9876543210",
                listOf("nativead.B.block(B.kt:2)"),
            ),
        )
    }
}
