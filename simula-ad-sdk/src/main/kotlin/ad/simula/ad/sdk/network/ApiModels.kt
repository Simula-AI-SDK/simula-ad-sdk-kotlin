package ad.simula.ad.sdk.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionResponse(
    @SerialName("sessionId") val sessionId: String? = null,
)

@Serializable
data class CatalogApiResponse(
    @SerialName("menu_id") val menuId: String? = null,
    val catalog: kotlinx.serialization.json.JsonElement? = null,
    val data: kotlinx.serialization.json.JsonArray? = null,
)

@Serializable
data class CatalogGameItem(
    val id: String,
    val name: String,
    val icon: String,
    val description: String? = null,
    val iconFallback: String? = null,
)

@Serializable
data class InitMinigameRequestBody(
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
data class MessageBody(
    val role: String,
    val content: String,
)

@Serializable
data class MinigameApiResponse(
    val adType: String? = null,
    val adInserted: Boolean = false,
    val adResponse: AdResponseBody? = null,
)

@Serializable
data class AdResponseBody(
    @SerialName("ad_id") val adId: String? = null,
    @SerialName("iframe_url") val iframeUrl: String? = null,
)

@Serializable
data class MenuGameClickBody(
    @SerialName("menu_id") val menuId: String,
    @SerialName("game_name") val gameName: String,
)
