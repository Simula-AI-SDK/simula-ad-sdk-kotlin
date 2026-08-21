package ad.simula.ad.sdk.bridge

import ad.simula.ad.sdk.telemetry.Telemetry
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
import java.util.WeakHashMap

private const val PAGE_READY_PREFIX = "__simulaSdkPageReady:"
private const val PAGE_READY_MAX_CHARS = 256

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
    private fun relayScript(installationId: String): String = """
        (function () {
            var pageReadySent = false;
            var pageId = Date.now().toString(36) + Math.random().toString(36);
            function notifyPageReady() {
                if (pageReadySent || window !== window.top) { return; }
                pageReadySent = true;
                try { window.$NATIVE_OBJECT.postMessage('$PAGE_READY_PREFIX$installationId:' + pageId); } catch (e) {}
            }
            window.addEventListener('message', function (event) {
                var d = event.data;
                if (d && d.__simulaSdkResponse) { return; }
                try {
                    if (typeof d === 'string') {
                        window.$NATIVE_OBJECT.postMessage(d);
                    } else if (d && typeof d === 'object') {
                        window.$NATIVE_OBJECT.postMessage(JSON.stringify(d));
                    }
                } catch (e) {}
            });
            if (document.readyState === 'complete') {
                setTimeout(notifyPageReady, 0);
            } else {
                window.addEventListener('load', notifyPageReady, false);
            }
        })();
    """.trimIndent()

    /** Whether document-start injection (the reliable, all-frames path) is available on this device. */
    fun documentStartSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /**
     * Attach [bridge] to [webView]. When [documentStartSupported] is false, the caller's
     * `WebViewClient` should call [injectFallback] in `onPageStarted` instead.
     */
    fun install(webView: WebView, bridge: CreativeBridge) {
        uninstall(webView) // clear stale wiring from this pooled view's prior use
        val installation = BridgeInstallation(
            id = (++nextInstallationId).toString(),
            audioObserver = CreativeAudioStateObserver(webView),
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
                bridge.handle(json) { js ->
                    webView.post { runCatching { webView.evaluateJavascript(js, null) } }
                }
            }
        }, NATIVE_OBJECT)

        if (documentStartSupported()) {
            scripts[webView] = WebViewCompat.addDocumentStartJavaScript(
                webView,
                relayScript(installation.id),
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
            runCatching { view.evaluateJavascript(relayScript(installation.id), null) }
        }
    }

    /** Remove the bridge wiring before a web view is recycled to the pool. Idempotent. */
    fun uninstall(webView: WebView) {
        installations.remove(webView)?.audioObserver?.close()
        runCatching { webView.removeJavascriptInterface(NATIVE_OBJECT) }
        scripts.remove(webView)?.let { runCatching { it.remove() } }
    }
}

private data class BridgeInstallation(
    val id: String,
    val audioObserver: CreativeAudioStateObserver,
) {
    var fallbackGeneration: Long = 0L
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
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            publishIfChanged()
        }
    }

    fun onPageReady(pageId: String) {
        if (closed || !gate.onPageReady(pageId)) return
        register()
        publishIfChanged()
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
        gate.close()
        if (registered) {
            registered = false
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
    }
}
