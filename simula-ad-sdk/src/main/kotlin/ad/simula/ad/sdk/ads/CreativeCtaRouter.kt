package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.StoreOpen
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.network.PrimaryCtaRoute
import ad.simula.ad.sdk.network.SimulaUserAgent
import ad.simula.ad.sdk.telemetry.Telemetry
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PendingAutomaticNavigation(
    val targetUrl: String,
    val trackerAlreadyRequested: Boolean,
)

internal class AutomaticNavigationAttempt internal constructor(
    internal val revision: Long,
    val route: PendingAutomaticNavigation,
)

internal enum class AutomaticNavigationOutcome { STORE_OPENED, OTHER_OPENED, HANDLED, FAILED }

internal enum class CtaTargetSource { MMP, RAW_STORE, DIRECT }

internal data class PreparedCtaTarget(
    val url: String,
    val source: CtaTargetSource,
)

internal sealed interface PreparedCtaOpen {
    data class Launch(
        val primary: PreparedCtaTarget,
        val fallback: PreparedCtaTarget?,
    ) : PreparedCtaOpen

    data object Handled : PreparedCtaOpen
    data object Failed : PreparedCtaOpen
}

internal class AutomaticNavigationGate {
    private var inFlight = false
    private var opened = false
    private var revision = 0L
    private var trackerRequestedInWebView = false
    private var pending: PendingAutomaticNavigation? = null

    @Synchronized
    fun retain(targetUrl: String, trackerAlreadyRequested: Boolean): Boolean {
        if (opened || targetUrl.isBlank() || targetUrl.length > MAX_AUTOMATIC_NAVIGATION_URL_CHARS) return false
        val retained = pending
        if (retained != null && retained.targetUrl != targetUrl) return false
        pending = PendingAutomaticNavigation(
            targetUrl = targetUrl,
            trackerAlreadyRequested = retained?.trackerAlreadyRequested == true ||
                trackerAlreadyRequested || trackerRequestedInWebView,
        )
        return true
    }

    @Synchronized
    fun markTrackerRequestedInWebView() {
        trackerRequestedInWebView = true
        pending = pending?.copy(trackerAlreadyRequested = true)
    }

    @Synchronized
    fun wasTrackerRequestedInWebView(): Boolean = trackerRequestedInWebView

    fun attemptPending(open: (PendingAutomaticNavigation) -> AutomaticNavigationOutcome): AutomaticNavigationOutcome {
        val attempt = beginPending() ?: return AutomaticNavigationOutcome.FAILED
        val outcome = runCatching { open(attempt.route) }.getOrDefault(AutomaticNavigationOutcome.FAILED)
        complete(attempt, outcome)
        return outcome
    }

    @Synchronized
    fun beginPending(): AutomaticNavigationAttempt? {
        if (inFlight || opened) return null
        val retained = pending ?: return null
        inFlight = true
        return AutomaticNavigationAttempt(++revision, retained)
    }

    @Synchronized
    fun isActive(attempt: AutomaticNavigationAttempt): Boolean =
        inFlight && !opened && revision == attempt.revision

    fun complete(attempt: AutomaticNavigationAttempt, outcome: AutomaticNavigationOutcome): Boolean {
        synchronized(this) {
            if (!inFlight || revision != attempt.revision) return false
            inFlight = false
            if (outcome != AutomaticNavigationOutcome.FAILED) {
                opened = true
                pending = null
            }
        }
        return true
    }

    @Synchronized
    fun abandonInFlight() {
        if (!inFlight || opened) return
        revision++
        inFlight = false
    }

    @Synchronized
    fun suppressPending() {
        revision++
        inFlight = false
        opened = true
        pending = null
    }

    @Synchronized
    fun clear() {
        revision++
        inFlight = false
        opened = true
        pending = null
    }

    @Synchronized
    fun hasPending(): Boolean = pending != null
}

