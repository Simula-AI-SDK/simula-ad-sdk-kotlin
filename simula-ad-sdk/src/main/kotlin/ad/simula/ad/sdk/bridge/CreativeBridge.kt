package ad.simula.ad.sdk.bridge

import ad.simula.ad.sdk.telemetry.Telemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The platform side of the WebView ↔ SDK bridge (PRD §3). Implemented by [AndroidBridgeHost]
 * (real device) and by fakes in tests, so [CreativeBridge]'s parsing / routing / reply logic is
 * exercised on the plain JVM without any Android framework.
 *
 * `GET_*` queries return their reply payload as a [JsonObject]; [CreativeBridge] wraps it in the
 * shared response envelope.
 */
internal interface BridgeHost {
    fun earlyComplete()
    fun haptic(style: String)
    fun setOrientation(orientation: String)
    fun deviceContext(): JsonObject
    fun audioState(): JsonObject
    fun currentOrientation(): JsonObject
}

/**
 * Routes one `window.postMessage` envelope `{ type, requestId?, payload? }` from an HTML creative
 * to a native action via [host], and — for `GET_*` queries — posts a reply back into the page
 * echoing the same `requestId` (PRD §3).
 *
 * Parsing runs on the caller's thread; [mainDispatch] hops the actual handling onto the main thread
 * (the Android JS-interface callback arrives on a background thread, and UIKit-equivalent work +
 * the reply must run on main). Tests inject a synchronous dispatcher.
 */
internal class CreativeBridge(
    private val host: BridgeHost,
    private val mainDispatch: (block: () -> Unit) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Handle one envelope. [reply] delivers a JS string back into the page (the installer binds it
     * to `webView.evaluateJavascript`). No-ops silently on malformed input or an unknown `type`.
     */
    fun handle(message: String, reply: (String) -> Unit) {
        val root = runCatching { json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return
        val type = root.str("type") ?: return
        val requestId = root["requestId"] // preserved verbatim so the reply echoes its JSON type
        val payload = root["payload"] as? JsonObject
        mainDispatch { process(type, requestId, payload, reply) }
    }

    private fun process(type: String, requestId: JsonElement?, payload: JsonObject?, reply: (String) -> Unit) {
        when (type) {
            // Events (no reply)
            "AD_EARLY_COMPLETE" -> host.earlyComplete()

            // Commands (no reply)
            "TRIGGER_HAPTIC" -> payload?.str("style")?.let(host::haptic)
            "SET_ORIENTATION" -> payload?.str("orientation")?.let(host::setOrientation)

            // Queries (request/response)
            "GET_DEVICE_CONTEXT" -> reply(buildReply(type, requestId, host.deviceContext()))
            "GET_AUDIO_STATE" -> reply(buildReply(type, requestId, host.audioState()))
            "GET_ORIENTATION" -> reply(buildReply(type, requestId, host.currentOrientation()))

            else -> return // unknown type: ignore (no telemetry)
        }
        Telemetry.recordOperation("bridge_${type.lowercase()}", 0L, true)
    }

    /**
     * Builds `window.postMessage({ type, requestId, payload, __simulaSdkResponse: true }, '*');`.
     * The injected relay drops messages carrying `__simulaSdkResponse`, so this reply reaches the
     * creative without being echoed back to native.
     */
    private fun buildReply(type: String, requestId: JsonElement?, payload: JsonObject): String {
        val obj = buildJsonObject {
            put("type", type)
            if (requestId != null) put("requestId", requestId)
            put("payload", payload)
            put("__simulaSdkResponse", true)
        }
        // JsonObject.toString() emits valid JSON, hence a valid JS object literal.
        return "window.postMessage($obj, '*');"
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
