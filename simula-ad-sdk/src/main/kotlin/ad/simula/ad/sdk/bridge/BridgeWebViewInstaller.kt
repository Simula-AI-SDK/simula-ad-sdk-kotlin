package ad.simula.ad.sdk.bridge

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.WeakHashMap

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

    /** Document-start scripts added per web view, so they can be removed on re-install / release. */
    private val scripts = WeakHashMap<WebView, ScriptHandler>()

    /**
     * Relay installed in the page: forwards `window.postMessage` payloads to the native receiver,
     * dropping the SDK's own query replies (marked `__simulaSdkResponse`). Mirrors the iOS
     * `WebViewPool.postMessageScript`.
     */
    val relayScript: String = """
        (function () {
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
        })();
    """.trimIndent()

    /** Whether document-start injection (the reliable, all-frames path) is available on this device. */
    fun documentStartSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /**
     * Attach [bridge] to [webView]. When [documentStartSupported] is false, the caller's
     * `WebViewClient` should inject [relayScript] in `onPageStarted` instead.
     */
    fun install(webView: WebView, bridge: CreativeBridge) {
        uninstall(webView) // clear stale wiring from this pooled view's prior use

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun postMessage(json: String) {
                // Off-main "JavaBridge" thread → the bridge hops to main; the reply runs on the
                // web view's thread via post(). Guarded so a reply that lands after the pooled view
                // is destroyed can't crash on a torn-down WebView.
                bridge.handle(json) { js ->
                    webView.post { runCatching { webView.evaluateJavascript(js, null) } }
                }
            }
        }, NATIVE_OBJECT)

        if (documentStartSupported()) {
            scripts[webView] = WebViewCompat.addDocumentStartJavaScript(webView, relayScript, setOf("*"))
        }
    }

    /** Remove the bridge wiring before a web view is recycled to the pool. Idempotent. */
    fun uninstall(webView: WebView) {
        runCatching { webView.removeJavascriptInterface(NATIVE_OBJECT) }
        scripts.remove(webView)?.let { runCatching { it.remove() } }
    }
}
