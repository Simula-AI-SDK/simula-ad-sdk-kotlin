package ad.simula.ad.sdk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulaCrashGuardFingerprintTest {

    @Test
    fun `binary exit trace is not interpreted as text`() {
        val protobufLike = byteArrayOf(0x0a, 0x03, 0x00, 0x10, 0x01)

        assertNull(decodeTextExitTrace(protobufLike))
    }

    @Test
    fun `strict utf8 exit trace remains parseable`() {
        val trace = "\"main\"\n  at ad.simula.ad.sdk.ads.Loader.load(Loader.kt:7)"

        assertEquals(trace, decodeTextExitTrace(trace.toByteArray()))
    }

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
    fun `trace canonicalization removes volatile addresses and keeps only strict SDK frames`() {
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
        assertTrue(anrMainThreadSdkFrames(sdkWorkerOnly).isEmpty())
        assertTrue(anrMainThreadInvolvesSdk(sdkOnMain))
    }

    @Test
    fun `same main ANR ignores changing SDK worker frames`() {
        val first = """
            "main" prio=5
              at ad.simula.ad.sdk.nativead.NativeAdSlot.render(NativeAdSlot.kt:42)
              at com.host.Home.block(Home.kt:3)
            "SimulaScope-worker" prio=5
              at ad.simula.ad.sdk.network.SimulaHttp.first(SimulaHttp.kt:8)
        """.trimIndent()
        val second = first.replace(
            "ad.simula.ad.sdk.network.SimulaHttp.first(SimulaHttp.kt:8)",
            "ad.simula.ad.sdk.telemetry.Telemetry.second(Telemetry.kt:99)",
        )

        val firstFrames = anrMainThreadSdkFrames(first)
        val secondFrames = anrMainThreadSdkFrames(second)
        assertEquals(firstFrames, secondFrames)
        assertEquals(canonicalFrameMessage(firstFrames), canonicalFrameMessage(secondFrames))
        assertEquals(canonicalFingerprint(firstFrames), canonicalFingerprint(secondFrames))
    }

    @Test
    fun `strict package matching rejects near prefixes`() {
        val throwable = IllegalStateException("host text").apply {
            stackTrace = arrayOf(
                StackTraceElement("ad.simula.ad.sdkfoo.Fake", "run", "Fake.kt", 1),
                StackTraceElement("com.host.ad.simula.ad.sdk.Fake", "run", "Fake.kt", 2),
            )
        }
        val trace = """
            "main"
              at ad.simula.ad.sdkfoo.Fake.run(Fake.kt:1)
              at com.host.ad.simula.ad.sdk.Fake.run(Fake.kt:2)
              java.lang.IllegalStateException: host text ad.simula.ad.sdk.network.SimulaHttp.request
        """.trimIndent()

        assertTrue(canonicalSdkFrames(throwable).isEmpty())
        assertTrue(canonicalSdkFrames(trace).isEmpty())
        assertFalse(anrMainThreadInvolvesSdk(trace))
    }

    @Test
    fun `uncaught payload fields contain canonical SDK frames and no host text`() {
        val throwable = IllegalStateException("private host exception text").apply {
            stackTrace = arrayOf(
                StackTraceElement("com.host.Checkout", "submit", "Checkout.kt", 7),
                StackTraceElement("ad.simula.ad.sdk.network.SimulaHttp", "request", "SimulaHttp.kt", 8),
            )
        }

        val frames = canonicalSdkFrames(throwable)
        val message = canonicalFrameMessage(frames)
        val fingerprint = canonicalFingerprint(frames)

        assertEquals(listOf("network.SimulaHttp.request(SimulaHttp.kt:8)"), frames)
        assertEquals("network.SimulaHttp.request(SimulaHttp.kt:8)", message)
        assertFalse(message.orEmpty().contains("private host exception text"))
        assertFalse(message.orEmpty().contains("com.host"))
        assertEquals(fnv1a64Hex(frames), fingerprint)
    }

    @Test
    fun `empty canonical frames produce no message or fingerprint`() {
        val frames = canonicalSdkFrames(
            IllegalStateException("host only").apply {
                stackTrace = arrayOf(StackTraceElement("com.host.Home", "run", "Home.kt", 1))
            },
        )

        assertTrue(frames.isEmpty())
        assertNull(canonicalFrameMessage(frames))
        assertNull(canonicalFingerprint(frames))
    }

    @Test
    fun `native crash ignores SDK workers outside the faulting thread section`() {
        val workerOnly = """
            pid: 123, tid: 123, name: main  >>> com.host.app <<<
            backtrace:
              #00 pc 0001 (com.host.Native.crash(Native.kt:1))
            pid: 123, tid: 456, name: SimulaScope-worker  >>> com.host.app <<<
            backtrace:
              #00 pc 0002 (ad.simula.ad.sdk.network.SimulaHttp.request(SimulaHttp.kt:8))
        """.trimIndent()
        val sdkFaulting = """
            pid: 123, tid: 456, name: SimulaScope-worker  >>> com.host.app <<<
            backtrace:
              #00 pc 0002 (ad.simula.ad.sdk.network.SimulaHttp.request(SimulaHttp.kt:8))
            pid: 123, tid: 123, name: main  >>> com.host.app <<<
              #00 pc 0001 (com.host.Native.crash(Native.kt:1))
        """.trimIndent()
        val ambiguous = """
            "crashed-SimulaScope-worker"
              at ad.simula.ad.sdk.network.SimulaHttp.request(SimulaHttp.kt:8)
        """.trimIndent()

        assertTrue(nativeCrashedThreadSdkFrames(workerOnly).isEmpty())
        assertEquals(
            listOf("ad.simula.ad.sdk.network.SimulaHttp.request(SimulaHttp.kt:8)"),
            nativeCrashedThreadSdkFrames(sdkFaulting),
        )
        assertTrue(nativeCrashedThreadSdkFrames(ambiguous).isEmpty())
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
