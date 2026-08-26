package ad.simula.ad.sdk.bridge

import ad.simula.ad.sdk.telemetry.Telemetry
import ad.simula.ad.sdk.minigame.WebViewPool
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val PAGE_READY_PREFIX = "__simulaSdkPageReady:"
private const val PAGE_READY_MAX_CHARS = 256
private const val AUDIO_CHANGE_COALESCE_MS = 250L
private const val TRUSTED_CTA_OPEN = "SIMULA_CTA_OPEN"
private const val MAX_CTA_URL_CHARS = 8 * 1024
private val installerJson = Json { ignoreUnknownKeys = true }

/**
 * Wires the WebView ↔ SDK bridge (PRD §3) onto a creative [WebView]: a `@JavascriptInterface`
 * receiver plus an injected document-start relay that forwards `window.postMessage` envelopes to
 * native (skipping the SDK's own `__simulaSdkResponse` replies, so they aren't echoed back).
 *
 * WebViews are pooled and recycled ([ad.simula.ad.sdk.minigame.WebViewPool]), so [install] first
 * clears any wiring left from this view's previous use — otherwise document-start scripts would
 * accumulate and a stale bridge would linger. Pair with [uninstall] on release for hygiene.
 */
internal object BridgeWebViewInstaller {

    private const val NATIVE_OBJECT = "SimulaBridgeNative"
    private var nextInstallationId = 0L

    /** Document-start scripts added per web view, so they can be removed on re-install / release. */
    private val scripts = WeakHashMap<WebView, ScriptHandler>()
    private val installations = WeakHashMap<WebView, BridgeInstallation>()

    /**
     * Relay installed in the page: forwards `window.postMessage` payloads to the native receiver,
     * dropping the SDK's own query replies (marked `__simulaSdkResponse`). Mirrors the iOS
     * `WebViewPool.postMessageScript`.
     */
    private fun relayScript(installationId: String, activationNonce: String?): String {
        val ctaRelay = if (activationNonce == null) "" else """
            var originalOpen = window.open;
            var ctaDisabled = false;
            var trustedDispatch = false;
            var gestureSequence = 0;
            var claimedGesture = -1;
            var awaitingClick = false;
            var capturedUserActivation = navigator.userActivation;
            var resolvedPromise = Promise.resolve();
            var nativePromiseThen = Promise.prototype.then;

            function clearTrustedDispatchLater() {
                nativePromiseThen.call(resolvedPromise, function () { trustedDispatch = false; });
            }
            function beginGesture() {
                gestureSequence += 1;
                awaitingClick = true;
            }
            function observeTrustedEvent(event) {
                if (!event || event.isTrusted !== true) { return; }
                trustedDispatch = true;
                clearTrustedDispatchLater();
                if (event.type === 'pointerdown' ||
                    (event.type === 'keydown' && event.repeat !== true)) {
                    beginGesture();
                } else if (event.type === 'click') {
                    if (!awaitingClick) { beginGesture(); }
                    awaitingClick = false;
                } else if (event.type === 'pointercancel') {
                    awaitingClick = false;
                }
            }
            ['click', 'pointerdown', 'pointerup', 'pointercancel', 'mousedown', 'touchend', 'keydown']
                .forEach(function (name) {
                    window.addEventListener(name, observeTrustedEvent, true);
                });
            function hasActiveUserGesture() {
                return trustedDispatch ||
                    !!(capturedUserActivation && capturedUserActivation.isActive === true);
            }
            function resolvedUrl(value) {
                if (value === undefined || value === null) { return null; }
                try { return new URL(String(value), document.baseURI).href; }
                catch (_) { return null; }
            }
            function forwardTrustedCta(value) {
                if (ctaDisabled) { return false; }
                if (gestureSequence === 0 || !hasActiveUserGesture()) { return false; }
                var url = resolvedUrl(value);
                if (!url) { return false; }
                if (claimedGesture === gestureSequence) { return true; }
                claimedGesture = gestureSequence;
                try {
                    nativePost(nativeStringify({
                        type: '$TRUSTED_CTA_OPEN',
                        url: url,
                        activation_nonce: '$activationNonce'
                    }));
                    return true;
                } catch (_) {
                    if (claimedGesture === gestureSequence) { claimedGesture = -1; }
                    return false;
                }
            }
            window.open = function () {
                if (arguments.length > 0 && forwardTrustedCta(arguments[0])) { return null; }
                return originalOpen.apply(window, arguments);
            };
            window.__simulaSdkDisableCta = function () {
                ctaDisabled = true;
                window.open = originalOpen;
            };
            window.addEventListener('click', function (event) {
                if (!event || event.isTrusted !== true || !hasActiveUserGesture()) { return; }
                var anchor = event.target && event.target.closest ? event.target.closest('a[href]') : null;
                if (!anchor || String(anchor.target).toLowerCase() !== '_blank') { return; }
                if (forwardTrustedCta(anchor.href)) { event.preventDefault(); }
            }, true);
        """.trimIndent()
        return """
        (function () {
            var nativeReceiver = window.$NATIVE_OBJECT;
            var nativePost = nativeReceiver && typeof nativeReceiver.postMessage === 'function'
                ? nativeReceiver.postMessage.bind(nativeReceiver)
                : null;
            var nativeStringify = JSON.stringify.bind(JSON);
            var pageReadySent = false;
            var pageId = Date.now().toString(36) + Math.random().toString(36);
            function notifyPageReady() {
                if (pageReadySent || window !== window.top || !nativePost) { return; }
                pageReadySent = true;
                try { nativePost('$PAGE_READY_PREFIX$installationId:' + pageId); } catch (e) {}
            }
            window.addEventListener('message', function (event) {
                var d = event.data;
                if (d && d.__simulaSdkResponse) { return; }
                try {
                    if (typeof d === 'string') {
                        nativePost(d);
                    } else if (d && typeof d === 'object') {
                        nativePost(nativeStringify(d));
                    }
                } catch (e) {}
            });
            if (document.readyState === 'complete') {
                setTimeout(notifyPageReady, 0);
            } else {
                window.addEventListener('load', notifyPageReady, false);
            }
$ctaRelay
        })();
    """.trimIndent()
    }