private const val MAX_AUTOMATIC_NAVIGATION_URL_CHARS = 8 * 1024

/**
 * Routes a creative's call-to-action tap to its advertiser destination.
 *
 * App-store trackers use the same bounded no-follow HEAD chain as the Unity SDK. A strict final
 * Play details URL is launched exactly as returned, preserving its install-referrer query without
 * rendering intermediary tracker pages. Inconclusive chains fall back to opening the original
 * tracker in the browser so the tap is never swallowed.
 *
 * **Deterministic store fallback** ([storeUrl], the campaign's raw `android_store_url`): a strict
 * raw Play link covers a missing tracker or an unavailable handler. It never replaces a resolved
 * referrer-bearing Play URL.
 *
 * The final Play URL is never reduced to a package id or rebuilt as `market://`: doing so previously
 * dropped `referrer` and broke install attribution. Web destinations retain browser routing.
 *
 * Every `startActivity` is wrapped in [runCatching]. A live Activity is preferred; application
 * context plus `FLAG_ACTIVITY_NEW_TASK` is the safe fallback for surfaces without one.
 */
internal object CreativeCtaRouter {

    private val redirectResolver = PlayStoreRedirectResolver()

    internal sealed interface AutomaticNavigationPlan {
        data object AllowInWebView : AutomaticNavigationPlan
        data object Consume : AutomaticNavigationPlan
        data class RouteExact(val targetUrl: String) : AutomaticNavigationPlan
    }

    internal sealed interface PrimaryCtaTapPlan {
        data object AllowInWebView : PrimaryCtaTapPlan
        data object ConsumeWithoutClick : PrimaryCtaTapPlan
        data class Route(val route: PrimaryCtaRoute) : PrimaryCtaTapPlan
    }

    internal fun admittedHttpUrl(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (candidate.hasUrlControlCharacters()) return null
        val url = runCatching { URL(candidate) }.getOrNull() ?: return null
        return candidate.takeIf {
            (url.protocol.equals("http", true) || url.protocol.equals("https", true)) &&
                url.host.isNotBlank() &&
                url.userInfo == null &&
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

        val admittedTracker = admittedHttpUrl(trackingUrl)
        val externalTarget = admittedTracker
            ?: tappedDestination
            ?: customDestination
            ?: return PrimaryCtaTapPlan.ConsumeWithoutClick
        return PrimaryCtaTapPlan.Route(
            PrimaryCtaRoute(
                tappedUrl = tappedDestination,
                externalTarget = externalTarget,
                externalTargetIsTracker = admittedTracker != null,
            ),
        )
    }

    /** Builds an app-store CTA exclusively from validated metadata owned by the current serve. */
    internal fun trustedStoreRoute(
        destination: String,
        trackingUrl: String?,
        storeUrl: String?,
    ): PrimaryCtaRoute? {
        if (destination != "appstore") return null
        val tracker = admittedHttpUrl(trackingUrl)
        val store = admittedStoreFallback(storeUrl)
        return when {
            tracker != null -> PrimaryCtaRoute(
                tappedUrl = store,
                externalTarget = tracker,
                externalTargetIsTracker = true,
            )
            store != null -> PrimaryCtaRoute(
                tappedUrl = store,
                externalTarget = store,
            )
            else -> null
        }
    }

    internal fun fallbackCtaTapPlan(
        isMainFrame: Boolean,
        hasGesture: Boolean,
        tappedUrl: String,
        creativeBaseUrl: String?,
        trackingUrl: String?,
        destination: String,
    ): PrimaryCtaTapPlan {
        if (!isMainFrame) return PrimaryCtaTapPlan.AllowInWebView
        val scheme = tappedUrl.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme in setOf("about", "data", "blob")) return PrimaryCtaTapPlan.AllowInWebView
        if (!hasGesture) {
            return if (hasSameHttpOrigin(creativeBaseUrl, tappedUrl)) {
                PrimaryCtaTapPlan.AllowInWebView
            } else {
                PrimaryCtaTapPlan.ConsumeWithoutClick
            }
        }
        return primaryCtaTapPlan(tappedUrl, creativeBaseUrl, trackingUrl, destination)
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

    internal fun automaticNavigationPlan(
        value: String?,
        destination: String,
        trackingUrl: String? = null,
    ): AutomaticNavigationPlan {
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() }
            ?: return AutomaticNavigationPlan.AllowInWebView
        if (candidate.hasUrlControlCharacters()) return AutomaticNavigationPlan.Consume
        val separator = candidate.indexOf(':')
        if (separator <= 0) return AutomaticNavigationPlan.AllowInWebView
        val scheme = candidate.substring(0, separator).lowercase()
        if (!scheme.matches(URL_SCHEME_PATTERN)) return AutomaticNavigationPlan.Consume
        if (scheme in INTERNAL_WEBVIEW_SCHEMES) return AutomaticNavigationPlan.AllowInWebView
        if (scheme == "http" || scheme == "https") {
            val admitted = admittedHttpUrl(candidate) ?: return AutomaticNavigationPlan.Consume
            return if (matchesKnownTrackingUrl(admitted, trackingUrl) ||
                admittedDirectPlayStoreUrl(admitted) != null
            ) {
                AutomaticNavigationPlan.RouteExact(admitted)
            } else AutomaticNavigationPlan.AllowInWebView
        }
        val routable = when (scheme) {
            "market", "intent" -> normalizeTappedDestination(candidate)?.takeUnless {
                isPlayStoreHost(it) && admittedDirectPlayStoreUrl(it) == null
            }
            else -> admittedWebCustomDestination(candidate, destination)
        }
        return routable?.let(AutomaticNavigationPlan::RouteExact)
            ?: AutomaticNavigationPlan.Consume
    }

