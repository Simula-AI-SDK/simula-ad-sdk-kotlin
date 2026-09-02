package ad.simula.ad.sdk.bridge

import ad.simula.ad.sdk.telemetry.Telemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Maximum creative bridge message size, measured as Kotlin [String.length] UTF-16 code units. */
internal const val CREATIVE_BRIDGE_MAX_MESSAGE_UTF16_CHARS = 64 * 1024
internal const val BRIDGE_CAPABILITY_KEY = "__simulaSdkCapability"

internal val FULL_SCREEN_BRIDGE_MESSAGE_TYPES = setOf(
    "AD_EARLY_COMPLETE",
    "TRIGGER_HAPTIC",
    "SET_ORIENTATION",
    "GET_DEVICE_CONTEXT",
    "GET_AUDIO_STATE",
    "GET_ORIENTATION",
)

internal val NATIVE_AD_BRIDGE_MESSAGE_TYPES = setOf(
    "SIMULA_AD_HEIGHT",
    "AD_RESIZE",
    "AD_FEEDBACK",
)

internal const val AUDIO_STATE_CHANGED = "AUDIO_STATE_CHANGED"

private val creativeBridgeJson = Json { ignoreUnknownKeys = true }

private const val MAX_REPORTED_BRIDGE_REJECTION_REASONS = 4

internal class BoundedBridgeRejectionRecorder(
    private val emit: (String) -> Unit,
) {
    private val lock = Any()
    private val reportedReasons = mutableSetOf<String>()

    fun record(reason: String) {
        val shouldRecord = synchronized(lock) {
            if (reason in reportedReasons || reportedReasons.size >= MAX_REPORTED_BRIDGE_REJECTION_REASONS) {
                false
            } else {
                reportedReasons.add(reason)
            }
        }
        if (shouldRecord) runCatching { emit(reason) }
    }
}

private val bridgeRejectionRecorder = BoundedBridgeRejectionRecorder { reason ->
    Telemetry.recordOperation(
        name = "bridge_message_rejected",
        durationMs = 0L,
        success = false,
        failureClass = reason,
    )
}

private fun recordBridgeMessageRejected(reason: String) = bridgeRejectionRecorder.record(reason)

