package ad.simula.ad.sdk.om

import ad.simula.ad.sdk.telemetry.Telemetry
import android.view.View
import android.webkit.WebView
import androidx.annotation.MainThread
import com.iab.omid.library.simulaad.adsession.AdEvents
import com.iab.omid.library.simulaad.adsession.AdSession
import com.iab.omid.library.simulaad.adsession.AdSessionConfiguration
import com.iab.omid.library.simulaad.adsession.AdSessionContext
import com.iab.omid.library.simulaad.adsession.CreativeType
import com.iab.omid.library.simulaad.adsession.ImpressionType
import com.iab.omid.library.simulaad.adsession.Owner

/**
 * Per-WebView holder for an [OmAdSession]. Plain mutable object (not Compose state):
 * written from WebView callbacks and read in `onRelease`, never read during
 * composition, so it triggers no recomposition. [attempted] makes session creation
 * fire at most once per WebView even if `onPageFinished` is called multiple times.
 */
internal class OmSessionRef {
    var session: OmAdSession? = null
    var attempted = false
}

/**
 * Minimal seam over OMID's final event classes so the idempotency state machine in
 * [OmAdSession] can be exercised by pure-JVM unit tests with a fake.
 */
internal interface OmEventsSink {
    fun loaded()
    fun impressionOccurred()
    fun finish()
}

/**
 * Wraps a single OMID [AdSession] with idempotent, fully-guarded lifecycle calls.
 *
 * Construct via the companion factory ([startHtml]); it performs the OMID sequence
 * (create → registerAdView → start → createAdEvents) and returns null if OM is
 * inactive or anything throws, so a measurement failure never blocks the ad. [fireLoaded]/[fireImpression] fire at most once; [finish] runs at most
 * once and supersedes the others. All methods are main-thread only (OMID + WebView).
 */
internal class OmAdSession internal constructor(
    private val sink: OmEventsSink,
) {
    private var loadedFired = false
    private var impressionFired = false
    private var finished = false

    @MainThread
    fun fireLoaded() {
        if (loadedFired || finished) return
        loadedFired = true
        runCatching { sink.loaded() }
            .onFailure { Telemetry.recordError(signature = "om:loaded", message = it.message) }
    }

    @MainThread
    fun fireImpression() {
        if (impressionFired || finished) return
        impressionFired = true
        runCatching { sink.impressionOccurred() }
            .onFailure { Telemetry.recordError(signature = "om:impression", message = it.message) }
    }

    @MainThread
    fun finish() {
        if (finished) return
        finished = true
        runCatching { sink.finish() }
            .onFailure { Telemetry.recordError(signature = "om:finish", message = it.message) }
    }

    companion object {
        /**
         * HTML ad session for any WebView surface — the interstitial (OM JS spliced into
         * the HTML string via [OpenMeasurement.injectIntoHtml]) as well as the remote-URL
         * surfaces (rewarded game / minigame / fallback), whose live WebView has the OM
         * service injected via [OpenMeasurement.injectIntoLiveWebView] before this is
         * called. Our creatives do not emit OMID events, so impression ownership is NATIVE
         * and we fire loaded/impression ourselves at first paint. Null when OM is inactive
         * or session creation throws.
         */
        @MainThread
        fun startHtml(webView: WebView, impressionId: String?): OmAdSession? {
            if (!OpenMeasurement.isActive) return null
            val partner = OpenMeasurement.partnerOrNull() ?: return null
            return try {
                val config = AdSessionConfiguration.createAdSessionConfiguration(
                    CreativeType.HTML_DISPLAY,
                    ImpressionType.BEGIN_TO_RENDER,
                    Owner.NATIVE,
                    Owner.NONE,
                    /* isolateVerificationScripts = */ false,
                )
                val context = AdSessionContext.createHtmlAdSessionContext(
                    partner, webView, /* contentUrl = */ null, /* customReferenceData = */ impressionId,
                )
                begin(config, context, webView)
            } catch (t: Throwable) {
                Telemetry.recordError(signature = "om:html_session", message = t.message)
                null
            }
        }

        /** Create the session, register the ad view, start, and build AdEvents — OMID's
         * required order — then wrap it for idempotent event firing. */
        private fun begin(
            config: AdSessionConfiguration,
            context: AdSessionContext,
            adView: View,
        ): OmAdSession {
            val session = AdSession.createAdSession(config, context)
            session.registerAdView(adView)
            session.start()
            val adEvents = AdEvents.createAdEvents(session)
            return OmAdSession(RealOmEventsSink(session, adEvents))
        }
    }

    /** Real sink backed by OMID's [AdSession] + [AdEvents]. */
    private class RealOmEventsSink(
        private val session: AdSession,
        private val adEvents: AdEvents,
    ) : OmEventsSink {
        override fun loaded() = adEvents.loaded()
        override fun impressionOccurred() = adEvents.impressionOccurred()
        override fun finish() = session.finish()
    }
}