    internal fun matchesKnownTrackingUrl(value: String?, trackingUrl: String?): Boolean {
        val candidate = admittedHttpUrl(value)?.let { runCatching { URL(it) }.getOrNull() } ?: return false
        val tracker = admittedHttpUrl(trackingUrl)?.let { runCatching { URL(it) }.getOrNull() } ?: return false
        return candidate.protocol.equals(tracker.protocol, ignoreCase = true) &&
            candidate.host.equals(tracker.host, ignoreCase = true) &&
            candidate.effectivePort() == tracker.effectivePort() &&
            candidate.path.orEmpty().ifEmpty { "/" } == tracker.path.orEmpty().ifEmpty { "/" } &&
            candidate.query == tracker.query
    }

    internal fun admittedDirectPlayStoreUrl(value: String?): String? {
        val candidate = admittedHttpUrl(value) ?: return null
        val url = runCatching { URL(candidate) }.getOrNull() ?: return null
        if (!url.protocol.equals("https", ignoreCase = true) ||
            !url.host.equals("play.google.com", ignoreCase = true) ||
            url.path != "/store/apps/details"
        ) return null
        var packageIdCount = 0
        var packageId: String? = null
        for (part in url.query?.split('&').orEmpty()) {
            val separator = part.indexOf('=')
            if (separator <= 0) continue
            val key = decodeIntentValue(part.substring(0, separator)) ?: continue
            if (!key.equals("id", ignoreCase = true)) continue
            packageIdCount++
            packageId = decodeIntentValue(part.substring(separator + 1)) ?: return null
        }
        return candidate.takeIf { packageIdCount == 1 && !packageId.isNullOrBlank() }
    }

    private fun isPlayStoreHost(value: String?): Boolean {
        val candidate = admittedHttpUrl(value) ?: return false
        val url = runCatching { URL(candidate) }.getOrNull() ?: return false
        return url.host.equals("play.google.com", ignoreCase = true)
    }

