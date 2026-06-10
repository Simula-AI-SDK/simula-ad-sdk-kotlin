package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.model.AdBehavior
import ad.simula.ad.sdk.model.AdUnitType
import ad.simula.ad.sdk.model.CloseBehavior
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.CloseTreatment
import ad.simula.ad.sdk.model.Creative
import ad.simula.ad.sdk.model.Experiment
import ad.simula.ad.sdk.model.MAX_CLOSE_DELAY_SECONDS
import ad.simula.ad.sdk.model.OverlayPosition
import ad.simula.ad.sdk.model.OverlayTiming
import ad.simula.ad.sdk.model.SkOverlayConfig
import ad.simula.ad.sdk.model.StoreOpen
import ad.simula.ad.sdk.model.StorePrompt
import ad.simula.ad.sdk.model.StorePromptPlatform
import ad.simula.ad.sdk.model.validatedHexColor
import ad.simula.ad.sdk.om.OmVerification
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SessionResponse(
    @SerialName("sessionId") val sessionId: String? = null,
    // Optional server-side telemetry directive: a runtime kill-switch + perf sampling rate,
    // letting telemetry volume be dialed without an SDK release. Absent → SDK defaults apply.
    @SerialName("telemetry_enabled") val telemetryEnabled: Boolean? = null,
    @SerialName("telemetry_sample_rate") val telemetrySampleRate: Double? = null,
)

@Serializable
internal data class InitMinigameRequestBody(
    @SerialName("game_type") val gameType: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("conv_id") val convId: String? = null,
    @SerialName("currency_mode") val currencyMode: Boolean = false,
    val w: Int,
    val h: Int,
    @SerialName("char_id") val charId: String? = null,
    @SerialName("char_name") val charName: String? = null,
    @SerialName("char_image") val charImage: String? = null,
    @SerialName("char_desc") val charDesc: String? = null,
    val messages: List<MessageBody>? = null,
    @SerialName("delegate_char") val delegateChar: Boolean = true,
    @SerialName("menu_id") val menuId: String? = null,
)

@Serializable
internal data class MessageBody(
    val role: String,
    val content: String,
)

@Serializable
internal data class MinigameApiResponse(
    val adType: String? = null,
    val adInserted: Boolean = false,
    val adResponse: AdResponseBody? = null,
)

@Serializable
internal data class AdResponseBody(
    @SerialName("ad_id") val adId: String? = null,
    // The minigame serve id — the handle for `GET /load/fallbacks/{impression_id}`.
    @SerialName("serve_id") val serveId: String? = null,
    @SerialName("iframe_url") val iframeUrl: String? = null,
    // Optional OMID verification resources (forward-compatible; absent today).
    @SerialName("ad_verifications") val adVerifications: List<ApiAdVerification>? = null,
)

/** Payload from `GET /load/fallbacks/{impression_id}` — every ad screen linked to the serve
 * (campaign creative, then the "Get the App" end screen) in reveal order. */
@Serializable
internal data class FallbackAdsApiResponse(
    @SerialName("impression_id") val impressionId: String = "",
    val ads: List<FallbackAdBody> = emptyList(),
)

@Serializable
internal data class FallbackAdBody(
    // Each screen carries its own ad id (the per-screen impression for report/tracking).
    @SerialName("ad_id") val adId: String = "",
    val html: String? = null,
    @SerialName("iframe_url") val iframeUrl: String? = null,
    // Optional OMID verification resources (forward-compatible; absent today).
    @SerialName("ad_verifications") val adVerifications: List<ApiAdVerification>? = null,
)

@Serializable
internal data class MenuGameClickBody(
    @SerialName("menu_id") val menuId: String,
    @SerialName("game_name") val gameName: String,
)

@Serializable
internal data class AdLoadRequestBody(
    @SerialName("ad_unit_id") val adUnitId: String,
    @SerialName("session_id") val sessionId: String = "",
    // Optional character context the backend can use to target the creative.
    @SerialName("char_id") val charId: String? = null,
    @SerialName("char_name") val charName: String? = null,
    @SerialName("char_image") val charImage: String? = null,
    @SerialName("char_desc") val charDesc: String? = null,
    // Device capability snapshot so the backend never assigns an unsupported variant. Defaults to a
    // neutral value (no framework access) so pure-JVM tests can construct this; the ad path injects
    // the real values via `currentDeviceCapabilities()`.
    val capabilities: ApiDeviceCapabilities = ApiDeviceCapabilities(),
)

