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

internal fun activeCtaNonce(nonce: String?, disabled: Boolean): String? =
    nonce?.takeUnless { disabled }

internal enum class BridgeInjectionMode { DOCUMENT_START, PAGE_START_FALLBACK, UNAVAILABLE }

internal fun bridgeInjectionMode(
    cleanupConfirmed: Boolean,
    interfaceInstalled: Boolean,
    documentStartSupported: Boolean,
    coreDocumentStartInstalled: Boolean,
    ctaRequired: Boolean,
    ctaDocumentStartInstalled: Boolean,
): BridgeInjectionMode = when {
    !cleanupConfirmed || !interfaceInstalled -> BridgeInjectionMode.UNAVAILABLE
    documentStartSupported && coreDocumentStartInstalled &&
        (!ctaRequired || ctaDocumentStartInstalled) -> BridgeInjectionMode.DOCUMENT_START
    else -> BridgeInjectionMode.PAGE_START_FALLBACK
}

internal fun trustedCtaRelaySource(activationNonce: String): String = """
    var activationNonce = ${JsonPrimitive(activationNonce)};
    var originalOpen = window.open;
    var ctaDisabled = false;
    var capturedUserActivation = navigator.userActivation;
    var nativeSetTimeout = window.setTimeout.bind(window);
    var trustedDispatch = false;
    var trustedEventEpoch = 0;
    var trustedEventTimestamp = -1;
    var gestureSequence = 0;
    var claimedGesture = -1;
    var awaitingClick = false;
    var contactPending = false;
    var cancelledContact = false;

    function clearTrustedDispatchLater(epoch) {
        nativeSetTimeout(function () {
            if (trustedEventEpoch === epoch) { trustedDispatch = false; }
        }, 0);
    }
    function markTrustedDispatch(event) {
        trustedEventEpoch += 1;
        trustedEventTimestamp = Number(event.timeStamp || 0);
        trustedDispatch = true;
        clearTrustedDispatchLater(trustedEventEpoch);
    }
    function beginGesture(event) {
        cancelledContact = false;
        if (!awaitingClick) { gestureSequence += 1; }
        awaitingClick = true;
        markTrustedDispatch(event);
    }
    function beginKeyboardGesture(event) {
        cancelledContact = false;
        gestureSequence += 1;
        awaitingClick = true;
        markTrustedDispatch(event);
    }
    function disarmPendingContact() {
        contactPending = true;
        awaitingClick = false;
        trustedDispatch = false;
        trustedEventEpoch += 1;
        cancelledContact = false;
    }
    function cancelContact() {
        contactPending = false;
        awaitingClick = false;
        trustedDispatch = false;
        trustedEventEpoch += 1;
        cancelledContact = true;
        var epoch = trustedEventEpoch;
        nativeSetTimeout(function () {
            if (trustedEventEpoch === epoch) { cancelledContact = false; }
        }, 0);
    }
    function isModifierOnlyKey(key) {
        return ['alt','altgraph','capslock','control','ctrl','fn','fnlock','hyper','meta',
            'numlock','scrolllock','shift','super','symbol','symbollock'].indexOf(key) !== -1;
    }
    function isActivationKey(event) {
        if (event.repeat === true) { return false; }
        var key = String(event.key || '').toLowerCase();
        if (key === 'escape' || key === 'esc' || key === 'dead' || key === 'process') { return false; }
        return !isModifierOnlyKey(key);
    }
    function observeTrustedEvent(event) {
        if (!event || event.isTrusted !== true) { return; }
        var type = event.type;
        var pointerType = String(event.pointerType || '').toLowerCase();
        if (type === 'pointerdown') {
            if (pointerType === 'touch' || pointerType === 'pen') { disarmPendingContact(); }
            else { beginGesture(event); }
        } else if (type === 'pointerup') {
            if (pointerType === 'touch' || pointerType === 'pen') {
                if (!contactPending) { return; }
                contactPending = false;
            }
            beginGesture(event);
        } else if (type === 'pointercancel' || type === 'touchcancel') {
            cancelContact();
        } else if (type === 'mousedown') {
            if (!contactPending) { beginGesture(event); }
        } else if (type === 'touchstart') {
            disarmPendingContact();
        } else if (type === 'touchend') {
            if (!contactPending) { return; }
            contactPending = false;
            beginGesture(event);
        } else if (type === 'keydown') {
            if (isActivationKey(event)) { beginKeyboardGesture(event); }
        } else if (type === 'click') {
            if (cancelledContact || contactPending) { return; }
            if (!awaitingClick) { beginGesture(event); }
            else { markTrustedDispatch(event); }
            awaitingClick = false;
        }
    }
    ['click', 'pointerdown', 'pointerup', 'pointercancel', 'mousedown',
        'touchstart', 'touchend', 'touchcancel', 'keydown'].forEach(function (name) {
        window.addEventListener(name, observeTrustedEvent, true);
    });
    function hasActiveUserGesture() {
        if (contactPending || cancelledContact) { return false; }
        if (capturedUserActivation && capturedUserActivation.isActive === true) { return true; }
        return trustedDispatch && trustedEventTimestamp >= 0;
    }
    function resolvedUrl(value) {
        if (value === undefined || value === null) { return null; }
        try { return new URL(String(value), document.baseURI).href; }
        catch (_) { return null; }
    }
    function documentHttpOrigin() {
        try {
            var origin = window.location && window.location.origin;
            if (!origin || origin === 'null') { return null; }
            var parsed = new URL(origin);
            if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') { return null; }
            return parsed.origin;
        } catch (_) { return null; }
    }
    function isSameOriginCta(url) {
        var origin = documentHttpOrigin();
        if (!origin) { return false; }
        try { return new URL(url, document.baseURI).origin === origin; }
        catch (_) { return false; }
    }
    function isInternalCta(url) {
        try {
            var protocol = new URL(url, document.baseURI).protocol;
            return protocol === 'about:' || protocol === 'data:' || protocol === 'blob:' ||
                protocol === 'javascript:';
        } catch (_) { return true; }
    }
    function nativeCtaEnabled() {
        try {
            return nativeReceiver && typeof nativeReceiver.isCtaEnabled === 'function' &&
                nativeReceiver.isCtaEnabled(activationNonce) === true;
        } catch (_) { return false; }
    }
    function forwardTrustedCta(value) {
        if (ctaDisabled || !nativeCtaEnabled()) { return false; }
        var url = resolvedUrl(value);
        if (!url || isInternalCta(url) || isSameOriginCta(url)) { return false; }
        if (gestureSequence === 0) { return false; }
        if (claimedGesture === gestureSequence) { return true; }
        if (!hasActiveUserGesture()) { return false; }
        claimedGesture = gestureSequence;
        try {
            nativePost('{"type":"$TRUSTED_CTA_OPEN","url":' + nativeStringify(url) +
                ',"activation_nonce":' + nativeStringify(activationNonce) + '}');
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
    private val scripts = WeakHashMap<WebView, DocumentStartScripts>()
    private val installations = WeakHashMap<WebView, BridgeInstallation>()

    /**
     * Relay installed in the page: forwards `window.postMessage` payloads to the native receiver,
     * dropping the SDK's own query replies (marked `__simulaSdkResponse`). Mirrors the iOS
     * `WebViewPool.postMessageScript`.
     */
    internal fun coreRelayScript(installationId: String, bridgeCapability: String): String = """
        (function () {
            'use strict';
            if (window !== window.top) { return; }
            var bridgeCapability = ${JsonPrimitive(bridgeCapability)};
            var nativeReceiver = window.$NATIVE_OBJECT;
            var nativePost = nativeReceiver && typeof nativeReceiver.postMessage === 'function'
                ? nativeReceiver.postMessage.bind(nativeReceiver)
                : null;
            var nativeStringify = JSON.stringify.bind(JSON);
            var nativeParse = JSON.parse.bind(JSON);
            var pageReadySent = false;
            var pageId = Date.now().toString(36) + Math.random().toString(36);
            function notifyPageReady() {
                if (pageReadySent || window !== window.top || !nativePost) { return; }
                pageReadySent = true;
                try { nativePost('$PAGE_READY_PREFIX$installationId:' + pageId); } catch (e) {}
            }
            window.addEventListener('message', function (event) {
                if (!event || event.isTrusted !== true || event.source !== window) { return; }
                var d = event.data;
                if (d && d.__simulaSdkResponse) { return; }
                try {
                    var envelope = typeof d === 'string' ? nativeParse(d) : d;
                    if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope)) { return; }
                    var serialized = nativeStringify(envelope);
                    if (!serialized || serialized.charAt(0) !== '{') { return; }
                    nativePost('{"$BRIDGE_CAPABILITY_KEY":' + nativeStringify(bridgeCapability) +
                        ',' + serialized.substring(1));
                } catch (e) {}
            });
            if (document.readyState === 'complete') {
                setTimeout(notifyPageReady, 0);
            } else {
                window.addEventListener('load', notifyPageReady, false);
            }
        })();
    """.trimIndent()

    internal fun trustedCtaDocumentStartScript(activationNonce: String): String = """
        (function () {
            'use strict';
            if (window !== window.top) { return; }
            var nativeReceiver = window.$NATIVE_OBJECT;
            var nativePost = nativeReceiver && typeof nativeReceiver.postMessage === 'function'
                ? nativeReceiver.postMessage.bind(nativeReceiver)
                : null;
            var nativeStringify = JSON.stringify.bind(JSON);
${trustedCtaRelaySource(activationNonce)}
        })();
    """.trimIndent()

    internal fun fallbackRelayScript(
        installationId: String,
        bridgeCapability: String,
        activationNonce: String?,
        ctaDisabled: Boolean,
        coreDocumentStartInstalled: Boolean,
        ctaDocumentStartInstalled: Boolean,
    ): String = buildString {
        if (!coreDocumentStartInstalled) append(coreRelayScript(installationId, bridgeCapability))
        if (!ctaDocumentStartInstalled) {
            activeCtaNonce(activationNonce, ctaDisabled)?.let { nonce ->
                if (isNotEmpty()) append('\n')
                append(trustedCtaDocumentStartScript(nonce))
            }
        }
    }

    /** Whether document-start injection (the reliable, all-frames path) is available on this device. */
    fun documentStartSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }.getOrDefault(false)

    /**
     * Attach [bridge] to [webView]. Legacy providers and failed document-start registrations use a
     * capability-bound page-start fallback; unsafe cleanup or interface setup remains unavailable.
     */
    fun install(
        webView: WebView,
        bridge: CreativeBridge,
        onTrustedCtaOpen: ((String) -> Unit)? = null,
    ): BridgeInjectionMode {
        if (!uninstall(webView)) {
            Telemetry.recordError(signature = "bridge:stale_wiring_cleanup_failed")
            return BridgeInjectionMode.UNAVAILABLE
        }
        val installation = BridgeInstallation(
            id = (++nextInstallationId).toString(),
            bridgeCapability = UUID.randomUUID().toString(),
            audioObserver = CreativeAudioStateObserver(webView),
            activationNonce = onTrustedCtaOpen?.let { UUID.randomUUID().toString() },
            onTrustedCtaOpen = onTrustedCtaOpen,
        )
        installations[webView] = installation

        val interfaceInstalled = runCatching { webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun isCtaEnabled(nonce: String?): Boolean =
                installation.active && !installation.ctaDisabled && nonce == installation.activationNonce

            @JavascriptInterface
            fun postMessage(json: String?) {
                // Off-main "JavaBridge" thread → the bridge hops to main; the reply runs on the
                // web view's thread via post(). Declared nullable + no-op on null so a malformed JS
                // bridge invocation passing null can't NPE on entry. Guarded so a reply that lands
                // after the pooled view is destroyed can't crash on a torn-down WebView.
                json ?: return
                readyPageId(json, installation.id)?.let { pageId ->
                    webView.post {
                        if (installations[webView] === installation) {
                            installation.audioObserver.onPageReady(pageId)
                        }
                    }
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
                val authenticated = authenticatedBridgeMessage(json, installation.bridgeCapability)
                    ?: return
                bridge.handle(
                    message = authenticated,
                    isActive = { installation.active },
                ) { js ->
                    webView.post {
                        if (installations[webView] === installation) {
                            runCatching { webView.evaluateJavascript(js, null) }
                        }
                    }
                }
            }
        }, NATIVE_OBJECT) }.onFailure {
            Telemetry.recordError(
                signature = "bridge:javascript_interface_failed",
                errorCode = it::class.java.simpleName,
            )
        }.isSuccess
        if (!interfaceInstalled) {
            installations.remove(webView)?.let {
                it.active = false
                runCatching { it.audioObserver.close() }
            }
            return BridgeInjectionMode.UNAVAILABLE
        }

        val documentStartSupported = documentStartSupported()
        var coreDocumentStartInstalled = false
        var ctaDocumentStartInstalled = installation.activationNonce == null
        if (documentStartSupported) {
            val core = runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    coreRelayScript(installation.id, installation.bridgeCapability),
                    setOf("*"),
                )
            }.onFailure {
                Telemetry.recordError(
                    signature = "bridge:document_start_failed",
                    errorCode = it::class.java.simpleName,
                    breadcrumb = "part=core",
                )
            }.getOrNull()
            if (core != null) {
                coreDocumentStartInstalled = true
                val cta = activeCtaNonce(installation.activationNonce, installation.ctaDisabled)?.let { nonce ->
                    runCatching {
                        WebViewCompat.addDocumentStartJavaScript(
                            webView,
                            trustedCtaDocumentStartScript(nonce),
                            setOf("*"),
                        )
                    }.onFailure {
                        Telemetry.recordError(
                            signature = "bridge:document_start_failed",
                            errorCode = it::class.java.simpleName,
                            breadcrumb = "part=cta",
                        )
                    }.getOrNull()
                }
                ctaDocumentStartInstalled = installation.activationNonce == null || cta != null
                scripts[webView] = DocumentStartScripts(core = core, cta = cta)
            }
        } else {
            Telemetry.recordError(signature = "bridge:document_start_unavailable")
        }
        val mode = bridgeInjectionMode(
            cleanupConfirmed = true,
            interfaceInstalled = true,
            documentStartSupported = documentStartSupported,
            coreDocumentStartInstalled = coreDocumentStartInstalled,
            ctaRequired = installation.activationNonce != null,
            ctaDocumentStartInstalled = ctaDocumentStartInstalled,
        )
        installation.injectionMode = mode
        installation.coreDocumentStartInstalled = coreDocumentStartInstalled
        installation.ctaDocumentStartInstalled = ctaDocumentStartInstalled
        return mode
    }

    /** Disarm delivery as soon as a replacement main document starts navigating. */
    fun onPageStarted(webView: WebView?) {
        val view = webView ?: return
        val installation = installations[view] ?: return
        installation.audioObserver.onPageStarted()
        if (!installation.active || installation.injectionMode != BridgeInjectionMode.PAGE_START_FALLBACK) return
        val source = fallbackRelayScript(
            installationId = installation.id,
            bridgeCapability = installation.bridgeCapability,
            activationNonce = installation.activationNonce,
            ctaDisabled = installation.ctaDisabled,
            coreDocumentStartInstalled = installation.coreDocumentStartInstalled,
            ctaDocumentStartInstalled = installation.ctaDocumentStartInstalled,
        )
        if (source.isEmpty()) return
        runCatching { view.evaluateJavascript(source, null) }.onFailure {
            Telemetry.recordError(
                signature = "bridge:page_start_fallback_failed",
                errorCode = it::class.java.simpleName,
            )
        }
    }

    /** Permanently disables CTA ownership for this installation and all later documents. */
    fun disableTrustedCta(webView: WebView) {
        val installation = installations[webView] ?: return
        if (installation.ctaDisabled) return
        installation.ctaDisabled = true
        scripts[webView]?.let { handlers ->
            handlers.cta?.let { handler ->
                if (runCatching { handler.remove() }.isSuccess) {
                    handlers.cta = null
                }
            }
        }
        webView.post {
            if (installations[webView] === installation) {
                runCatching {
                    webView.evaluateJavascript(
                        "window.__simulaSdkDisableCta&&window.__simulaSdkDisableCta()",
                        null,
                    )
                }
            }
        }
    }

    /** Remove the bridge wiring before a web view is recycled to the pool. Idempotent. */
    fun uninstall(webView: WebView): Boolean {
        val installation = installations.remove(webView)
        installation?.active = false
        val observerClosed = runCatching { installation?.audioObserver?.close() }.isSuccess
        val interfaceRemoved = runCatching { webView.removeJavascriptInterface(NATIVE_OBJECT) }.isSuccess
        val handlers = scripts[webView]
        val ctaRemoved = handlers?.cta?.let { runCatching { it.remove() }.isSuccess } ?: true
        if (ctaRemoved) handlers?.cta = null
        val coreRemoved = handlers?.core?.let { runCatching { it.remove() }.isSuccess } ?: true
        if (coreRemoved) handlers?.core = null
        if (ctaRemoved && coreRemoved) scripts.remove(webView)
        return observerClosed && interfaceRemoved && ctaRemoved && coreRemoved
    }

    /** Tear down presentation-scoped wiring before returning the view to the shared pool. */
    fun release(webView: WebView) {
        cleanupBeforePooling(
            cleanup = { uninstall(webView) },
            release = { WebViewPool.release(webView) },
            discard = { WebViewPool.discard(webView) },
        )
    }
}

internal fun cleanupBeforePooling(
    cleanup: () -> Boolean,
    release: () -> Unit,
    discard: () -> Unit,
) {
    val cleaned = runCatching(cleanup).getOrDefault(false)
    if (!cleaned) {
        runCatching(discard)
        return
    }
    if (runCatching(release).isFailure) runCatching(discard)
}

private data class BridgeInstallation(
    val id: String,
    val bridgeCapability: String,
    val audioObserver: CreativeAudioStateObserver,
    val activationNonce: String?,
    val onTrustedCtaOpen: ((String) -> Unit)?,
) {
    var injectionMode: BridgeInjectionMode = BridgeInjectionMode.UNAVAILABLE
    var coreDocumentStartInstalled: Boolean = false
    var ctaDocumentStartInstalled: Boolean = false
    @Volatile
    var active: Boolean = true
    @Volatile
    var ctaDisabled: Boolean = false
}

private data class DocumentStartScripts(
    var core: ScriptHandler?,
    var cta: ScriptHandler?,
)

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