/** Admits only an envelope authenticated by the current presentation's trusted document relay. */
internal fun authenticatedBridgeMessage(message: String, expectedCapability: String): String? {
    if (message.length > CREATIVE_BRIDGE_MAX_MESSAGE_UTF16_CHARS) return null
    val root = runCatching { creativeBridgeJson.parseToJsonElement(message) as? JsonObject }.getOrNull()
        ?: return null
    val supplied = (root[BRIDGE_CAPABILITY_KEY] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
    return message.takeIf { supplied == expectedCapability }
}

/** Rejects oversized, malformed, non-object, missing-type, and unknown-type messages off-main. */
internal fun parseKnownCreativeBridgeMessage(
    message: String,
    knownTypes: Set<String>,
    recordRejected: (String) -> Unit = ::recordBridgeMessageRejected,
): JsonObject? {
    fun reject(reason: String): JsonObject? {
        runCatching { recordRejected(reason) }
        return null
    }
    if (message.length > CREATIVE_BRIDGE_MAX_MESSAGE_UTF16_CHARS) return reject("too_large")
    val root = runCatching { creativeBridgeJson.parseToJsonElement(message) as? JsonObject }.getOrNull()
        ?: return reject("malformed")
    val type = (root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return reject("missing_type")
    return if (type in knownTypes) root else reject("unknown_type")
}

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
 * `GET_AUDIO_STATE` returns `{ muted, volume }`, where `volume` is the media stream percentage
 * from 0 to 100. [BridgeWebViewInstaller] also emits `AUDIO_STATE_CHANGED` with that payload after
 * each creative page load and whenever the normalized state changes.
 *
 * Parsing runs on the caller's thread; [mainDispatch] hops the actual handling onto the main thread
 * (the Android JS-interface callback arrives on a background thread, and UIKit-equivalent work +
 * the reply must run on main). Tests inject a synchronous dispatcher.
 */
internal class CreativeBridge(
    private val host: BridgeHost,
    private val mainDispatch: (block: () -> Unit) -> Unit,
    private val recordDispatchError: (errorCode: String) -> Unit = { errorCode ->
        Telemetry.recordError(
            signature = "bridge:creative_dispatch",
            errorCode = errorCode,
        )
    },
    private val recordRejectedMessage: (reason: String) -> Unit = ::recordBridgeMessageRejected,
) {
    /**
     * Handle one envelope. [reply] delivers a JS string back into the page (the installer binds it
     * to `webView.evaluateJavascript`). Rejects malformed input or an unknown `type` with telemetry.
     */
    fun handle(
        message: String,
        isActive: () -> Boolean = { true },
        reply: (String) -> Unit,
    ) {
        val root = parseKnownCreativeBridgeMessage(
            message,
            FULL_SCREEN_BRIDGE_MESSAGE_TYPES,
            recordRejectedMessage,
        ) ?: return
        val type = root.str("type") ?: return
        val requestId = root["requestId"] // preserved verbatim so the reply echoes its JSON type
        val payload = root["payload"] as? JsonObject
        try {
            mainDispatch {
                if (!isActive()) return@mainDispatch
                try {
                    process(type, requestId, payload, reply)
                } catch (error: Throwable) {
                    reportDispatchError(error)
                    // The current bridge protocol has no error-reply envelope. In particular,
                    // failed GET_* requests stay silent rather than inventing a wire contract.
                }
            }
        } catch (error: Throwable) {
            reportDispatchError(error)
        }
    }

    private fun reportDispatchError(error: Throwable) {
        runCatching { recordDispatchError(error::class.java.simpleName) }
    }

    private fun process(type: String, requestId: JsonElement?, payload: JsonObject?, reply: (String) -> Unit) {
        when (type) {
            // Events (no reply)
            "AD_EARLY_COMPLETE" -> host.earlyComplete()

            // Commands (no reply)
            "TRIGGER_HAPTIC" -> payload?.str("style")?.let(host::haptic)
            "SET_ORIENTATION" -> payload?.str("orientation")?.let(host::setOrientation)

            // Queries (request/response)
            "GET_DEVICE_CONTEXT" -> reply(buildCreativeBridgeMessage(type, host.deviceContext(), requestId))
            "GET_AUDIO_STATE" -> reply(buildCreativeBridgeMessage(type, host.audioState(), requestId))
            "GET_ORIENTATION" -> reply(buildCreativeBridgeMessage(type, host.currentOrientation(), requestId))
        }
        Telemetry.recordOperation("bridge_${type.lowercase()}", 0L, true)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** Builds a query reply or SDK-originated event that the page relay will not echo back to native. */
internal fun buildCreativeBridgeMessage(
    type: String,
    payload: JsonObject,
    requestId: JsonElement? = null,
): String {
    val obj = buildJsonObject {
        put("type", type)
        if (requestId != null) put("requestId", requestId)
        put("payload", payload)
        put("__simulaSdkResponse", true)
    }
    // JsonObject.toString() emits valid JSON, hence a valid JS object literal. The server-rendered
    // creative is normally a direct srcdoc child of a synthetic top document, so deliver to both
    // supported document shapes without exposing bridge data to nested third-party frames.
    return """
        (function () {
            'use strict';
            if (window !== window.top) { return; }
            var message = $obj;
            function deliver(target) {
                try { if (target) { target.postMessage(message, '*'); } } catch (e) {}
            }
            deliver(window);
            var frame;
            try { frame = document.querySelector('iframe[srcdoc]'); }
            catch (e) { return; }
            var target = null;
            try {
                target = frame && frame.contentWindow;
                if (!target || String(target.location.href) !== 'about:srcdoc') { return; }
            } catch (e) { return; }
            deliver(target);
        })();
    """.trimIndent()
}