@Serializable
internal data class AdLoadApiResponse(
    // The impression (minigame serve) id — the SDK's single handle for fallbacks,
    // tracking and reporting. Nullable: the server sends an explicit null on a no-fill.
    @SerialName("impression_id") val impressionId: String? = null,
    @SerialName("ad_inserted") val adInserted: Boolean = false,
    @SerialName("ad_unit_id") val adUnitId: String = "",
    val destination: String = "appstore",
    @SerialName("rendered_format") val renderedFormat: String? = null,
    @SerialName("tracking_url") val trackingUrl: String? = null,
    // Server-rendered HTML creative. When present (non-blank) it is rendered
    // full-screen in a WebView — the imperative interstitial's sole creative.
    @SerialName("rendered_html") val renderedHtml: String? = null,
    // Null when the payload omits `ad_behavior` — the renderer falls back to today's defaults.
    @SerialName("ad_behavior") val adBehavior: ApiAdBehavior? = null,
    val creative: ApiCreative? = null,
    val experiment: ApiExperiment? = null,
    // Optional OMID verification resources (forward-compatible; absent today).
    @SerialName("ad_verifications") val adVerifications: List<ApiAdVerification>? = null,
)

// ── Capability handshake ──────────────────────────────────────────────────────

@Serializable
internal data class ApiDeviceCapabilities(
    @SerialName("os_version") val osVersion: String = "",
    @SerialName("api_level") val apiLevel: Int = 0,
    @SerialName("play_services_available") val playServicesAvailable: Boolean = false,
    @SerialName("install_referrer_available") val installReferrerAvailable: Boolean = false,
)

/** Reads the running device's capabilities (Android framework). Called from the ad path only —
 * never from pure-JVM parsing tests — so the `Build` access here stays out of those tests. */
internal fun currentDeviceCapabilities(): ApiDeviceCapabilities = ApiDeviceCapabilities(
    osVersion = android.os.Build.VERSION.RELEASE ?: "",
    apiLevel = android.os.Build.VERSION.SDK_INT,
    // Play Install Prompt requires API 21+; refine with a GoogleApiAvailability check if the dep is present.
    playServicesAvailable = android.os.Build.VERSION.SDK_INT >= 21,
    installReferrerAvailable = android.os.Build.VERSION.SDK_INT >= 21,
)

// ── Ad behavior (server-driven A/B render config) ─────────────────────────────

@Serializable
internal data class ApiAdBehavior(
    val close: ApiCloseBehavior? = null,
    @SerialName("store_open") val storeOpen: String? = null,
    @SerialName("store_prompt") val storePrompt: ApiStorePrompt? = null,
    val skoverlay: ApiSkOverlay? = null,
)

@Serializable
internal data class ApiCloseBehavior(
    @SerialName("delay_seconds") val delaySeconds: Int = 0,
    val treatment: String? = null,
    val position: String? = null,
    @SerialName("progress_bar_color") val progressBarColor: String? = null,
)

@Serializable
internal data class ApiCreative(
    val type: String = "",
    @SerialName("bundle_url") val bundleUrl: String? = null,
    @SerialName("ad_unit_type") val adUnitType: String? = null,
)

@Serializable
internal data class ApiExperiment(
    @SerialName("experiment_id") val experimentId: String? = null,
    @SerialName("variant_id") val variantId: String? = null,
    val layer: String? = null,
)

// ── Open Measurement (OMID) verification resources ────────────────────────────

/** One verification vendor entry from a response `ad_verifications` array. All fields
 * are optional so an unexpected/partial shape is tolerated rather than failing the parse. */
@Serializable
internal data class ApiAdVerification(
    @SerialName("vendor_key") val vendorKey: String? = null,
    @SerialName("javascript_resource_url") val javascriptResourceUrl: String? = null,
    @SerialName("verification_parameters") val verificationParameters: String? = null,
)

/** Maps wire verification DTOs to domain [OmVerification]s, dropping entries without a
 * usable JS resource URL. A null/absent array → empty list (no measurement). */
internal fun List<ApiAdVerification>?.toOmVerifications(): List<OmVerification> {
    if (this.isNullOrEmpty()) return emptyList()
    return mapNotNull { v ->
        val url = v.javascriptResourceUrl
        if (url.isNullOrBlank()) null
        else OmVerification(vendorKey = v.vendorKey, url = url, parameters = v.verificationParameters)
    }
}

