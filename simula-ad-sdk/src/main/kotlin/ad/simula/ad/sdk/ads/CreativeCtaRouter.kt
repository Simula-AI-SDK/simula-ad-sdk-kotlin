package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.StoreOpen
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Routes a creative's call-to-action tap to its advertiser destination.
 *
 * A creative's [trackingUrl] is an MMP click tracker (AppsFlyer, Adjust, etc.). We open it
 * **directly** in the browser and let the tracker perform its own 30x redirect to the store —
 * we never resolve the chain ourselves or rebuild a store intent. This is what preserves
 * attribution end-to-end:
 *
 * - The real browser navigation is what registers the **click** with the MMP (with the device's
 *   own user-agent / IP, which the MMP fingerprints).
 * - For Play Store CTAs the tracker redirects to
 *   `https://play.google.com/store/apps/details?id=…&referrer=…`; the Play Store app intercepts
 *   that https link and records the `referrer`, which the Google Play Install Referrer API reads
 *   at install time. That `referrer` is the *only* signal that ties the **install** back to the
 *   click — see https://developer.android.com/google/play/installreferrer.
 *
 * The previous implementation resolved the redirect chain and relaunched a bare
 * `market://details?id=…` intent; that dropped the `referrer` query parameter (breaking install
 * attribution) and fired the tracker from a non-browser request (risking user-agent/IP mismatch
 * and double-counted clicks). We deliberately do neither now.
 *
 * **Deterministic store fallback** ([storeUrl], the campaign's raw `android_store_url`): the raw
 * Play link is used only when the tracker can't carry the click at all — a blank/missing
 * [trackingUrl] (previously a silent no-op) or a tracker `startActivity` that throws — so the CTA
 * still lands deterministically on the store. It never *replaces* an openable tracker: unlike the
 * iOS router (which opens `SKStoreProductViewController` from `ios_store_url` and fires the
 * tracker in the background), Android's install attribution rides the Play `referrer`, which only
 * survives the real browser navigation through the tracker.
 *
 * [destination] and [storeOpen] are retained for wire compatibility but no longer branch Android
 * behavior — every CTA opens its tracking link verbatim. (`storeOpen == INLINE_INSTALL` previously
 * tried an undocumented `market://…&overlay=true` half-sheet, which cannot carry the `referrer`;
 * preserving attribution takes precedence over that experiment.)
 *
 * The `startActivity` is wrapped in [runCatching] so a missing/unavailable browser can never crash
 * the host, and uses the application context + `FLAG_ACTIVITY_NEW_TASK` so the open survives the ad
 * Activity being auto-dismissed.
 */
internal object CreativeCtaRouter {

    /**
     * The URL a creative CTA should open: the tracking link itself, trimmed and **verbatim**
     * (never rewritten into a store URL), or — when the tracker is blank/missing AND the
     * [destination] is the app store — the campaign's raw [storeUrl], so the CTA deterministically
     * lands on the store instead of silently no-oping. A web-destination CTA never falls back to
     * the store link. `null` when nothing is applicable (the caller then no-ops). Pure and
     * framework-free so the "never rewrite the tracker" contract can be unit-tested.
     */
    internal fun targetUrl(
        trackingUrl: String?,
        storeUrl: String? = null,
        destination: String = "appstore",
    ): String? =
        trackingUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: storeUrl?.trim()?.takeIf { it.isNotEmpty() && destination == "appstore" }

    /**
     * Opens the advertiser destination for a creative CTA by handing [targetUrl] to the browser.
     * Best-effort: a blank link or unavailable browser silently no-ops (the CLICKED event has
     * already fired upstream). When the tracker itself can't be launched and the destination is
     * the app store, the raw [storeUrl] (if distinct) is the deterministic fallback.
     *
     * @return `true` when a launch actually succeeded (tracker or store fallback) — callers use
     * this to gate store-open telemetry / click state so a failed launch is never recorded as a
     * store visit.
     */
    fun open(
        context: Context,
        trackingUrl: String?,
        destination: String,
        storeOpen: StoreOpen? = null,
        storeUrl: String? = null,
    ): Boolean {
        val url = targetUrl(trackingUrl, storeUrl, destination) ?: return false
        if (launch(context, url)) return true
        // Deterministic fallback (appstore destinations only): the tracker had no handler / was
        // malformed — land the CTA on the raw store link instead of dropping it.
        if (destination != "appstore") return false
        val fallback = storeUrl?.trim()?.takeIf { it.isNotEmpty() && it != url } ?: return false
        return launch(context, fallback)
    }

    private fun launch(context: Context, url: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.applicationContext.startActivity(intent)
    }.isSuccess
}
