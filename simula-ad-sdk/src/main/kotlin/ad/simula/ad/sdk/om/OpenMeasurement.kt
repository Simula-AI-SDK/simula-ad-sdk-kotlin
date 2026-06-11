package ad.simula.ad.sdk.om

import ad.simula.ad.sdk.R
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.telemetry.SIMULA_SDK_VERSION
import ad.simula.ad.sdk.telemetry.Telemetry
import android.content.Context
import android.webkit.WebView
import androidx.annotation.MainThread
import com.iab.omid.library.simulaad.Omid
import com.iab.omid.library.simulaad.ScriptInjector
import com.iab.omid.library.simulaad.adsession.Partner
import kotlinx.coroutines.launch

/**
 * Process-wide entry point for IAB Open Measurement (OMID).
 *
 * Owns the one-time SDK activation, the cached [Partner], and the bundled OM JS
 * service script, plus the HTML-injection helper used for server-rendered creatives.
 *
 * Measurement must never break an ad: [Omid.activate] runs once at SDK init, every
 * OMID interaction is guarded, and any failure disables OM for the rest of the
 * process while the ad continues to render unmeasured (no `fatalError`-style aborts).
 */
internal object OpenMeasurement {

    /** Partner name issued by IAB Tech Lab — must match the SDK namespace ("simulaad"). */
    private const val PARTNER_NAME = "Simulaad"

    @Volatile private var enabled = false
    @Volatile private var omidJs: String? = null
    private var appContext: Context? = null
    private var cachedPartner: Partner? = null

    /** True only when the host opted in AND OMID activated successfully. */
    val isActive: Boolean
        get() = enabled && runCatching { Omid.isActive() }.getOrDefault(false)

    /**
     * Activate OMID once, at SDK init. [Omid.activate] is a cheap main-thread call;
     * the ~50KB service-script read is pushed onto [SimulaScope] (IO) so nothing
     * touches disk on an ad load/show path. Idempotent and safe to call repeatedly.
     */
    @MainThread
    fun initialize(context: Context, enabled: Boolean) {
        if (!enabled) {
            this.enabled = false
            return
        }
        if (this.enabled) return // already activated
        val appCtx = context.applicationContext
        appContext = appCtx
        try {
            Omid.activate(appCtx)
            cachedPartner = Partner.createPartner(PARTNER_NAME, SIMULA_SDK_VERSION)
            this.enabled = true
        } catch (t: Throwable) {
            this.enabled = false
            Telemetry.recordError(signature = "om:activate", message = t.message)
            return
        }
        // Warm the service-script cache off the critical path.
        SimulaScope.launch { readOmidJs(appCtx) }
    }

    /** The cached OMID partner; null until [initialize] succeeds. */
    internal fun partnerOrNull(): Partner? = cachedPartner

    /**
     * Splice the OMID service script into [html] so a verification script referenced
     * by the creative can run. Callable from any thread (intended for the load
     * coroutine on IO). Returns [html] unchanged when OM is inactive or on any
     * failure — the ad renders normally, just unmeasured.
     */
    fun injectIntoHtml(html: String): String {
        if (!isActive) return html
        val js = omidJs ?: appContext?.let { readOmidJs(it) } ?: return html
        return try {
            ScriptInjector.injectScriptContentIntoHtml(js, html)
        } catch (t: Throwable) {
            Telemetry.recordError(signature = "om:inject", message = t.message)
            html
        }
    }

    /**
     * Inject the OMID service script into a live [webView] whose page we don't author —
     * the remote-URL surfaces (rewarded game / minigame / fallback). An HTML ad session
     * needs the service running in the page, so [onInjected] (which starts the session)
     * runs on the UI thread only once the script has evaluated. No-op when OM is inactive,
     * the service script can't be read, or evaluation throws — the ad renders unmeasured.
     */
    @MainThread
    fun injectIntoLiveWebView(webView: WebView, onInjected: () -> Unit) {
        if (!isActive) return
        val js = omidJs ?: appContext?.let { readOmidJs(it) } ?: return
        try {
            webView.evaluateJavascript(js) { onInjected() }
        } catch (t: Throwable) {
            Telemetry.recordError(signature = "om:inject_live", message = t.message)
        }
    }

    /** Reads + caches the bundled service script. Returns it, or null on failure. */
    private fun readOmidJs(context: Context): String? {
        omidJs?.let { return it }
        return try {
            val text = context.resources.openRawResource(R.raw.simula_omsdk_v1)
                .use { it.readBytes().toString(Charsets.UTF_8) }
            omidJs = text
            text
        } catch (t: Throwable) {
            Telemetry.recordError(signature = "om:js_read", message = t.message)
            null
        }
    }
}
