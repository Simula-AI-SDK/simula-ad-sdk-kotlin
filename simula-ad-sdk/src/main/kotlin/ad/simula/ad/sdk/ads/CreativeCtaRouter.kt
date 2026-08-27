package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.StoreOpen
import ad.simula.ad.sdk.network.PrimaryCtaRoute
import ad.simula.ad.sdk.telemetry.Telemetry
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI
import java.net.URL
import java.net.URLDecoder

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

    internal sealed interface PrimaryCtaTapPlan {
        data object AllowInWebView : PrimaryCtaTapPlan
        data object ConsumeWithoutClick : PrimaryCtaTapPlan
        data class Route(val route: PrimaryCtaRoute) : PrimaryCtaTapPlan
    }

    /** Prefer the attribution URL carried outside rendered HTML, whose script text may HTML-escape
     * query separators. Older payloads without that field keep using the creative's tapped URL. */
    internal fun preferredClickUrl(trackingUrl: String?, embeddedUrl: String): String? =
        admittedHttpUrl(trackingUrl) ?: normalizeTappedDestination(embeddedUrl)

    internal fun admittedHttpUrl(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (candidate.hasUrlControlCharacters()) return null
        val url = runCatching { URL(candidate) }.getOrNull() ?: return null
        return candidate.takeIf {
            (url.protocol.equals("http", true) || url.protocol.equals("https", true)) &&
                url.host.isNotBlank() &&
                url.host.none { char ->
                    char.isWhitespace() || Character.getType(char) == Character.FORMAT.toInt()
                } &&
                url.hasValidExplicitPort()
        }
    }

    internal fun hasSameHttpOrigin(first: String?, second: String?): Boolean {
        val firstUrl = admittedHttpUrl(first)?.let { runCatching { URL(it) }.getOrNull() } ?: return false
        val secondUrl = admittedHttpUrl(second)?.let { runCatching { URL(it) }.getOrNull() } ?: return false
        return firstUrl.protocol.equals(secondUrl.protocol, ignoreCase = true) &&
            firstUrl.host.equals(secondUrl.host, ignoreCase = true) &&
            firstUrl.effectivePort() == secondUrl.effectivePort()
    }

    /**
     * Separates proof of an advertiser exit from route-target selection. A serve tracker may replace
     * an admitted external destination, but it must never turn document-local or unsafe navigation
     * into a billable click.
     */
    internal fun primaryCtaTapPlan(
        tappedUrl: String?,
        creativeBaseUrl: String?,
        trackingUrl: String?,
        destination: String,
    ): PrimaryCtaTapPlan {
        val candidate = tappedUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: return PrimaryCtaTapPlan.AllowInWebView
        if (candidate.hasUrlControlCharacters()) return PrimaryCtaTapPlan.ConsumeWithoutClick
        if (candidate.startsWith('#')) return PrimaryCtaTapPlan.AllowInWebView

        val separator = candidate.indexOf(':')
        if (separator <= 0) return PrimaryCtaTapPlan.AllowInWebView
        val scheme = candidate.substring(0, separator).lowercase()
        if (!scheme.matches(URL_SCHEME_PATTERN)) return PrimaryCtaTapPlan.ConsumeWithoutClick
        if (scheme in INTERNAL_WEBVIEW_SCHEMES) return PrimaryCtaTapPlan.AllowInWebView

        val tappedDestination = normalizeTappedDestination(candidate)
        var customDestination: String? = null
        when (scheme) {
            "http", "https" -> {
                if (tappedDestination == null) return PrimaryCtaTapPlan.ConsumeWithoutClick
                if (hasSameHttpOrigin(creativeBaseUrl, tappedDestination)) {
                    return PrimaryCtaTapPlan.AllowInWebView
                }
            }
            "market", "intent" -> {
                if (tappedDestination == null) return PrimaryCtaTapPlan.ConsumeWithoutClick
            }
            else -> {
                customDestination = admittedWebCustomDestination(candidate, destination)
                    ?: return PrimaryCtaTapPlan.ConsumeWithoutClick
            }
        }

        val externalTarget = admittedHttpUrl(trackingUrl)
            ?: tappedDestination
            ?: customDestination
            ?: return PrimaryCtaTapPlan.ConsumeWithoutClick
        return PrimaryCtaTapPlan.Route(
            PrimaryCtaRoute(
                tappedUrl = tappedDestination,
                externalTarget = externalTarget,
            ),
        )
    }

    internal fun admittedWebCustomDestination(value: String?, destination: String): String? {
        if (destination != "web") return null
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (candidate.hasUrlControlCharacters()) return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it.matches(URL_SCHEME_PATTERN) } ?: return null
        if (scheme in INTERNAL_WEBVIEW_SCHEMES || scheme in setOf("http", "https", "market", "intent")) {
            return null
        }
        return candidate.takeIf { !uri.rawSchemeSpecificPart.isNullOrBlank() }
    }

    /**
     * Safely normalizes a user-tapped fallback destination without broadening ordinary MMP tracker
     * admission. HTTP(S) remains byte-preserving. A strict Play `market://details` link is converted
     * to its HTTPS equivalent, while an Android intent URI contributes only its encoded HTTP(S)
     * browser fallback and is never launched directly.
     */
    internal fun normalizeTappedDestination(value: String?): String? {
        admittedHttpUrl(value)?.let { return it }
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (candidate.hasUrlControlCharacters()) return null
        return when {
            candidate.startsWith("market://", ignoreCase = true) -> normalizeMarketDestination(candidate)
            candidate.startsWith("intent://", ignoreCase = true) -> normalizeIntentFallback(candidate)
            else -> null
        }
    }

    private fun normalizeMarketDestination(candidate: String): String? {
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!uri.scheme.equals("market", ignoreCase = true)) return null
        if (!uri.rawAuthority.equals("details", ignoreCase = true)) return null
        if (!uri.rawPath.isNullOrEmpty() || uri.rawFragment != null) return null
        val rawQuery = uri.rawQuery?.takeIf { it.isNotEmpty() } ?: return null
        val hasPackageId = rawQuery.split('&').any { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@any false
            val key = decodeIntentValue(part.substring(0, separator)) ?: return@any false
            val value = decodeIntentValue(part.substring(separator + 1)) ?: return@any false
            key.equals("id", ignoreCase = true) && value.isNotBlank()
        }
        if (!hasPackageId) return null
        return "https://play.google.com/store/apps/details?$rawQuery"
    }

    private fun normalizeIntentFallback(candidate: String): String? {
        val marker = candidate.indexOf("#Intent;")
        if (marker < "intent://".length || !candidate.endsWith(";end")) return null
        val tokens = candidate.substring(marker + "#Intent;".length, candidate.length - ";end".length)
            .split(';')
        if (tokens.any { token ->
                token.equals("SEL", ignoreCase = true) ||
                    token.substringBefore('=').equals("component", ignoreCase = true) ||
                    token.substringBefore('=').equals("selector", ignoreCase = true)
            }
        ) {
            return null
        }
        val fallbacks = tokens.mapNotNull { token ->
            token.takeIf { it.startsWith("S.browser_fallback_url=") }
                ?.substringAfter('=')
        }
        if (fallbacks.size != 1) return null
        val decoded = decodeIntentValue(fallbacks.single()) ?: return null
        return admittedHttpUrl(decoded)
    }

    private fun decodeIntentValue(value: String): String? =
        runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrNull()

    /**
     * The URL a creative CTA should open: the tracking link itself, trimmed and **verbatim**
     * (never rewritten into a store URL), or — when the tracker is blank/missing AND the
     * [destination] is the app store — the campaign's safely normalized [storeUrl], so the CTA deterministically
     * lands on the store instead of silently no-oping. A web-destination CTA never falls back to
     * the store link. `null` when nothing is applicable (the caller then no-ops). Pure and
     * framework-free so the "never rewrite the tracker" contract can be unit-tested.
     */
    internal fun targetUrl(
        trackingUrl: String?,
        storeUrl: String? = null,
        destination: String = "appstore",
    ): String? =
        admittedHttpUrl(trackingUrl)
            ?: normalizeTappedDestination(storeUrl).takeIf { destination == "appstore" }

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
        return routeCta(
            trackingUrl = trackingUrl,
            destination = destination,
            storeUrl = storeUrl,
            launch = { launch(context, it) },
            record = { name -> Telemetry.recordOperation(name, 0L, success = name != "mmp_route_failed") },
        )
    }

    fun openPrimaryCta(
        context: Context,
        route: PrimaryCtaRoute,
        destination: String,
        storeOpen: StoreOpen? = null,
        storeUrl: String? = null,
    ): Boolean {
        admittedWebCustomDestination(route.externalTarget, destination)?.let { custom ->
            return launch(context, custom)
        }
        return open(context, route.externalTarget, destination, storeOpen, storeUrl)
    }

    internal fun routeCta(
        trackingUrl: String?,
        destination: String,
        storeUrl: String?,
        launch: (String) -> Boolean,
        record: (String) -> Unit = {},
    ): Boolean {
        val admittedTracking = admittedHttpUrl(trackingUrl)
        val admittedStore = normalizeTappedDestination(storeUrl).takeIf { destination == "appstore" }
        val url = admittedTracking ?: admittedStore ?: run {
            runCatching { record("mmp_route_failed") }
            return false
        }
        if (admittedTracking == null && admittedStore == url) {
            runCatching { record("mmp_raw_store_fallback") }
        }
        runCatching { record("mmp_route_attempted") }
        if (runCatching { launch(url) }.getOrDefault(false)) return true
        // Deterministic fallback (appstore destinations only): the tracker had no handler / was
        // malformed — land the CTA on the raw store link instead of dropping it.
        if (destination != "appstore") {
            runCatching { record("mmp_route_failed") }
            return false
        }
        val fallback = admittedStore?.takeIf { it != url } ?: run {
            runCatching { record("mmp_route_failed") }
            return false
        }
        runCatching { record("mmp_raw_store_fallback") }
        val opened = runCatching { launch(fallback) }.getOrDefault(false)
        if (!opened) runCatching { record("mmp_route_failed") }
        return opened
    }

    private fun launch(context: Context, url: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.applicationContext.startActivity(intent)
    }.isSuccess
}

private fun URL.effectivePort(): Int = if (port >= 0) port else defaultPort

private fun URL.hasValidExplicitPort(): Boolean {
    val hostAndPort = authority.substringAfterLast('@')
    val explicitPort = if (hostAndPort.startsWith('[')) {
        val bracket = hostAndPort.indexOf(']')
        if (bracket < 0) return false
        val suffix = hostAndPort.substring(bracket + 1)
        if (suffix.isEmpty()) return true
        if (!suffix.startsWith(':')) return false
        suffix.substring(1)
    } else {
        val separator = hostAndPort.lastIndexOf(':')
        if (separator < 0) return true
        hostAndPort.substring(separator + 1)
    }
    if (explicitPort.isEmpty() || explicitPort.any { it !in '0'..'9' }) return false
    return explicitPort.toIntOrNull()?.let { it in 0..65535 } == true
}

private fun String.hasUrlControlCharacters(): Boolean = any { it.code in 0..31 || it.code == 127 }

private val INTERNAL_WEBVIEW_SCHEMES = setOf("about", "blob", "data", "file", "javascript")
private val URL_SCHEME_PATTERN = Regex("[a-z][a-z0-9+.-]*")
