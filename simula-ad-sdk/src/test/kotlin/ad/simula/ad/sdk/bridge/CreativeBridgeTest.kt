package ad.simula.ad.sdk.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the WebView ↔ SDK bridge dispatch logic (PRD §3): event/command/query routing and
 * the `window.postMessage` reply envelope. Uses a fake [BridgeHost] + synchronous dispatcher, so it
 * runs on the plain JVM without any Android framework.
 */
class CreativeBridgeTest {

    /** Records routed calls; returns fixed payloads for the `GET_*` queries. */
    private class FakeHost : BridgeHost {
        var earlyCompletes = 0
        var hapticStyle: String? = null
        var orientationSet: String? = null

        override fun earlyComplete() { earlyCompletes++ }
        override fun haptic(style: String) { hapticStyle = style }
        override fun setOrientation(orientation: String) { orientationSet = orientation }
        override fun deviceContext() = buildJsonObject {
            put("darkMode", true); put("locale", "en-US"); put("osVersion", "14")
        }
        override fun audioState() = buildJsonObject { put("muted", true) }
        override fun currentOrientation() = buildJsonObject { put("orientation", "portrait") }
    }

    /** Bridge with a synchronous main dispatcher so `handle` resolves inline. */
    private fun bridge(host: BridgeHost) = CreativeBridge(host) { it() }

    @Test
    fun earlyCompleteRoutesAndDoesNotReply() {
        val host = FakeHost()
        var replied = false
        bridge(host).handle("""{"type":"AD_EARLY_COMPLETE"}""") { replied = true }
        assertEquals(1, host.earlyCompletes)
        assertFalse("events must not reply", replied)
    }

    @Test
    fun commandsRouteWithoutReply() {
        val host = FakeHost()
        var replied = false
        val b = bridge(host)
        b.handle("""{"type":"TRIGGER_HAPTIC","payload":{"style":"success"}}""") { replied = true }
        b.handle("""{"type":"SET_ORIENTATION","payload":{"orientation":"landscape"}}""") { replied = true }
        assertEquals("success", host.hapticStyle)
        assertEquals("landscape", host.orientationSet)
        assertFalse("commands must not reply", replied)
    }

    @Test
    fun malformedAndUnknownIgnored() {
        val host = FakeHost()
        var replied = false
        val b = bridge(host)
        b.handle("not json") { replied = true }
        b.handle("""{"noType":1}""") { replied = true }
        b.handle("""{"type":"NOPE","requestId":"1"}""") { replied = true }
        assertFalse(replied)
        assertEquals(0, host.earlyCompletes)
    }

    @Test
    fun getAudioStateReplyShape() {
        val reply = capture("""{"type":"GET_AUDIO_STATE","requestId":"42"}""")
        assertEquals("GET_AUDIO_STATE", reply["type"]!!.jsonPrimitive.content)
        assertEquals("42", reply["requestId"]!!.jsonPrimitive.content)
        assertTrue(reply["requestId"]!!.jsonPrimitive.isString)
        assertTrue(reply["__simulaSdkResponse"]!!.jsonPrimitive.boolean)
        assertTrue(reply["payload"]!!.jsonObject["muted"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun getOrientationEchoesNumericRequestId() {
        val reply = capture("""{"type":"GET_ORIENTATION","requestId":7}""")
        // The numeric requestId is echoed back as a number, not a string.
        assertEquals("7", reply["requestId"]!!.jsonPrimitive.content)
        assertFalse(reply["requestId"]!!.jsonPrimitive.isString)
        assertEquals("portrait", reply["payload"]!!.jsonObject["orientation"]!!.jsonPrimitive.content)
    }

    @Test
    fun getDeviceContextReplyShape() {
        val reply = capture("""{"type":"GET_DEVICE_CONTEXT","requestId":"ctx"}""")
        val payload = reply["payload"]!!.jsonObject
        assertTrue(payload["darkMode"]!!.jsonPrimitive.boolean)
        assertEquals("en-US", payload["locale"]!!.jsonPrimitive.content)
        assertEquals("14", payload["osVersion"]!!.jsonPrimitive.content)
    }

    /** Drives a query and parses the `window.postMessage(<json>, '*');` reply into a [JsonObject]. */
    private fun capture(message: String): JsonObject {
        var js: String? = null
        bridge(FakeHost()).handle(message) { js = it }
        val raw = requireNotNull(js) { "no reply for: $message" }
        val json = raw.removePrefix("window.postMessage(").removeSuffix(", '*');")
        return Json.parseToJsonElement(json).jsonObject
    }

    @Test
    fun aThrowingHostCallIsAbsorbedByTheDispatch() {
        // Regression (API-26 orientation crash class): a `host.*` sink throwing a framework
        // exception must be absorbed + reported — never escape as an uncaught main-thread throw.
        val host = object : BridgeHost by FakeHost() {
            override fun setOrientation(orientation: String) {
                throw IllegalStateException("Only fullscreen opaque activities can request orientation")
            }
        }
        var completed = false
        bridge(host).handle("""{"type":"SET_ORIENTATION","payload":{"orientation":"landscape"}}""") {}
        completed = true
        assertTrue("dispatch must absorb host failures", completed)
    }

    @Test
    fun aThrowingGetHandlerStillReplies() {
        // Regression: a GET_* query whose host call throws must STILL reply — the creative
        // holds a pending promise on the requestId and would otherwise hang for the page's
        // lifetime. The error payload resolves it so the creative uses its own defaults.
        val host = object : BridgeHost by FakeHost() {
            override fun deviceContext(): JsonObject {
                throw IllegalStateException("framework blew up")
            }
        }
        var js: String? = null
        bridge(host).handle("""{"type":"GET_DEVICE_CONTEXT","requestId":"ctx1"}""") { js = it }
        val raw = requireNotNull(js) { "a failed GET_* query must still reply" }
        val reply = Json.parseToJsonElement(raw.removePrefix("window.postMessage(").removeSuffix(", '*');")).jsonObject
        assertEquals("GET_DEVICE_CONTEXT", reply["type"]!!.jsonPrimitive.content)
        assertEquals("ctx1", reply["requestId"]!!.jsonPrimitive.content)
        assertTrue(reply["__simulaSdkResponse"]!!.jsonPrimitive.boolean)
        assertEquals("native_dispatch_failed", reply["payload"]!!.jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun aThrowingCommandDoesNotReply() {
        // Events/commands have no pending promise — replying would just echo noise into the page.
        val host = object : BridgeHost by FakeHost() {
            override fun haptic(style: String) {
                throw IllegalStateException("no vibrator")
            }
        }
        var replied = false
        bridge(host).handle("""{"type":"TRIGGER_HAPTIC","payload":{"style":"success"}}""") { replied = true }
        assertFalse("commands must not reply even on failure", replied)
    }
}
