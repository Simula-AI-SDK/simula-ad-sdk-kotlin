package ad.simula.ad.sdk.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `primary CTA planner allows document-local actions even when tracker exists`() {
        val tracker = "https://tracker.example/click"
        val base = "https://creative.example/game"

        listOf(
            "#rules",
            "/next-level",
            "javascript:showDetails()",
            "about:blank",
            "data:text/plain,help",
            "https://creative.example:443/next",
        ).forEach { tapped ->
            assertEquals(
                tapped,
                CreativeCtaRouter.PrimaryCtaTapPlan.AllowInWebView,
                CreativeCtaRouter.primaryCtaTapPlan(tapped, base, tracker, "appstore"),
            )
        }
    }

    @Test
    fun `primary CTA planner selects tracker only after safe external admission`() {
        val plan = CreativeCtaRouter.primaryCtaTapPlan(
            tappedUrl = "market://details?id=com.example.app&referrer=click%2Bvalue",
            creativeBaseUrl = "https://creative.example/game",
            trackingUrl = "https://tracker.example/click?a=1%2B2",
            destination = "appstore",
        )

        assertEquals(
            CreativeCtaRouter.PrimaryCtaTapPlan.Route(
                ad.simula.ad.sdk.network.PrimaryCtaRoute(
                    tappedUrl = "https://play.google.com/store/apps/details?id=com.example.app&referrer=click%2Bvalue",
                    externalTarget = "https://tracker.example/click?a=1%2B2",
                ),
            ),
            plan,
        )
    }

    @Test
    fun `primary CTA planner consumes unsafe exits without billing tracker`() {
        listOf(
            "market://details?referrer=missing-id",
            "intent://details#Intent;scheme=market;end",
            "custom://open/app",
            "https://bad host.example/click",
        ).forEach { tapped ->
            assertEquals(
                tapped,
                CreativeCtaRouter.PrimaryCtaTapPlan.ConsumeWithoutClick,
                CreativeCtaRouter.primaryCtaTapPlan(
                    tapped,
                    "https://creative.example/game",
                    "https://tracker.example/click",
                    "appstore",
                ),
            )
        }
    }

    @Test
    fun `web custom scheme uses safe tracker when present and direct target otherwise`() {
        assertEquals(
            CreativeCtaRouter.PrimaryCtaTapPlan.Route(
                ad.simula.ad.sdk.network.PrimaryCtaRoute(
                    tappedUrl = null,
                    externalTarget = "https://tracker.example/click",
                ),
            ),
            CreativeCtaRouter.primaryCtaTapPlan(
                "partner-app://offer",
                "https://creative.example/game",
                "https://tracker.example/click",
                "web",
            ),
        )
        assertEquals(
            CreativeCtaRouter.PrimaryCtaTapPlan.Route(
                ad.simula.ad.sdk.network.PrimaryCtaRoute(
                    tappedUrl = null,
                    externalTarget = "partner-app://offer",
                ),
            ),
            CreativeCtaRouter.primaryCtaTapPlan(
                "partner-app://offer",
                "https://creative.example/game",
                null,
                "web",
            ),
        )
        assertEquals(
            "partner-app://offer",
            CreativeCtaRouter.admittedWebCustomDestination("partner-app://offer", "web"),
        )
        assertNull(CreativeCtaRouter.admittedWebCustomDestination("javascript:alert(1)", "web"))
        assertNull(CreativeCtaRouter.admittedWebCustomDestination("intent://details#Intent;end", "web"))
        assertNull(CreativeCtaRouter.admittedWebCustomDestination("partner-app://offer", "appstore"))
    }

    @Test
    fun `top-level tracker wins over the URL embedded in HTML`() {
        val tracker = "https://tracker.example/click?a=1&b=2"
        val embedded = "https://tracker.example/click?a=1&amp;b=2"

        assertEquals(tracker, CreativeCtaRouter.preferredClickUrl(tracker, embedded))
    }

    @Test
    fun `missing or blank top-level tracker falls back to embedded URL`() {
        val embedded = "https://example.com/landing"

        assertEquals(embedded, CreativeCtaRouter.preferredClickUrl(null, embedded))
        assertEquals(embedded, CreativeCtaRouter.preferredClickUrl("", embedded))
        assertEquals(embedded, CreativeCtaRouter.preferredClickUrl("   ", embedded))
    }

    @Test
    fun `invalid top-level tracker falls back to admitted tapped URL`() {
        val tapped = "https://creative.example/click?encoded=a%2Fb%3Fc&sig=x%2By"

        assertEquals(tapped, CreativeCtaRouter.preferredClickUrl("javascript:alert(1)", tapped))
        assertEquals(tapped, CreativeCtaRouter.preferredClickUrl("/relative", tapped))
        assertEquals(tapped, CreativeCtaRouter.preferredClickUrl("https:///missing-host", tapped))
    }

    @Test
    fun `embedded market and intent destinations are safely normalized for auto redirects`() {
        val market = "market://details?id=com.example.app&referrer=click%2Bvalue"
        val intent = "intent://details#Intent;scheme=market;" +
            "S.browser_fallback_url=https%3A%2F%2Fplay.google.com%2Fstore%2Fapps%2Fdetails%3Fid%3Dcom.example.app;end"

        assertEquals(
            "https://play.google.com/store/apps/details?id=com.example.app&referrer=click%2Bvalue",
            CreativeCtaRouter.preferredClickUrl(null, market),
        )
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.example.app",
            CreativeCtaRouter.preferredClickUrl(null, intent),
        )
        assertEquals(
            "https://tracker.example/click",
            CreativeCtaRouter.preferredClickUrl("https://tracker.example/click", market),
        )
    }

    @Test
    fun `embedded unsafe custom destinations remain rejected`() {
        listOf(
            "javascript:alert(1)",
            "custom://open/app",
            "intent://details#Intent;scheme=market;end",
        ).forEach { assertNull(CreativeCtaRouter.preferredClickUrl(null, it)) }
    }

    @Test
    fun `admitted URL preserves encoded query bytes exactly`() {
        val url = "https://tracker.example/click?a=x%2Fy&sig=a%2Bb%3D%3D"

        assertEquals(url, CreativeCtaRouter.admittedHttpUrl(url))
        assertNull(CreativeCtaRouter.admittedHttpUrl("market://details?id=app"))
        assertNull(CreativeCtaRouter.admittedHttpUrl("//tracker.example/click"))
    }

    @Test
    fun `admitted URL preserves lenient MMP query bytes and trims only outer whitespace`() {
        val urls = listOf(
            "https://tracker.example/click?pid=partner|affiliate&c=summer",
            "https://tracker.example/click?campaign=Summer Sale&clickid=abc",
            "https://tracker.example/click?clickid={click_id}&sub={sub_id}",
            "https://tracker.example/click?pipe=%7C&space=Summer%20Sale&macro=%7Bclick_id%7D",
        )

        urls.forEach { assertEquals(it, CreativeCtaRouter.admittedHttpUrl(it)) }
        assertEquals(urls[2], CreativeCtaRouter.admittedHttpUrl("  ${urls[2]}  "))
        assertEquals("HTTP://tracker.example/click", CreativeCtaRouter.admittedHttpUrl("HTTP://tracker.example/click"))
    }

    @Test
    fun `URL admission rejects controls unsafe schemes and malformed authorities`() {
        listOf(
            "javascript:alert(1)",
            "data:text/html,hello",
            "intent://tracker.example/click",
            "market://details?id=app",
            "/relative",
            "//tracker.example/click",
            "https:///missing-host",
            "https://bad host.example/click",
            "https://tracker.example/click\nX-Injected: value",
            "https://tracker.example/click\u007f",
        ).forEach { assertNull(it, CreativeCtaRouter.admittedHttpUrl(it)) }
    }

    @Test
    fun `strict market details destination becomes Play HTTPS with raw query preserved`() {
        val market = "market://details?id=com.example.app&referrer=utm_source%3Dsimula%26click_id%3Dabc%2B123"

        assertEquals(
            "https://play.google.com/store/apps/details?id=com.example.app&referrer=utm_source%3Dsimula%26click_id%3Dabc%2B123",
            CreativeCtaRouter.normalizeTappedDestination(market),
        )
    }

    @Test
    fun `normalized market store fallback is used directly when tracker is missing`() {
        val market = "market://details?id=com.example.app&referrer=click%2Bvalue"
        val https = "https://play.google.com/store/apps/details?id=com.example.app&referrer=click%2Bvalue"
        val launched = mutableListOf<String>()

        assertEquals(https, CreativeCtaRouter.targetUrl(null, market, destination = "appstore"))
        assertTrue(
            CreativeCtaRouter.routeCta(
                trackingUrl = null,
                destination = "appstore",
                storeUrl = market,
                launch = { launched += it; true },
            ),
        )
        assertEquals(listOf(https), launched)
    }

    @Test
    fun `normalized market store fallback is reused after tracker launch fails`() {
        val tracker = "https://tracker.example/click"
        val market = "market://details?id=com.example.app"
        val https = "https://play.google.com/store/apps/details?id=com.example.app"
        val launched = mutableListOf<String>()

        assertTrue(
            CreativeCtaRouter.routeCta(
                trackingUrl = tracker,
                destination = "appstore",
                storeUrl = market,
                launch = { url -> launched += url; url == https },
            ),
        )
        assertEquals(listOf(tracker, https), launched)
    }

    @Test
    fun `market normalization rejects anything except strict details links with package id`() {
        listOf(
            "market://details",
            "market://details?id=",
            "market://details?id=%20",
            "market://details/?id=com.example.app",
            "market://details?id=com.example.app#fragment",
            "market://search?q=example",
            "market://details?referrer=abc",
            "market://details?id=%ZZ",
            "market://details?id=com.example.app\nnext=true",
        ).forEach { assertNull(it, CreativeCtaRouter.normalizeTappedDestination(it)) }
    }

    @Test
    fun `intent destination uses only decoded admitted browser fallback`() {
        val intent = "intent://details#Intent;scheme=market;package=com.android.vending;" +
            "S.browser_fallback_url=https%3A%2F%2Fplay.google.com%2Fstore%2Fapps%2Fdetails%3Fid%3Dcom.example.app%26referrer%3Dclick%252Bvalue;end"

        assertEquals(
            "https://play.google.com/store/apps/details?id=com.example.app&referrer=click%2Bvalue",
            CreativeCtaRouter.normalizeTappedDestination(intent),
        )
    }

    @Test
    fun `intent browser fallback is used by direct and retry store routes`() {
        val intent = "intent://details#Intent;scheme=market;" +
            "S.browser_fallback_url=https%3A%2F%2Fplay.google.com%2Fstore%2Fapps%2Fdetails%3Fid%3Dcom.example.app;end"
        val fallback = "https://play.google.com/store/apps/details?id=com.example.app"
        val directLaunches = mutableListOf<String>()
        val retryLaunches = mutableListOf<String>()

        assertEquals(fallback, CreativeCtaRouter.targetUrl(null, intent, destination = "appstore"))
        assertTrue(
            CreativeCtaRouter.routeCta(
                trackingUrl = null,
                destination = "appstore",
                storeUrl = intent,
                launch = { directLaunches += it; true },
            ),
        )
        assertTrue(
            CreativeCtaRouter.routeCta(
                trackingUrl = "https://tracker.example/click",
                destination = "appstore",
                storeUrl = intent,
                launch = { url -> retryLaunches += url; url == fallback },
            ),
        )
        assertEquals(listOf(fallback), directLaunches)
        assertEquals(listOf("https://tracker.example/click", fallback), retryLaunches)
    }

    @Test
    fun `intent fallback percent decoding preserves literal and encoded plus signs`() {
        val literalPlus = "intent://details#Intent;" +
            "S.browser_fallback_url=https%3A%2F%2Ftracker.example%2Fclick%3Fsig%3Da+b;end"
        val encodedPlus = "intent://details#Intent;" +
            "S.browser_fallback_url=https%3A%2F%2Ftracker.example%2Fclick%3Fsig%3Da%2Bb;end"

        assertEquals(
            "https://tracker.example/click?sig=a+b",
            CreativeCtaRouter.normalizeTappedDestination(literalPlus),
        )
        assertEquals(
            "https://tracker.example/click?sig=a+b",
            CreativeCtaRouter.normalizeTappedDestination(encodedPlus),
        )
    }

    @Test
    fun `intent normalization rejects direct launch explicit targets and unsafe fallbacks`() {
        val safeFallback =
            "S.browser_fallback_url=https%3A%2F%2Fplay.google.com%2Fstore%2Fapps%2Fdetails%3Fid%3Dapp"
        listOf(
            "intent://details#Intent;scheme=market;end",
            "intent://details#Intent;scheme=market;component=com.example/.Main;$safeFallback;end",
            "intent://details#Intent;scheme=market;selector=com.example/.Main;$safeFallback;end",
            "intent://details#Intent;scheme=market;SEL;$safeFallback;end",
            "intent://details#Intent;S.browser_fallback_url=javascript%3Aalert%281%29;end",
            "intent://details#Intent;S.browser_fallback_url=market%3A%2F%2Fdetails%3Fid%3Dapp;end",
            "intent://details#Intent;S.browser_fallback_url=https%3A%2F%2Fgood.example%2F%0Aevil;end",
            "intent://details#Intent;$safeFallback;$safeFallback;end",
            "intent://details#Intent;$safeFallback;end trailing",
            "intent://details#Intent;$safeFallback;end\u0000",
        ).forEach { assertNull(it, CreativeCtaRouter.normalizeTappedDestination(it)) }
    }

    @Test
    fun `tapped destination keeps HTTP bytes and never admits arbitrary schemes`() {
        val http = "https://tracker.example/click?campaign=Summer Sale&macro={click_id}"

        assertEquals(http, CreativeCtaRouter.normalizeTappedDestination("  $http  "))
        assertNull(CreativeCtaRouter.normalizeTappedDestination("javascript:alert(1)"))
        assertNull(CreativeCtaRouter.normalizeTappedDestination("custom://open/app"))
        assertNull(CreativeCtaRouter.admittedHttpUrl("market://details?id=app"))
        assertNull(CreativeCtaRouter.admittedHttpUrl("intent://details#Intent;end"))
    }

    @Test
    fun `same HTTP origin requires matching scheme host and effective port`() {
        assertTrue(
            CreativeCtaRouter.hasSameHttpOrigin(
                "https://creative.example/game",
                "https://CREATIVE.example:443/next",
            ),
        )
        assertEquals(
            false,
            CreativeCtaRouter.hasSameHttpOrigin(
                "https://creative.example/game",
                "http://creative.example/next",
            ),
        )
        assertEquals(
            false,
            CreativeCtaRouter.hasSameHttpOrigin(
                "https://creative.example/game",
                "https://creative.example:8443/next",
            ),
        )
    }

    @Test
    fun `lenient top-level tracker wins and is launched byte-for-byte`() {
        val tracker = "https://tracker.example/click?pid=partner|affiliate&campaign=Summer Sale&id={click_id}"
        val store = "https://play.google.com/store/apps/details?id=app"
        val launched = mutableListOf<String>()

        assertEquals(tracker, CreativeCtaRouter.preferredClickUrl("  $tracker  ", store))
        assertTrue(
            CreativeCtaRouter.routeCta(
                trackingUrl = "  $tracker  ",
                destination = "appstore",
                storeUrl = store,
                launch = { launched += it; true },
            ),
        )
        assertEquals(listOf(tracker), launched)
    }

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

    @Test
    fun `failed MMP route attempts raw store fallback with low cardinality diagnostics`() {
        val launched = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        val store = "https://play.google.com/store/apps/details?id=app"

        val opened = CreativeCtaRouter.routeCta(
            trackingUrl = "https://tracker.example/click?x=1%2F2",
            destination = "appstore",
            storeUrl = store,
            launch = { url -> launched += url; url == store },
            record = diagnostics::add,
        )

        assertTrue(opened)
        assertEquals(listOf("https://tracker.example/click?x=1%2F2", store), launched)
        assertEquals(listOf("mmp_route_attempted", "mmp_raw_store_fallback"), diagnostics)
    }

    @Test
    fun `failed MMP and store routes report terminal failure`() {
        val diagnostics = mutableListOf<String>()

        assertEquals(
            false,
            CreativeCtaRouter.routeCta(
                trackingUrl = "https://tracker.example/click",
                destination = "appstore",
                storeUrl = "https://play.google.com/store/apps/details?id=app",
                launch = { false },
                record = diagnostics::add,
            ),
        )
        assertEquals(
            listOf("mmp_route_attempted", "mmp_raw_store_fallback", "mmp_route_failed"),
            diagnostics,
        )
    }

    @Test
    fun `missing tracker records direct raw store fallback`() {
        val diagnostics = mutableListOf<String>()

        assertTrue(
            CreativeCtaRouter.routeCta(
                trackingUrl = null,
                destination = "appstore",
                storeUrl = "https://play.google.com/store/apps/details?id=app",
                launch = { true },
                record = diagnostics::add,
            ),
        )
        assertEquals(listOf("mmp_raw_store_fallback", "mmp_route_attempted"), diagnostics)
    }
}