@Serializable
internal data class ApiStorePrompt(
    val enabled: Boolean = false,
    val trigger: String = "midpoint",
    val position: String? = null,
    val platform: String? = null,
)

@Serializable
internal data class ApiSkOverlay(
    val enabled: Boolean = false,
    val timing: String? = null,
    @SerialName("delay_seconds") val delaySeconds: Int = 0,
    val position: String? = null,
    val dismissible: Boolean = true,
)

/** Maps the wire DTO to the domain model, normalizing enum strings. A null DTO (absent
 * `ad_behavior`) stays null so callers can preserve today's literal behavior. */
internal fun ApiAdBehavior?.toDomain(): AdBehavior? {
    if (this == null) return null
    return AdBehavior(
        close = close.toDomain(),
        storeOpen = StoreOpen.from(storeOpen),
        storePrompt = storePrompt.toDomain(),
        skoverlay = skoverlay.toDomain(),
    )
}

internal fun ApiCloseBehavior?.toDomain(): CloseBehavior {
    if (this == null) return CloseBehavior()
    // Every treatment honors the configured corner. (`progress_bar` renders its bar at the top edge
    // regardless; only its resolved close ✕ follows `position`.)
    return CloseBehavior(
        // Clamp to [0, MAX] so a bad/oversized value can't trap the user behind a blocked close.
        delaySeconds = delaySeconds.coerceIn(0, MAX_CLOSE_DELAY_SECONDS),
        treatment = CloseTreatment.from(treatment),
        position = ClosePosition.from(position),
        progressBarColor = validatedHexColor(progressBarColor),
    )
}

internal fun ApiCreative?.toDomain(): Creative? {
    if (this == null) return null
    return Creative(type = type, bundleUrl = bundleUrl, adUnitType = AdUnitType.from(adUnitType))
}

internal fun ApiExperiment?.toDomain(): Experiment? {
    if (this == null) return null
    return Experiment(experimentId = experimentId, variantId = variantId, layer = layer)
}

internal fun ApiStorePrompt?.toDomain(): StorePrompt? {
    if (this == null) return null
    return StorePrompt(
        enabled = enabled,
        trigger = trigger,
        position = ClosePosition.from(position),
        platform = StorePromptPlatform.from(platform),
    )
}

internal fun ApiSkOverlay?.toDomain(): SkOverlayConfig? {
    if (this == null) return null
    return SkOverlayConfig(
        enabled = enabled,
        timing = OverlayTiming.from(timing),
        delaySeconds = delaySeconds.coerceAtLeast(0),
        position = OverlayPosition.from(position),
        dismissible = dismissible,
    )
}

// ── Rewarded minigame (init / verify) ─────────────────────────────────────────

@Serializable
internal data class RewardedInitRequestBody(
    @SerialName("ad_unit_id") val adUnitId: String,
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("min_play_threshold") val minPlayThreshold: Int? = null,
    // Optional character context the backend can use to target the minigame.
    @SerialName("char_id") val charId: String? = null,
    @SerialName("char_name") val charName: String? = null,
    @SerialName("char_image") val charImage: String? = null,
    @SerialName("char_desc") val charDesc: String? = null,
)

@Serializable
internal data class RewardedInitApiResponse(
    // The impression (minigame serve) id — replaces the old `serve_id`/`ad_id` pair as the
    // single handle for verify-reward, fallbacks, tracking and reporting.
    @SerialName("impression_id") val impressionId: String = "",
    @SerialName("iframe_url") val iframeUrl: String = "",
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    // Mirrors the interstitial response: drives the mid-ad store prompt + its tap routing.
    // Null/absent → no store prompt (today's behavior).
    val destination: String = "appstore",
    @SerialName("tracking_url") val trackingUrl: String? = null,
    @SerialName("ad_behavior") val adBehavior: ApiAdBehavior? = null,
    // Optional OMID verification resources (forward-compatible; absent today).
    @SerialName("ad_verifications") val adVerifications: List<ApiAdVerification>? = null,
)

@Serializable
internal data class VerifyRewardRequestBody(
    @SerialName("serve_id") val serveId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("elapsed_play_time") val elapsedPlayTime: Double,
)

@Serializable
internal data class VerifyRewardApiResponse(
    val verified: Boolean = false,
    val token: String? = null,
)
