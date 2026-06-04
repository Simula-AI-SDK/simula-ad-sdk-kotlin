package ad.simula.ad.sdk.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SessionResponse(
    @SerialName("sessionId") val sessionId: String? = null,
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
    @SerialName("iframe_url") val iframeUrl: String? = null,
)

@Serializable
internal data class MenuGameClickBody(
    @SerialName("menu_id") val menuId: String,
    @SerialName("game_name") val gameName: String,
)

@Serializable
internal data class AdLoadRequestBody(
    @SerialName("ad_unit_id") val adUnitId: String,
    val rewarded: Boolean = false,
    @SerialName("session_id") val sessionId: String = "",
    // Optional character context the backend can use to target the creative.
    @SerialName("char_id") val charId: String? = null,
    @SerialName("char_name") val charName: String? = null,
    @SerialName("char_image") val charImage: String? = null,
    @SerialName("char_desc") val charDesc: String? = null,
)

@Serializable
internal data class AdLoadApiResponse(
    @SerialName("ad_id") val adId: String = "",
    @SerialName("ad_inserted") val adInserted: Boolean = false,
    @SerialName("ad_unit_id") val adUnitId: String = "",
    val rewarded: Boolean = false,
    val destination: String = "appstore",
    @SerialName("rendered_format") val renderedFormat: String? = null,
    @SerialName("tracking_url") val trackingUrl: String? = null,
    // Server-rendered HTML creative. When present (non-blank) it is rendered
    // full-screen in a WebView — the imperative interstitial's sole creative.
    @SerialName("rendered_html") val renderedHtml: String? = null,
)

@Serializable
internal data class RewardedInitRequestBody(
    @SerialName("ad_unit_id") val adUnitId: String,
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("min_play_threshold") val minPlayThreshold: Int? = null,
)

@Serializable
internal data class RewardedInitApiResponse(
    @SerialName("serve_id") val serveId: String = "",
    @SerialName("iframe_url") val iframeUrl: String = "",
    @SerialName("ad_id") val adId: String = "",
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
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
