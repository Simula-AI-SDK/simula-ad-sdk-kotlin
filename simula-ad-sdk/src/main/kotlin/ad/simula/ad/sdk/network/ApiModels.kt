package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.model.AdBehavior
import ad.simula.ad.sdk.model.CloseBehavior
import ad.simula.ad.sdk.model.CloseCountdownUi
import ad.simula.ad.sdk.model.CloseMotion
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.CloseSize
import ad.simula.ad.sdk.model.StoreOpen
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
)

@Serializable
internal data class AdLoadApiResponse(
    @SerialName("ad_id") val adId: String = "",
    @SerialName("ad_inserted") val adInserted: Boolean = false,
    @SerialName("ad_unit_id") val adUnitId: String = "",
    val rewarded: Boolean = false,
    val destination: String = "appstore",
    @SerialName("rendered_format") val renderedFormat: String? = null,
    @SerialName("rendered_assets") val renderedAssets: List<String> = emptyList(),
    @SerialName("tracking_url") val trackingUrl: String? = null,
    // Null when the payload omits `ad_behavior` — the renderer falls back to today's defaults.
    @SerialName("ad_behavior") val adBehavior: ApiAdBehavior? = null,
)

// ── Ad behavior (server-driven A/B render config) ─────────────────────────────

@Serializable
internal data class ApiAdBehavior(
    val close: ApiCloseBehavior? = null,
    @SerialName("store_open") val storeOpen: String? = null,
)

@Serializable
internal data class ApiCloseBehavior(
    @SerialName("delay_seconds") val delaySeconds: Int = 0,
    @SerialName("countdown_ui") val countdownUi: String? = null,
    val position: String? = null,
    val size: String? = null,
    val motion: String? = null,
)

/** Maps the wire DTO to the domain model, normalizing enum strings. A null DTO (absent
 * `ad_behavior`) stays null so callers can preserve today's literal behavior. */
internal fun ApiAdBehavior?.toDomain(): AdBehavior? {
    if (this == null) return null
    return AdBehavior(
        close = close.toDomain(),
        storeOpen = StoreOpen.from(storeOpen),
    )
}

internal fun ApiCloseBehavior?.toDomain(): CloseBehavior {
    if (this == null) return CloseBehavior()
    return CloseBehavior(
        delaySeconds = delaySeconds.coerceAtLeast(0),
        countdownUi = CloseCountdownUi.from(countdownUi),
        position = ClosePosition.from(position),
        size = CloseSize.from(size),
        motion = CloseMotion.from(motion),
    )
}