    /** Whether document-start injection (the reliable, all-frames path) is available on this device. */
    fun documentStartSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /**
     * Attach [bridge] to [webView]. When [documentStartSupported] is false, the caller's
     * `WebViewClient` should call [injectFallback] in `onPageStarted` instead.
     */
    fun install(
        webView: WebView,
        bridge: CreativeBridge,
        onTrustedCtaOpen: ((String) -> Unit)? = null,
    ) {
        uninstall(webView) // clear stale wiring from this pooled view's prior use
        val installation = BridgeInstallation(
            id = (++nextInstallationId).toString(),
            audioObserver = CreativeAudioStateObserver(webView),
            activationNonce = onTrustedCtaOpen?.let { UUID.randomUUID().toString() },
            onTrustedCtaOpen = onTrustedCtaOpen,
        )
        installations[webView] = installation

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun postMessage(json: String?) {
                // Off-main "JavaBridge" thread → the bridge hops to main; the reply runs on the
                // web view's thread via post(). Declared nullable + no-op on null so a malformed JS
                // bridge invocation passing null can't NPE on entry. Guarded so a reply that lands
                // after the pooled view is destroyed can't crash on a torn-down WebView.
                json ?: return
                readyPageId(json, installation.id)?.let { pageId ->
                    webView.post { installation.audioObserver.onPageReady(pageId) }
                    return
                }
                trustedCtaUrl(
                    json,
                    installation.activationNonce,
                    enabled = !installation.ctaDisabled,
                )?.let { url ->
                    webView.post {
                        if (installations[webView] === installation) {
                            runCatching { installation.onTrustedCtaOpen?.invoke(url) }
                        }
                    }
                    return
                }
                bridge.handle(json) { js ->
                    webView.post { runCatching { webView.evaluateJavascript(js, null) } }
                }
            }
        }, NATIVE_OBJECT)

        if (documentStartSupported()) {
            scripts[webView] = WebViewCompat.addDocumentStartJavaScript(
                webView,
                relayScript(installation.id, installation.activationNonce),
                setOf("*"),
            )
        }
    }

    /** Older WebViews lack document-start injection; install this presentation's bound script. */
    fun injectFallback(webView: WebView?) {
        val view = webView ?: return
        val installation = installations[view] ?: return
        val generation = ++installation.fallbackGeneration
        view.post {
            if (installations[view] !== installation || installation.fallbackGeneration != generation) {
                return@post
            }
            runCatching {
                view.evaluateJavascript(relayScript(installation.id, installation.activationNonce), null)
            }
        }
    }

    /** Disarm delivery as soon as a replacement main document starts navigating. */
    fun onPageStarted(webView: WebView?) {
        val view = webView ?: return
        installations[view]?.audioObserver?.onPageStarted()
    }

    /** Permanently disables CTA ownership for this installation and all later documents. */
    fun disableTrustedCta(webView: WebView) {
        val installation = installations[webView] ?: return
        installation.ctaDisabled = true
        scripts.remove(webView)?.let { runCatching { it.remove() } }
        webView.post {
            runCatching {
                webView.evaluateJavascript(
                    "window.__simulaSdkDisableCta&&window.__simulaSdkDisableCta()",
                    null,
                )
            }
        }
    }

    /** Remove the bridge wiring before a web view is recycled to the pool. Idempotent. */
    fun uninstall(webView: WebView) {
        installations.remove(webView)?.audioObserver?.close()
        runCatching { webView.removeJavascriptInterface(NATIVE_OBJECT) }
        scripts.remove(webView)?.let { runCatching { it.remove() } }
    }

    /** Tear down presentation-scoped wiring before returning the view to the shared pool. */
    fun release(webView: WebView) {
        cleanupBeforePooling(
            cleanup = { uninstall(webView) },
            release = { WebViewPool.release(webView) },
        )
    }
}