    internal fun admittedInWebViewFallback(value: String?, trackingUrl: String?): String? {
        val candidate = admittedHttpUrl(value) ?: return null
        return candidate.takeUnless {
            matchesKnownTrackingUrl(it, trackingUrl) || admittedDirectPlayStoreUrl(it) != null
        }
    }

    internal fun primaryCtaStoreFallback(
        route: PrimaryCtaRoute,
        destination: String,
        storeUrl: String?,
    ): String? {
        if (destination != "appstore") return null
        return normalizeTappedDestination(storeUrl)
            ?: admittedDirectPlayStoreUrl(route.tappedUrl)
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
        if (uri.rawPath.orEmpty() !in setOf("", "/") || uri.rawFragment != null) return null
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
     * Opens the admitted tracker or deterministic app-store fallback in the browser.
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
        if (!route.externalTargetIsTracker) {
            val direct = normalizeTappedDestination(route.externalTarget) ?: return false
            val fallback = admittedStoreFallback(primaryCtaStoreFallback(route, destination, storeUrl))
                ?.takeIf { it != direct }
            return launchPrepared(
                context,
                PreparedCtaOpen.Launch(
                    primary = PreparedCtaTarget(direct, CtaTargetSource.DIRECT),
                    fallback = fallback?.let { PreparedCtaTarget(it, CtaTargetSource.RAW_STORE) },
                ),
            ) != AutomaticNavigationOutcome.FAILED
        }
        return open(
            context,
            route.externalTarget,
            destination,
            storeOpen,
            primaryCtaStoreFallback(route, destination, storeUrl),
        )
    }

    fun openAutomaticNavigation(
        context: Context,
        targetUrl: String,
        destination: String,
        trackingUrl: String? = null,
        trackerAlreadyRequested: Boolean = false,
    ): AutomaticNavigationOutcome = routeAutomaticNavigationOutcome(
        targetUrl = targetUrl,
        destination = destination,
        trackingUrl = trackingUrl,
        trackerAlreadyRequested = trackerAlreadyRequested,
        launch = { launch(context, it) },
        record = { name -> Telemetry.recordOperation(name, 0L, success = name != "mmp_route_failed") },
    )

    /** Resolve an app-store tracker using Unity's bounded no-follow HEAD flow. */
    internal suspend fun prepare(
        trackingUrl: String?,
        destination: String,
        storeUrl: String? = null,
        startedAtNanos: Long = System.nanoTime(),
        userAgent: String? = SimulaUserAgent.browserValue,
        resolver: PlayStoreRedirectResolver = redirectResolver,
    ): PreparedCtaOpen {
        val tracker = admittedHttpUrl(trackingUrl)
        val store = admittedStoreFallback(storeUrl).takeIf { destination == "appstore" }
        val initial = tracker ?: store ?: return PreparedCtaOpen.Failed
        if (destination != "appstore" || tracker == null) {
            return PreparedCtaOpen.Launch(
                primary = PreparedCtaTarget(
                    initial,
                    if (tracker != null) CtaTargetSource.MMP else CtaTargetSource.RAW_STORE,
                ),
                fallback = null,
            )
        }
        admittedDirectPlayStoreUrl(tracker)?.let { direct ->
            return PreparedCtaOpen.Launch(
                primary = PreparedCtaTarget(direct, CtaTargetSource.MMP),
                fallback = store?.takeIf { it != direct }?.let {
                    PreparedCtaTarget(it, CtaTargetSource.RAW_STORE)
                },
            )
        }

        val resolveStarted = System.nanoTime()
        runCatching { Telemetry.recordOperation("mmp_resolve_attempted", 0L, success = true) }
        return when (val resolution = resolver.resolve(tracker, userAgent, startedAtNanos)) {
            is PlayStoreRedirectResolution.Resolved -> {
                runCatching {
                    Telemetry.recordOperation(
                        "mmp_resolve_store_success",
                        elapsedMs(resolveStarted),
                        success = true,
                    )
                }
                PreparedCtaOpen.Launch(
                    primary = PreparedCtaTarget(resolution.url, CtaTargetSource.MMP),
                    fallback = store?.takeIf { it != resolution.url }?.let {
                        PreparedCtaTarget(it, CtaTargetSource.RAW_STORE)
                    },
                )
            }
            is PlayStoreRedirectResolution.BrowserFallback -> {
                runCatching {
                    Telemetry.recordOperation(
                        "mmp_resolve_${resolution.reason.name.lowercase()}",
                        elapsedMs(resolveStarted),
                        success = false,
                    )
                }
                PreparedCtaOpen.Launch(
                    primary = PreparedCtaTarget(resolution.url, CtaTargetSource.MMP),
                    fallback = store?.takeIf { it != resolution.url }?.let {
                        PreparedCtaTarget(it, CtaTargetSource.RAW_STORE)
                    },
                )
            }
        }
    }

