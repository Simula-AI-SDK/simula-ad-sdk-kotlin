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
    val ensureSession: suspend () -> String?,
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

// ── Ad Behavior (server-driven A/B config) ──────────────────────────────────

/** Lowercases and normalizes hyphens to underscores so the tolerant enum factories accept
 * either wire spelling (`circular-progress` ≡ `circular_progress`). */
private fun normalizeBehaviorToken(raw: String?): String =
    (raw ?: "").lowercase().replace("-", "_")

/** How the close button's pre-tap delay is communicated. Non-rewarded only. Unknown → NONE. */
internal enum class CloseCountdownUi {
    NUMERIC_ALWAYS, CIRCULAR_PROGRESS, APPEARS_AT_NS, BAR, NONE;

    companion object {
        fun from(raw: String?): CloseCountdownUi = when (normalizeBehaviorToken(raw)) {
            "numeric_always" -> NUMERIC_ALWAYS
            "circular_progress" -> CIRCULAR_PROGRESS
            "appears_at_ns" -> APPEARS_AT_NS
            "bar" -> BAR
            else -> NONE
        }
    }
}

/** Close button corner. Unknown → TOP_RIGHT. Legacy `bottom_corner` → BOTTOM_RIGHT. */
internal enum class ClosePosition {
    TOP_RIGHT, TOP_LEFT, BOTTOM_LEFT, BOTTOM_RIGHT;

    companion object {
        fun from(raw: String?): ClosePosition = when (normalizeBehaviorToken(raw)) {
            "top_left" -> TOP_LEFT
            "bottom_left" -> BOTTOM_LEFT
            "bottom_right", "bottom_corner" -> BOTTOM_RIGHT
            else -> TOP_RIGHT
        }
    }
}

/** Close button size (PRD: small 16 / standard 24 / large 32). Unknown → STANDARD. */
internal enum class CloseSize(val glyphSp: Int) {
    SMALL(16), STANDARD(24), LARGE(32);

    /** Tappable box diameter in dp (glyph + 20). */
    val boxDp: Int get() = glyphSp + 20

    companion object {
        fun from(raw: String?): CloseSize = when (normalizeBehaviorToken(raw)) {
            "small" -> SMALL
            "large" -> LARGE
            else -> STANDARD
        }
    }
}

/** Close button motion. Only STATIC is rendered this release (non-static not shipping). Unknown → STATIC. */
internal enum class CloseMotion {
    STATIC, REPOSITION_ON_TAP, DRIFT;

    companion object {
        fun from(raw: String?): CloseMotion = when (normalizeBehaviorToken(raw)) {
            "reposition_on_tap" -> REPOSITION_ON_TAP
            "drift" -> DRIFT
            else -> STATIC
        }
    }
}

/** How a CTA tap opens the store (PRD Section 6). Unknown → EXTERNAL. `sk_overlay`/`sk_store_product`
 * map to the native store (SKSTOREPRODUCT), which the Android router routes to the Play Store app. */
internal enum class StoreOpen {
    EXTERNAL, SKSTOREPRODUCT, INLINE_INSTALL;

    companion object {
        fun from(raw: String?): StoreOpen = when (normalizeBehaviorToken(raw)) {
            "inline_install" -> INLINE_INSTALL
            "skstoreproduct", "sk_store_product", "sk_overlay" -> SKSTOREPRODUCT
            else -> EXTERNAL
        }
    }
}

internal data class CloseBehavior(
    val delaySeconds: Int = 0,
    val countdownUi: CloseCountdownUi = CloseCountdownUi.NONE,
    val position: ClosePosition = ClosePosition.TOP_RIGHT,
    val size: CloseSize = CloseSize.STANDARD,
    val motion: CloseMotion = CloseMotion.STATIC,
)

internal data class AdBehavior(
    val close: CloseBehavior = CloseBehavior(),
    val storeOpen: StoreOpen = StoreOpen.EXTERNAL,
)
