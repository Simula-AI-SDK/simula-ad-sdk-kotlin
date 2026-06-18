package ad.simula.ad.sdk.bridge

import ad.simula.ad.sdk.telemetry.Telemetry
import android.graphics.Bitmap
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Base [WebViewClient] for ad creatives, adding best-effort telemetry shared by the interstitial,
 * rewarded, and native WebViews (PRD "Better Telemetry Tracking"):
 *
 * - **Page-load timing** — `webview_page_load` lifecycle (onPageStarted → onPageFinished). Only for
 *   real URL loads; data-URL/`about:blank` loads are skipped.
 * - **Render-crash detection** — [onRenderProcessGone] records `webview:render_gone` and returns
 *   `true` so a dead creative render process is absorbed by the SDK instead of killing the **host
 *   app process** (crash-free for the host).
 *
 * Subclasses add their own navigation handling; when they override [onPageStarted] they MUST call
 * `super.onPageStarted(...)` so the load timer starts.
 */
internal open class CreativeTelemetryWebViewClient(private val adFormat: String) : WebViewClient() {
    private var pageStartNanos = 0L

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url != null && url != "about:blank") pageStartNanos = System.nanoTime()
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        val start = pageStartNanos
        if (start != 0L && url != null && url != "about:blank") {
            pageStartNanos = 0L
            Telemetry.recordLifecycle(
                stage = "webview_page_load",
                adFormat = adFormat,
                adUnitId = null,
                adId = null,
                serveId = null,
                durationMs = (System.nanoTime() - start) / 1_000_000,
                errorCode = null,
            )
        }
        super.onPageFinished(view, url)
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        // onRenderProcessGone exists since API 26; the platform never calls it on older versions, so
        // detail.didCrash() (also API 26) is safe to reach here.
        val crashed = detail?.didCrash() ?: false
        Telemetry.recordError(
            signature = "webview:render_gone",
            errorCode = if (crashed) "render_crash" else "render_oom",
            breadcrumb = adFormat,
        )
        // Returning true tells the platform we've handled it, so the HOST process is not killed (the
        // creative is gone either way). Dead-view eviction is left to the normal teardown/pool path.
        return true
    }
}

/**
 * [WebChromeClient] that captures creative JS **console errors** as deduped telemetry. The message is
 * redacted + length-capped by [Telemetry.recordError] before storage/send. Returns `false` so the
 * platform's default console logging still runs.
 */
internal class CreativeTelemetryWebChromeClient(private val adFormat: String) : WebChromeClient() {
    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
        if (message?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
            Telemetry.recordError(
                signature = "creative:js_error",
                errorCode = adFormat,
                message = message.message(),
                breadcrumb = "line=${message.lineNumber()}",
            )
        }
        return false
    }
}
