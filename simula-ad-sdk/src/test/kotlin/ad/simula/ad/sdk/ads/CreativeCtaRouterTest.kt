package ad.simula.ad.sdk.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tier-0 regression guard for click-through attribution: the CTA must open the MMP tracking link
 * **verbatim** and never rewrite it into a `market://`/Play Store URL — rewriting strips the
 * `referrer` the Google Play Install Referrer API needs, breaking install attribution. The framework
 * (Intent/Uri/Context) launch is verified manually; here we lock the pure URL-selection contract.
 */
class CreativeCtaRouterTest {

    @Test
    fun `opens the tracking link verbatim regardless of destination`() {
        val tracking = "https://app.appsflyer.com/id123?pid=net&c=camp&af_siteid=pub&clickid=abc"
        // The opened URL is exactly the tracker — same host, query untouched, never a market:// link.
        assertEquals(tracking, CreativeCtaRouter.targetUrl(tracking))
    }

    @Test
    fun `blank or missing tracking link is a no-op`() {
        assertNull(CreativeCtaRouter.targetUrl(null))
        assertNull(CreativeCtaRouter.targetUrl(""))
        assertNull(CreativeCtaRouter.targetUrl("   "))
    }
}