    internal suspend fun preparePrimaryCta(
        route: PrimaryCtaRoute,
        destination: String,
        storeUrl: String? = null,
        startedAtNanos: Long = System.nanoTime(),
        userAgent: String? = SimulaUserAgent.browserValue,
    ): PreparedCtaOpen {
        if (route.externalTargetIsTracker) {
            return prepare(
                trackingUrl = route.externalTarget,
                destination = destination,
                storeUrl = primaryCtaStoreFallback(route, destination, storeUrl),
                startedAtNanos = startedAtNanos,
                userAgent = userAgent,
            )
        }
        val direct = admittedWebCustomDestination(route.externalTarget, destination)
            ?: normalizeTappedDestination(route.externalTarget)
            ?: return PreparedCtaOpen.Failed
        val fallback = admittedStoreFallback(primaryCtaStoreFallback(route, destination, storeUrl))
            ?.takeIf { it != direct }
        return PreparedCtaOpen.Launch(
            primary = PreparedCtaTarget(direct, CtaTargetSource.DIRECT),
            fallback = fallback?.let { PreparedCtaTarget(it, CtaTargetSource.RAW_STORE) },
        )
    }

    internal suspend fun prepareAutomaticNavigation(
        targetUrl: String,
        destination: String,
        trackingUrl: String? = null,
        trackerAlreadyRequested: Boolean = false,
        startedAtNanos: Long = System.nanoTime(),
        userAgent: String? = SimulaUserAgent.browserValue,
    ): PreparedCtaOpen {
        val admittedHttp = admittedHttpUrl(targetUrl)
        val target = if (admittedHttp != null) {
            if (isPlayStoreHost(admittedHttp) && admittedDirectPlayStoreUrl(admittedHttp) == null) {
                return PreparedCtaOpen.Failed
            }
            admittedHttp
        } else {
            normalizeTappedDestination(targetUrl)
                ?: admittedWebCustomDestination(targetUrl, destination)
                ?: return PreparedCtaOpen.Failed
        }
        val targetIsTracker = matchesKnownTrackingUrl(target, trackingUrl)
        if (trackerAlreadyRequested) {
            return if (targetIsTracker) PreparedCtaOpen.Handled else PreparedCtaOpen.Launch(
                primary = PreparedCtaTarget(target, CtaTargetSource.DIRECT),
                fallback = null,
            )
        }
        if (targetIsTracker) {
            return prepare(target, destination, startedAtNanos = startedAtNanos, userAgent = userAgent)
        }
        admittedDirectPlayStoreUrl(target)?.let { play ->
            return prepare(
                trackingUrl = trackingUrl,
                destination = "appstore",
                storeUrl = play,
                startedAtNanos = startedAtNanos,
                userAgent = userAgent,
            )
        }
        val tracker = admittedHttpUrl(trackingUrl)
        return if (tracker != null) {
            when (val prepared = prepare(
                tracker,
                destination,
                startedAtNanos = startedAtNanos,
                userAgent = userAgent,
            )) {
                is PreparedCtaOpen.Launch -> prepared.copy(
                    fallback = prepared.fallback ?: target.takeIf { it != prepared.primary.url }?.let {
                        PreparedCtaTarget(it, CtaTargetSource.DIRECT)
                    },
                )
                else -> prepared
            }
        } else {
            PreparedCtaOpen.Launch(
                primary = PreparedCtaTarget(target, CtaTargetSource.DIRECT),
                fallback = null,
            )
        }
    }

