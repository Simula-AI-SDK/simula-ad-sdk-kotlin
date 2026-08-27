package ad.simula.ad.sdk.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
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
        override fun audioState() = buildJsonObject { put("muted", true); put("volume", 0) }
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
    fun queuedMessageCannotDispatchAfterInstallationBecomesInactive() {
        val host = FakeHost()
        var queued: (() -> Unit)? = null
        var active = true
        val bridge = CreativeBridge(host, mainDispatch = { queued = it })

        bridge.handle(
            message = """{"type":"AD_EARLY_COMPLETE"}""",
            isActive = { active },
        ) {}
        active = false
        queued?.invoke()

        assertEquals(0, host.earlyCompletes)
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
    fun oversizedMessageIsRejectedBeforeDispatchAndRecorded() {
        val host = FakeHost()
        var dispatches = 0
        var replied = false
        val errors = mutableListOf<String>()
        val rejections = mutableListOf<String>()
        val message = messageOfLength("AD_EARLY_COMPLETE", CREATIVE_BRIDGE_MAX_MESSAGE_UTF16_CHARS) + " "

        CreativeBridge(host, { dispatches++; it() }, { errors += it }, { rejections += it })
            .handle(message) { replied = true }

        assertEquals(CREATIVE_BRIDGE_MAX_MESSAGE_UTF16_CHARS + 1, message.length)
        assertEquals(0, dispatches)
        assertEquals(0, host.earlyCompletes)
        assertFalse(replied)
        assertTrue(errors.isEmpty())
        assertEquals(listOf("too_large"), rejections)
    }

    @Test
    fun unknownAndMalformedMessagesNeverDispatchAndRecordRejections() {
        var dispatches = 0
        var replied = false
        val errors = mutableListOf<String>()
        val rejections = mutableListOf<String>()
        val bridge = CreativeBridge(FakeHost(), { dispatches++; it() }, { errors += it }, { rejections += it })

        bridge.handle("""{"type":"UNKNOWN"}""") { replied = true }
        bridge.handle("not json") { replied = true }

        assertEquals(0, dispatches)
        assertFalse(replied)
        assertTrue(errors.isEmpty())
        assertEquals(listOf("unknown_type", "malformed"), rejections)
    }

    @Test
    fun nativeMessageTypesUseTheSameAdmissionProtection() {
        val exact = messageOfLength("SIMULA_AD_HEIGHT", CREATIVE_BRIDGE_MAX_MESSAGE_UTF16_CHARS)
        val rejections = mutableListOf<String>()

        assertTrue(parseKnownCreativeBridgeMessage(exact, NATIVE_AD_BRIDGE_MESSAGE_TYPES) { rejections += it } != null)
        assertNull(parseKnownCreativeBridgeMessage("$exact ", NATIVE_AD_BRIDGE_MESSAGE_TYPES) { rejections += it })
        assertNull(parseKnownCreativeBridgeMessage("""{"type":"NOT_NATIVE"}""", NATIVE_AD_BRIDGE_MESSAGE_TYPES) { rejections += it })
        assertNull(parseKnownCreativeBridgeMessage("malformed", NATIVE_AD_BRIDGE_MESSAGE_TYPES) { rejections += it })
        assertEquals(listOf("too_large", "unknown_type", "malformed"), rejections)
    }

    @Test
    fun rejectionTelemetryIsBoundedAcrossFullScreenAndNativeMessages() {
        val recorded = mutableListOf<String>()
        val recorder = BoundedBridgeRejectionRecorder { recorded += it }

        repeat(100) {
            parseKnownCreativeBridgeMessage("malformed", FULL_SCREEN_BRIDGE_MESSAGE_TYPES, recorder::record)
            parseKnownCreativeBridgeMessage("malformed", NATIVE_AD_BRIDGE_MESSAGE_TYPES, recorder::record)
        }
        parseKnownCreativeBridgeMessage("{}", FULL_SCREEN_BRIDGE_MESSAGE_TYPES, recorder::record)
        parseKnownCreativeBridgeMessage("""{"type":"UNKNOWN"}""", FULL_SCREEN_BRIDGE_MESSAGE_TYPES, recorder::record)
        parseKnownCreativeBridgeMessage(
            " ".repeat(CREATIVE_BRIDGE_MAX_MESSAGE_UTF16_CHARS + 1),
            NATIVE_AD_BRIDGE_MESSAGE_TYPES,
            recorder::record,
        )

        assertEquals(listOf("malformed", "missing_type", "unknown_type", "too_large"), recorded)
    }

    @Test
    fun trustedCtaMessageRequiresThePresentationNonce() {
        val message = """{"type":"SIMULA_CTA_OPEN","url":"https://tracker.example/click","activation_nonce":"nonce-1"}"""

        assertEquals("https://tracker.example/click", trustedCtaUrl(message, "nonce-1"))
        assertNull(trustedCtaUrl(message, "other-presentation"))
        assertNull(trustedCtaUrl(message, null))
        assertNull("disabled installation rejects an already queued message", trustedCtaUrl(message, "nonce-1", false))
    }

    @Test
    fun disabledReplacementDocumentDoesNotReceiveTrustedCtaRelayNonce() {
        assertEquals("nonce", activeCtaNonce("nonce", disabled = false))
        assertNull(activeCtaNonce("nonce", disabled = true))
        assertNull(activeCtaNonce(null, disabled = false))
    }

    @Test
    fun coreDocumentStartRelaySurvivesWithoutTrustedCtaHooks() {
        val source = BridgeWebViewInstaller.coreRelayScript("17")

        assertTrue(source.contains("window.addEventListener('message'"))
        assertTrue(source.contains("__simulaSdkPageReady:17:"))
        assertFalse(source.contains("SIMULA_CTA_OPEN"))
        assertFalse(source.contains("window.open ="))
        assertFalse(source.contains("activation_nonce"))
    }

    @Test
    fun trustedCtaDocumentScriptContainsOnlyOneShotCaptureLayer() {
        val source = BridgeWebViewInstaller.trustedCtaDocumentStartScript(
            activationNonce = "nonce",
            trustedCtaBaseUrl = "https://creative.example/game",
        )

        assertTrue(source.contains("SIMULA_CTA_OPEN"))
        assertTrue(source.contains("window.open ="))
        assertTrue(source.contains("activation_nonce"))
        assertFalse(source.contains("__simulaSdkPageReady:"))
        assertFalse(source.contains("window.addEventListener('message'"))
    }

    @Test
    fun trustedCtaMessageRejectsMalformedOrNonStringFields() {
        assertNull(trustedCtaUrl("malformed", "nonce"))
        assertNull(trustedCtaUrl("""{"type":"SIMULA_CTA_OPEN","url":7,"activation_nonce":"nonce"}""", "nonce"))
        assertNull(trustedCtaUrl("""{"type":"SIMULA_CTA_OPEN","url":"","activation_nonce":"nonce"}""", "nonce"))
        assertNull(trustedCtaUrl("""{"type":"AD_EARLY_COMPLETE","url":"https://x","activation_nonce":"nonce"}""", "nonce"))
    }

    @Test
    fun trustedCtaMessageBoundsUrlAndWholeEnvelope() {
        val acceptedUrl = "x".repeat(8 * 1024)
        val oversizedUrl = "$acceptedUrl?"

        assertEquals(
            acceptedUrl,
            trustedCtaUrl(
                """{"type":"SIMULA_CTA_OPEN","url":"$acceptedUrl","activation_nonce":"nonce"}""",
                "nonce",
            ),
        )
        assertNull(
            trustedCtaUrl(
                """{"type":"SIMULA_CTA_OPEN","url":"$oversizedUrl","activation_nonce":"nonce"}""",
                "nonce",
            ),
        )
        assertNull(
            trustedCtaUrl(
                """{"type":"SIMULA_CTA_OPEN","url":"https://x","activation_nonce":"nonce","padding":"${"x".repeat(CREATIVE_BRIDGE_MAX_MESSAGE_UTF16_CHARS)}"}""",
                "nonce",
            ),
        )
    }

    @Test
    fun trustedCtaRelayRejectsNonActivationKeysAndCancelledContacts() {
        val source = trustedCtaRelaySource("nonce")

        assertTrue(source.contains("key === 'escape' || key === 'esc' || key === 'dead' || key === 'process'"))
        assertTrue(source.contains("return !isModifierOnlyKey(key)"))
        assertTrue(source.contains("type === 'pointercancel' || type === 'touchcancel'"))
        assertTrue(source.contains("cancelledContact = true"))
        assertTrue(source.contains("if (contactPending || cancelledContact) { return false; }"))
        assertTrue(source.contains("var trustedEventTimestamp = -1"))
        assertTrue(source.contains("trustedEventTimestamp = Number(event.timeStamp || 0)"))
        assertTrue(source.contains("return trustedDispatch && trustedEventTimestamp >= 0"))
        assertTrue(source.contains("if (!awaitingClick) { beginGesture(event); }"))
        assertTrue(source.contains("nativeReceiver.isCtaEnabled('nonce') === true"))
        val duplicateCheck = requireNotNull(source.indexOf("if (claimedGesture === gestureSequence) { return true; }")
            .takeIf { it >= 0 })
        val activeCheck = requireNotNull(source.indexOf("if (!hasActiveUserGesture()) { return false; }")
            .takeIf { it >= 0 })
        assertTrue(duplicateCheck < activeCheck)
    }

    @Test
    fun trustedCtaRelayLeavesSameOriginPopupsToTheWebViewBeforeClaimingTheGesture() {
        val source = trustedCtaRelaySource(
            activationNonce = "nonce",
            trustedCtaBaseUrl = "https://creative.example:443/game",
        )

        assertTrue(source.contains("var trustedCtaBaseUrl = \"https://creative.example:443/game\""))
        assertTrue(source.contains("return target.origin === base.origin"))
        assertTrue(source.contains("new URL(String(value), document.baseURI)"))
        val sameOriginCheck = source.indexOf(
            "if (!url || !trustedCtaBaseUrl || isInternalCta(url) || isSameOriginCta(url)) { return false; }",
        )
        val gestureClaim = source.indexOf("claimedGesture = gestureSequence;")
        assertTrue(sameOriginCheck >= 0)
        assertTrue(gestureClaim >= 0)
        assertTrue(sameOriginCheck < gestureClaim)
    }

    @Test
    fun trustedCtaRelayFailsClosedWithoutOriginAndRejectsInternalDocuments() {
        val source = trustedCtaRelaySource("nonce")

        assertTrue(source.contains("!trustedCtaBaseUrl"))
        assertTrue(source.contains("protocol === 'about:'"))
        assertTrue(source.contains("protocol === 'data:'"))
        assertTrue(source.contains("protocol === 'blob:'"))
        assertTrue(source.contains("protocol === 'javascript:'"))
        val policyCheck = source.indexOf("if (!url || !trustedCtaBaseUrl || isInternalCta(url)")
        val gestureClaim = source.indexOf("claimedGesture = gestureSequence;")
        assertTrue(policyCheck >= 0)
        assertTrue(policyCheck < gestureClaim)
    }

    @Test
    fun getAudioStateReplyShape() {
        val reply = capture("""{"type":"GET_AUDIO_STATE","requestId":"42"}""")
        assertEquals("GET_AUDIO_STATE", reply["type"]!!.jsonPrimitive.content)
        assertEquals("42", reply["requestId"]!!.jsonPrimitive.content)
        assertTrue(reply["requestId"]!!.jsonPrimitive.isString)
        assertTrue(reply["__simulaSdkResponse"]!!.jsonPrimitive.boolean)
        val payload = requireNotNull(reply["payload"]).jsonObject
        assertTrue(requireNotNull(payload["muted"]).jsonPrimitive.boolean)
        assertEquals(0, requireNotNull(payload["volume"]).jsonPrimitive.int)
    }

    @Test
    fun audioStateChangedEventShape() {
        val event = parseScript(
            buildCreativeBridgeMessage(
                AUDIO_STATE_CHANGED,
                CreativeAudioState(muted = false, volume = 42).payload(),
            ),
        )
        assertEquals(AUDIO_STATE_CHANGED, requireNotNull(event["type"]).jsonPrimitive.content)
        assertNull(event["requestId"])
        assertTrue(requireNotNull(event["__simulaSdkResponse"]).jsonPrimitive.boolean)
        val payload = requireNotNull(event["payload"]).jsonObject
        assertFalse(requireNotNull(payload["muted"]).jsonPrimitive.boolean)
        assertEquals(42, requireNotNull(payload["volume"]).jsonPrimitive.int)
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
        return parseScript(raw)
    }

    private fun parseScript(script: String): JsonObject = Json.parseToJsonElement(
        script.removePrefix("window.postMessage(").removeSuffix(", '*');"),
    ).jsonObject

    private fun messageOfLength(type: String, length: Int): String {
        val prefix = "{\"type\":\"$type\",\"padding\":\""
        val suffix = "\"}"
        require(length >= prefix.length + suffix.length)
        return prefix + "x".repeat(length - prefix.length - suffix.length) + suffix
    }
}
