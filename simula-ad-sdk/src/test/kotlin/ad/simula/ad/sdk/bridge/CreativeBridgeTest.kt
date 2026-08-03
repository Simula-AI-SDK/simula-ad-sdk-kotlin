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
import org.junit.Assert.assertNull
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
    private fun bridge(host: BridgeHost) = CreativeBridge(host, mainDispatch = { it() })

    private class HostFailure : RuntimeException("must not be reported")

    private class ReplyFailure : RuntimeException("must not be reported")

    private class DispatchFailure : RuntimeException("must not be reported")

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
    fun exactUtf16CapIsAdmitted() {
        val host = FakeHost()
        var dispatches = 0
        val errors = mutableListOf<String>()
        val message = messageOfLength("AD_EARLY_COMPLETE", CREATIVE_BRIDGE_MAX_MESSAGE_UTF16_CHARS)

        CreativeBridge(host, { dispatches++; it() }, { errors += it }).handle(message) {}

        assertEquals(CREATIVE_BRIDGE_MAX_MESSAGE_UTF16_CHARS, message.length)
        assertEquals(1, dispatches)
        assertEquals(1, host.earlyCompletes)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun oversizedMessageIsRejectedBeforeDispatchWithoutTelemetryCallback() {
        val host = FakeHost()
        var dispatches = 0
        var replied = false
        val errors = mutableListOf<String>()
        val message = messageOfLength("AD_EARLY_COMPLETE", CREATIVE_BRIDGE_MAX_MESSAGE_UTF16_CHARS) + " "

        CreativeBridge(host, { dispatches++; it() }, { errors += it }).handle(message) { replied = true }

        assertEquals(CREATIVE_BRIDGE_MAX_MESSAGE_UTF16_CHARS + 1, message.length)
        assertEquals(0, dispatches)
        assertEquals(0, host.earlyCompletes)
        assertFalse(replied)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun unknownAndMalformedMessagesNeverDispatchOrReport() {
        var dispatches = 0
        var replied = false
        val errors = mutableListOf<String>()
        val bridge = CreativeBridge(FakeHost(), { dispatches++; it() }, { errors += it })

        bridge.handle("""{"type":"UNKNOWN"}""") { replied = true }
        bridge.handle("not json") { replied = true }

        assertEquals(0, dispatches)
        assertFalse(replied)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun nativeMessageTypesUseTheSameAdmissionProtection() {
        val exact = messageOfLength("SIMULA_AD_HEIGHT", CREATIVE_BRIDGE_MAX_MESSAGE_UTF16_CHARS)

        assertTrue(parseKnownCreativeBridgeMessage(exact, NATIVE_AD_BRIDGE_MESSAGE_TYPES) != null)
        assertNull(parseKnownCreativeBridgeMessage("$exact ", NATIVE_AD_BRIDGE_MESSAGE_TYPES))
        assertNull(parseKnownCreativeBridgeMessage("""{"type":"NOT_NATIVE"}""", NATIVE_AD_BRIDGE_MESSAGE_TYPES))
        assertNull(parseKnownCreativeBridgeMessage("malformed", NATIVE_AD_BRIDGE_MESSAGE_TYPES))
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

    @Test
    fun hostFailureIsAbsorbedAndRecordedOnceWithoutQueryReply() {
        val errorCodes = mutableListOf<String>()
        val host = object : BridgeHost by FakeHost() {
            override fun deviceContext(): JsonObject = throw HostFailure()
        }
        var reply: String? = null

        CreativeBridge(host, { it() }, { errorCodes += it }).handle(
            """{"type":"GET_DEVICE_CONTEXT","requestId":"private-id"}""",
        ) { reply = it }

        assertNull(reply)
        assertEquals(listOf("HostFailure"), errorCodes)
    }

    @Test
    fun replyFailureIsAbsorbedAndRecordedOnce() {
        val errorCodes = mutableListOf<String>()

        CreativeBridge(FakeHost(), { it() }, { errorCodes += it }).handle(
            """{"type":"GET_AUDIO_STATE","requestId":7}""",
        ) { throw ReplyFailure() }

        assertEquals(listOf("ReplyFailure"), errorCodes)
    }

    @Test
    fun dispatcherFailureIsAbsorbedAndRecordedOnce() {
        val errorCodes = mutableListOf<String>()

        CreativeBridge(FakeHost(), { throw DispatchFailure() }, { errorCodes += it }).handle(
            """{"type":"AD_EARLY_COMPLETE"}""",
        ) {}

        assertEquals(listOf("DispatchFailure"), errorCodes)
    }

    /** Drives a query and parses the `window.postMessage(<json>, '*');` reply into a [JsonObject]. */
    private fun capture(message: String): JsonObject {
        var js: String? = null
        bridge(FakeHost()).handle(message) { js = it }
        val raw = requireNotNull(js) { "no reply for: $message" }
        val json = raw.removePrefix("window.postMessage(").removeSuffix(", '*');")
        return Json.parseToJsonElement(json).jsonObject
    }

    private fun messageOfLength(type: String, length: Int): String {
        val prefix = "{\"type\":\"$type\",\"padding\":\""
        val suffix = "\"}"
        require(length >= prefix.length + suffix.length)
        return prefix + "x".repeat(length - prefix.length - suffix.length) + suffix
    }
}
