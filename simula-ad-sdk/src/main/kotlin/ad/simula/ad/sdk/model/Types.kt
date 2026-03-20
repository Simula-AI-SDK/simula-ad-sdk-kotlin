package ad.simula.ad.sdk.model

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ── Core Types ──────────────────────────────────────────────────────────────

data class Message(
    val role: String,
    val content: String,
)

internal data class AdData(
    val id: String,
    val format: String,
    val iframeUrl: String? = null,
    val html: String? = null,
)

internal data class GameData(
    val id: String,
    val name: String,
    val iconUrl: String,
    val description: String,
    val iconFallback: String? = null,
    val gifCover: String? = null,
)

// ── SimulaProvider Types ────────────────────────────────────────────────────

internal data class SimulaContextValue(
    val apiKey: String,
    val devMode: Boolean,
    val sessionId: String?,
    val hasPrivacyConsent: Boolean,
    val getCachedAd: (slot: String, position: Int) -> AdData?,
    val cacheAd: (slot: String, position: Int, ad: AdData) -> Unit,
    val getCachedHeight: (slot: String, position: Int) -> Float?,
    val cacheHeight: (slot: String, position: Int, height: Float) -> Unit,
    val hasNoFill: (slot: String, position: Int) -> Boolean,
    val markNoFill: (slot: String, position: Int) -> Unit,
)

// ── MiniGameMenu Types ──────────────────────────────────────────────────────

data class MiniGameTheme(
    val backgroundColor: String? = null,
    val headerColor: String? = null,
    val borderColor: String? = null,
    val titleFont: String? = null,
    val secondaryFont: String? = null,
    val titleFontColor: String? = null,
    val secondaryFontColor: String? = null,
    val iconCornerRadius: Int? = null,
    val accentColor: String? = null,
    val playableHeight: Any? = null, // Number (px) or String with % or null (fullscreen)
    val playableBorderColor: String? = null,
)

// ── MiniGameInvitation Types ────────────────────────────────────────────────

enum class MiniGameInvitationAnimation {
    AUTO,
    SLIDE_DOWN,
    SLIDE_UP,
    FADE_IN,
    NONE;

    companion object {
        fun fromString(value: String): MiniGameInvitationAnimation {
            return when (value.lowercase()) {
                "auto" -> AUTO
                "slidedown", "slide_down" -> SLIDE_DOWN
                "slideup", "slide_up" -> SLIDE_UP
                "fadein", "fade_in" -> FADE_IN
                "none" -> NONE
                else -> SLIDE_DOWN
            }
        }
    }
}

data class MiniGameInvitationTheme(
    val cornerRadius: Int? = null,
    val backgroundColor: String? = null,
    val textColor: String? = null,
    val titleTextColor: String? = null,
    val subTextColor: String? = null,
    val ctaTextColor: String? = null,
    val ctaColor: String? = null,
    val charImageCornerRadius: Int? = null,
    val charImageAnchor: String? = null, // "left" or "right"
    val borderWidth: Int? = null,
    val borderColor: String? = null,
    val fontFamily: String? = null,
    val fontSize: Int? = null,
)

// ── MiniGameButton Types ────────────────────────────────────────────────────

data class MiniGameButtonTheme(
    val cornerRadius: Int? = null,
    val backgroundColor: String? = null,
    val textColor: String? = null,
    val fontSize: Int? = null,
    val fontFamily: String? = null,
    val padding: Any? = null, // String (e.g. "10px 20px") or Number (px)
    val borderWidth: Int? = null,
    val borderColor: String? = null,
    val pulsateColor: String? = null,
    val badgeColor: String? = null,
)

// ── MiniGameInterstitial Types ──────────────────────────────────────────────

data class MiniGameInterstitialTheme(
    val ctaCornerRadius: Int? = null,
    val characterSize: Int? = null,
    val titleTextColor: String? = null,
    val titleFontSize: Int? = null,
    val ctaTextColor: String? = null,
    val ctaFontSize: Int? = null,
    val ctaColor: String? = null,
    val fontFamily: String? = null,
)
