package ad.simula.ad.sdk.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tier-0 regression guard for click-through attribution: the CTA must open the MMP tracking link
 * **verbatim** and never rewrite it into a `market://`/Play Store URL — rewriting strips the
 * `referrer` the Google Play Install Referrer API needs, breaking install attribution. The raw
 * store link ([CreativeCtaRouter.targetUrl]'s second argument) is only the deterministic fallback
 * when the tracker is blank/missing — it must never *replace* an available tracker. The framework
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
    fun `a store link never replaces an available tracker`() {
        val tracking = "https://scrmbly.sng.link/D8eij/u4bya?idfa=x&ad_id=abc"
        val store = "https://play.google.com/store/apps/details?id=com.scrambly"
        // The tracker wins — opening the raw store link instead would drop the MMP click.
        assertEquals(tracking, CreativeCtaRouter.targetUrl(tracking, store))
    }

    @Test
    fun `blank or missing tracker falls back to the raw store link for appstore destinations`() {
        val store = "https://play.google.com/store/apps/details?id=com.scrambly"
        // Previously a silent no-op; now the CTA deterministically lands on the store.
        assertEquals(store, CreativeCtaRouter.targetUrl(null, store))
        assertEquals(store, CreativeCtaRouter.targetUrl("", store))
        assertEquals(store, CreativeCtaRouter.targetUrl("   ", store, "appstore"))
    }

    @Test
    fun `a web destination never falls back to the store link`() {
        val store = "https://play.google.com/store/apps/details?id=com.scrambly"
        // A web CTA with no tracker must no-op — opening the Play Store would be the wrong surface.
        assertNull(CreativeCtaRouter.targetUrl(null, store, "web"))
        assertNull(CreativeCtaRouter.targetUrl("  ", store, "web"))
        // The tracker itself still opens verbatim for web destinations.
        assertEquals("https://example.com/offer", CreativeCtaRouter.targetUrl("https://example.com/offer", store, "web"))
    }

    @Test
    fun `blank or missing tracking link with no store link is a no-op`() {
        assertNull(CreativeCtaRouter.targetUrl(null))
        assertNull(CreativeCtaRouter.targetUrl(""))
        assertNull(CreativeCtaRouter.targetUrl("   "))
        assertNull(CreativeCtaRouter.targetUrl(null, null))
        assertNull(CreativeCtaRouter.targetUrl("", "  "))
    }
}
