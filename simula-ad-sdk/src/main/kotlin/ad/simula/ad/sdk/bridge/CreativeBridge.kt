package ad.simula.ad.sdk.bridge

import ad.simula.ad.sdk.telemetry.Telemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean

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

internal data class CreativeBridgeLimits(
    val maxMessageChars: Int = 64 * 1024,
    val maxDispatchesPerWindow: Int = 60,
    val windowMs: Long = 1_000L,
    val maxPendingDispatches: Int = 16,
)

internal enum class CreativeBridgeHandleResult {
    DISPATCHED,
    REJECTED,
    OVERLOADED,
    DISPATCH_FAILED,
}

/** Thread-safe admission control for calls arriving concurrently on WebView JavaBridge threads. */
private class CreativeBridgeDispatchLimiter(
    private val limits: CreativeBridgeLimits,
    private val clock: () -> Long,
) {
    private val lock = Any()
    private var windowStartMs = clock()
    private var dispatchesInWindow = 0
    private var pendingDispatches = 0

    fun tryAcquire(): Boolean = synchronized(lock) {
        val now = clock()
        if (now < windowStartMs || now - windowStartMs >= limits.windowMs) {
            windowStartMs = now
            dispatchesInWindow = 0
        }
        if (dispatchesInWindow >= limits.maxDispatchesPerWindow ||
            pendingDispatches >= limits.maxPendingDispatches
        ) {
            return@synchronized false
        }
        dispatchesInWindow += 1
        pendingDispatches += 1
        true
    }

    fun release() {
        synchronized(lock) {
            if (pendingDispatches > 0) pendingDispatches -= 1
        }
    }
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
    private val limits: CreativeBridgeLimits = CreativeBridgeLimits(),
    clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val mainDispatch: (block: () -> Unit) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val limiter = CreativeBridgeDispatchLimiter(limits, clock)

    /**
     * Handle one envelope. [reply] delivers a JS string back into the page (the installer binds it
     * to `webView.evaluateJavascript`). Malformed/unknown messages are rejected. An overloaded
     * `GET_*` intentionally receives no error reply: every reply itself requires a WebView main-loop
     * post, so replying to an attacker-controlled rejected flood would recreate the unbounded queue
     * this limiter protects. Creatives must retain their own request timeout/fallback.
     */
    fun handle(message: String, reply: (String) -> Unit): CreativeBridgeHandleResult {
        if (message.length > limits.maxMessageChars) return CreativeBridgeHandleResult.REJECTED
        val root = runCatching { json.parseToJsonElement(message) as? JsonObject }.getOrNull()
            ?: return CreativeBridgeHandleResult.REJECTED
        val type = root.str("type") ?: return CreativeBridgeHandleResult.REJECTED
        if (type !in SUPPORTED_TYPES) return CreativeBridgeHandleResult.REJECTED
        val requestId = root["requestId"] // preserved verbatim so the reply echoes its JSON type
        val payload = root["payload"] as? JsonObject
        if (!limiter.tryAcquire()) return CreativeBridgeHandleResult.OVERLOADED
        val released = AtomicBoolean(false)
        fun releasePermit() {
            if (released.compareAndSet(false, true)) limiter.release()
        }
        // The one unguarded @JavascriptInterface dispatch path: any throw from a `host.*` call
        // (framework quirk, OEM bug) would otherwise be an uncaught main-thread exception that
        // kills the host. Mirror the native-ad bridge's defensive posture — absorb + report.
        try {
            mainDispatch {
                try {
                    process(type, requestId, payload, reply)
                } catch (t: Throwable) {
                    runCatching {
                        Telemetry.recordError(
                            signature = "bridge:creative_dispatch",
                            errorCode = t.javaClass.simpleName,
                            message = "creative bridge dispatch failed",
                            breadcrumb = "type=$type",
                        )
                    }
                    // A failed GET_* must still reply: the creative holds a pending promise on
                    // this requestId and would otherwise hang for the page's lifetime. The error
                    // payload resolves it so the creative falls back to its own defaults.
                    if (type.startsWith("GET_")) {
                        runCatching {
                            reply(buildReply(type, requestId, buildJsonObject { put("error", "native_dispatch_failed") }))
                        }
                    }
                } finally {
                    releasePermit()
                }
            }
            return CreativeBridgeHandleResult.DISPATCHED
        } catch (_: Throwable) {
            // A dispatcher/Looper failure must not leak the pending-work permit forever.
            releasePermit()
            return CreativeBridgeHandleResult.DISPATCH_FAILED
        }
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

    private companion object {
        val SUPPORTED_TYPES = setOf(
            "AD_EARLY_COMPLETE",
            "TRIGGER_HAPTIC",
            "SET_ORIENTATION",
            "GET_DEVICE_CONTEXT",
            "GET_AUDIO_STATE",
            "GET_ORIENTATION",
        )
    }
}