internal fun cleanupBeforePooling(cleanup: () -> Unit, release: () -> Unit) {
    runCatching(cleanup)
    runCatching(release)
}

private data class BridgeInstallation(
    val id: String,
    val audioObserver: CreativeAudioStateObserver,
    val activationNonce: String?,
    val onTrustedCtaOpen: ((String) -> Unit)?,
) {
    var fallbackGeneration: Long = 0L
    @Volatile
    var ctaDisabled: Boolean = false
}

internal fun trustedCtaUrl(
    message: String,
    expectedNonce: String?,
    enabled: Boolean = true,
): String? {
    if (!enabled) return null
    val nonce = expectedNonce ?: return null
    if (message.length > CREATIVE_BRIDGE_MAX_MESSAGE_UTF16_CHARS) return null
    val root = runCatching { installerJson.parseToJsonElement(message) as? JsonObject }.getOrNull()
        ?: return null
    val type = (root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (type != TRUSTED_CTA_OPEN) return null
    val suppliedNonce = (root["activation_nonce"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (suppliedNonce != nonce) return null
    return (root["url"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() && it.length <= MAX_CTA_URL_CHARS }
}

internal fun readyPageId(message: String, installationId: String): String? {
    if (message.length > PAGE_READY_MAX_CHARS) return null
    val prefix = "$PAGE_READY_PREFIX$installationId:"
    return message.takeIf { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.takeIf { it.isNotBlank() }
}

/** Pure page-readiness and payload-deduplication state for automatic audio events. */
internal class AudioStateDeliveryGate {
    private var closed = false
    private var pageReady = false
    private var pageId: String? = null
    private var lastDelivered: CreativeAudioState? = null

    fun onPageReady(newPageId: String): Boolean {
        if (closed || newPageId.isBlank() || newPageId == pageId) return false
        pageId = newPageId
        pageReady = true
        lastDelivered = null
        return true
    }

    fun shouldDeliver(state: CreativeAudioState): Boolean {
        if (closed || !pageReady || state == lastDelivered) return false
        lastDelivered = state
        return true
    }

    fun onPageStarted() {
        if (closed) return
        pageReady = false
        lastDelivered = null
    }

    fun close() {
        closed = true
        pageReady = false
        pageId = null
        lastDelivered = null
    }
}

/** Presentation-scoped observer. Android has no public per-stream callback, so observe settings. */
private class CreativeAudioStateObserver(webView: WebView) {
    private val appContext = webView.context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val webViewRef = WeakReference(webView)
    private val gate = AudioStateDeliveryGate()
    private var registered = false
    private var closed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val publishRunnable = Runnable { publishIfChanged() }
    private val observer = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            schedulePublish()
        }
    }

    fun onPageStarted() {
        if (closed) return
        mainHandler.removeCallbacks(publishRunnable)
        gate.onPageStarted()
    }

    fun onPageReady(pageId: String) {
        if (closed || !gate.onPageReady(pageId)) return
        mainHandler.removeCallbacks(publishRunnable)
        register()
        publishIfChanged()
    }

    private fun schedulePublish() {
        if (closed) return
        runCatching {
            mainHandler.removeCallbacks(publishRunnable)
            mainHandler.postDelayed(publishRunnable, AUDIO_CHANGE_COALESCE_MS)
        }
    }

    private fun register() {
        if (registered || closed) return
        runCatching {
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
            registered = true
        }.onFailure { error ->
            runCatching {
                Telemetry.recordError(
                    signature = "bridge:audio_observer_failed",
                    errorCode = error.javaClass.simpleName,
                    breadcrumb = "stage=register",
                )
            }
        }
    }

    private fun publishIfChanged() {
        if (closed) return
        val state = readCreativeAudioState(appContext)
        if (!gate.shouldDeliver(state)) return
        val webView = webViewRef.get() ?: run {
            close()
            return
        }
        val message = buildCreativeBridgeMessage(AUDIO_STATE_CHANGED, state.payload())
        runCatching { webView.evaluateJavascript(message, null) }
    }

    fun close() {
        if (closed) return
        closed = true
        mainHandler.removeCallbacks(publishRunnable)
        gate.close()
        if (registered) {
            registered = false
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
    }
}