    internal fun launchPrepared(context: Context, prepared: PreparedCtaOpen): AutomaticNavigationOutcome =
        launchPrepared(
            prepared = prepared,
            launch = { launchUrl(context, it) },
            record = { name -> Telemetry.recordOperation(name, 0L, success = name != "mmp_route_failed") },
        )

    internal fun launchPrepared(
        prepared: PreparedCtaOpen,
        launch: (String) -> Boolean,
        record: (String) -> Unit = {},
    ): AutomaticNavigationOutcome = when (prepared) {
        PreparedCtaOpen.Failed -> AutomaticNavigationOutcome.FAILED
        PreparedCtaOpen.Handled -> AutomaticNavigationOutcome.HANDLED
        is PreparedCtaOpen.Launch -> {
            var mmpAttempted = false
            fun attempt(target: PreparedCtaTarget): Boolean {
                when (target.source) {
                    CtaTargetSource.MMP -> {
                        mmpAttempted = true
                        runCatching { record("mmp_route_attempted") }
                    }
                    CtaTargetSource.RAW_STORE -> runCatching { record("mmp_raw_store_fallback") }
                    CtaTargetSource.DIRECT -> Unit
                }
                return runCatching { launch(target.url) }.getOrDefault(false)
            }

            val openedTarget = if (attempt(prepared.primary)) {
                prepared.primary
            } else {
                prepared.fallback?.takeIf(::attempt)
            }
            if (openedTarget == null) {
                if (mmpAttempted) runCatching { record("mmp_route_failed") }
                AutomaticNavigationOutcome.FAILED
            } else if (admittedDirectPlayStoreUrl(openedTarget.url) != null) {
                AutomaticNavigationOutcome.STORE_OPENED
            } else {
                AutomaticNavigationOutcome.OTHER_OPENED
            }
        }
    }

    internal fun prepareInBackground(
        prepare: suspend () -> PreparedCtaOpen,
        onPrepared: (PreparedCtaOpen) -> Unit,
    ): Job = SimulaScope.launch {
        val prepared = try {
            prepare()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            PreparedCtaOpen.Failed
        }
        withContext(Dispatchers.Main.immediate) {
            runCatching { onPrepared(prepared) }
        }
    }

    internal fun routeAutomaticNavigation(
        targetUrl: String,
        destination: String,
        trackingUrl: String?,
        trackerAlreadyRequested: Boolean = false,
        launch: (String) -> Boolean,
        record: (String) -> Unit = {},
    ): Boolean = routeAutomaticNavigationOutcome(
        targetUrl,
        destination,
        trackingUrl,
        trackerAlreadyRequested,
        launch,
        record,
    ).let { it == AutomaticNavigationOutcome.STORE_OPENED || it == AutomaticNavigationOutcome.OTHER_OPENED }

    internal fun routeAutomaticNavigationOutcome(
        targetUrl: String,
        destination: String,
        trackingUrl: String?,
        trackerAlreadyRequested: Boolean = false,
        launch: (String) -> Boolean,
        record: (String) -> Unit = {},
    ): AutomaticNavigationOutcome {
        val admittedHttp = admittedHttpUrl(targetUrl)
        val target = if (admittedHttp != null) {
            if (isPlayStoreHost(admittedHttp) && admittedDirectPlayStoreUrl(admittedHttp) == null) {
                return AutomaticNavigationOutcome.FAILED
            }
            admittedHttp
        } else {
            normalizeTappedDestination(targetUrl)
                ?: admittedWebCustomDestination(targetUrl, destination)
                ?: return AutomaticNavigationOutcome.FAILED
        }
        val targetIsTracker = matchesKnownTrackingUrl(target, trackingUrl)
        val storeBound = destination == "appstore" || admittedDirectPlayStoreUrl(target) != null
        if (trackerAlreadyRequested) {
            if (targetIsTracker) return AutomaticNavigationOutcome.HANDLED
            return runCatching { launch(target) }.getOrDefault(false).toAutomaticNavigationOutcome(storeBound)
        }
        if (targetIsTracker) {
            return runCatching { launch(target) }.getOrDefault(false)
                .toAutomaticNavigationOutcome(destination == "appstore")
        }
        if (admittedDirectPlayStoreUrl(target) != null) {
            return routeCta(
                trackingUrl = trackingUrl,
                destination = "appstore",
                storeUrl = target,
                launch = launch,
                record = record,
            ).toAutomaticNavigationOutcome(storeBound = true)
        }
        val tracker = admittedHttpUrl(trackingUrl)
        if (tracker != null) {
            runCatching { record("mmp_route_attempted") }
            if (runCatching { launch(tracker) }.getOrDefault(false)) {
                return if (storeBound) {
                    AutomaticNavigationOutcome.STORE_OPENED
                } else {
                    AutomaticNavigationOutcome.OTHER_OPENED
                }
            }
            val opened = runCatching { launch(target) }.getOrDefault(false)
            if (!opened) runCatching { record("mmp_route_failed") }
            return opened.toAutomaticNavigationOutcome(storeBound)
        }
        return runCatching { launch(target) }.getOrDefault(false).toAutomaticNavigationOutcome(storeBound)
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

    private fun admittedStoreFallback(value: String?): String? =
        normalizeTappedDestination(value)?.let(::admittedDirectPlayStoreUrl)

    private fun elapsedMs(startNanos: Long): Long =
        ((System.nanoTime() - startNanos).coerceAtLeast(0L) / 1_000_000L)

    private fun launch(context: Context, url: String): Boolean = launchUrl(context, url)

    private fun launchUrl(context: Context, url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val activity = context.findActivity()?.takeUnless { it.isFinishing || it.isDestroyed }
        val launchContext = activity ?: context.applicationContext
        val directPlay = admittedDirectPlayStoreUrl(url) != null
        if (directPlay) {
            val playIntent = Intent(Intent.ACTION_VIEW, uri).setPackage(PLAY_STORE_PACKAGE)
            if (activity == null) playIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { launchContext.startActivity(playIntent) }.isSuccess) return true
        }
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { launchContext.startActivity(intent) }.isSuccess
    }

    private const val PLAY_STORE_PACKAGE = "com.android.vending"
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    val seen = HashSet<Context>(4)
    while (current != null && seen.add(current)) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

private fun Boolean.toAutomaticNavigationOutcome(storeBound: Boolean): AutomaticNavigationOutcome = when {
    !this -> AutomaticNavigationOutcome.FAILED
    storeBound -> AutomaticNavigationOutcome.STORE_OPENED
    else -> AutomaticNavigationOutcome.OTHER_OPENED
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
    if (explicitPort.isEmpty()) return true
    if (explicitPort.any { it !in '0'..'9' }) return false
    return explicitPort.toIntOrNull()?.let { it in 0..65535 } == true
}

private fun String.hasUrlControlCharacters(): Boolean = any { it.code in 0..31 || it.code in 127..159 }

private val INTERNAL_WEBVIEW_SCHEMES = setOf("about", "blob", "data", "file", "javascript")
private val URL_SCHEME_PATTERN = Regex("[a-z][a-z0-9+.-]*")
