package ad.simula.ad.sdk.model

import ad.simula.ad.sdk.privacy.ConsentSnapshot
import ad.simula.ad.sdk.privacy.SimulaPrivacyConfig
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
    /** Resolved consent snapshot (IAB-read + explicit overrides). */
    val consent: ConsentSnapshot,
    /** Replace the privacy configuration at runtime (e.g. CMP refresh). */
    val updateConsent: (SimulaPrivacyConfig) -> Unit,
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

/** Hard cap on the server-driven close delay. The close button — and the system Back button,
 * which is blocked while the gate is active — stays locked until the delay elapses, so an
 * out-of-range value would otherwise trap the user. PRD arms are 0/3/5s; 15s leaves headroom. */
internal const val MAX_CLOSE_DELAY_SECONDS = 15

/**
 * Validates a server-supplied progress-bar color. Accepts an optional leading `#` followed by
 * exactly 6 hex digits; anything else (missing, wrong length, non-hex) falls back to white per
 * spec. Returned WITH a leading `#` so it drops straight into `ColorUtil.parseColor`.
 */
internal fun validatedHexColor(raw: String?, fallback: String = "#FFFFFF"): String {
    val body = (raw ?: return fallback).removePrefix("#")
    val isHex = body.length == 6 && body.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    return if (isHex) "#" + body.uppercase() else fallback
}

/** The close-button visual treatment (v2 replaces `countdown_ui`). Unknown/missing → HIDDEN
 * (safest: shows no affordance, so a malformed value never presents a false tap target). */
internal enum class CloseTreatment {
    HIDDEN, COUNTDOWN_CIRCLE, PROGRESS_BAR, REWARD_OR_CLOSE_LABEL;

    companion object {
        fun from(raw: String?): CloseTreatment = when (normalizeBehaviorToken(raw)) {
            "countdown_circle" -> COUNTDOWN_CIRCLE
            "progress_bar" -> PROGRESS_BAR
            "reward_or_close_label" -> REWARD_OR_CLOSE_LABEL
            else -> HIDDEN
        }
    }
}

/** Close button corner. v2 narrows this to three corners — `bottom_right` is excluded (it collides
 * with the install prompt and OS nav gestures). Unknown/missing/excluded → TOP_RIGHT. Reused
 * verbatim for the server-resolved store-prompt position. */
internal enum class ClosePosition {
    TOP_RIGHT, TOP_LEFT, BOTTOM_LEFT;

    companion object {
        fun from(raw: String?): ClosePosition = when (normalizeBehaviorToken(raw)) {
            "top_left" -> TOP_LEFT
            "bottom_left" -> BOTTOM_LEFT
            // top_right, plus excluded bottom_right / legacy bottom_corner, plus unknown → safe default.
            else -> TOP_RIGHT
        }
    }
}

/** How a CTA tap opens the store. Unknown → EXTERNAL. `sk_overlay`/`sk_store_product` map to the
 * native store (SKSTOREPRODUCT), which the Android router routes to the Play Store app. Retained
 * from v1; the v2 payload omits `store_open`, so it simply defaults. */
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

/** Ad format, used to pick `reward_or_close_label` copy. Unknown/missing → INTERSTITIAL. */
internal enum class AdUnitType {
    REWARDED, INTERSTITIAL;

    companion object {
        fun from(raw: String?): AdUnitType = when (normalizeBehaviorToken(raw)) {
            "rewarded" -> REWARDED
            else -> INTERSTITIAL
        }
    }
}

/** Which store the mid-ad prompt badge advertises. Unknown/missing → ANDROID. */
internal enum class StorePromptPlatform {
    IOS, ANDROID;

    companion object {
        fun from(raw: String?): StorePromptPlatform = when (normalizeBehaviorToken(raw)) {
            "ios" -> IOS
            else -> ANDROID
        }
    }
}

/** When the install overlay is presented. Unknown/missing → ON_CLICK. */
internal enum class OverlayTiming {
    DURING_PLAY, ON_CLICK, DELAYED;

    companion object {
        fun from(raw: String?): OverlayTiming = when (normalizeBehaviorToken(raw)) {
            "during_play" -> DURING_PLAY
            "delayed" -> DELAYED
            else -> ON_CLICK
        }
    }
}

/** Where the install overlay is pinned. Unknown/missing → BOTTOM. */
internal enum class OverlayPosition {
    BOTTOM, BOTTOM_RAISED;

    companion object {
        fun from(raw: String?): OverlayPosition = when (normalizeBehaviorToken(raw)) {
            "bottom_raised" -> BOTTOM_RAISED
            else -> BOTTOM
        }
    }
}

internal data class CloseBehavior(
    val delaySeconds: Int = 0,
    val treatment: CloseTreatment = CloseTreatment.HIDDEN,
    val position: ClosePosition = ClosePosition.TOP_RIGHT,
    /** Validated 6-digit hex (with leading `#`); tints the countdown_circle / progress_bar fill. */
    val progressBarColor: String = "#FFFFFF",
)

/** The creative descriptor (`creative` node). `adUnitType` drives format-aware close copy. */
internal data class Creative(
    val type: String = "",
    val bundleUrl: String? = null,
    val adUnitType: AdUnitType = AdUnitType.INTERSTITIAL,
)

/** Experiment-assignment metadata (`experiment` node), carried for telemetry only. */
internal data class Experiment(
    val experimentId: String? = null,
    val variantId: String? = null,
    val layer: String? = null,
)

/** Mid-ad store prompt (`store_prompt` node). `position` is resolved server-side (opposite the
 * close button) and rendered verbatim — the SDK never recomputes collisions. */
internal data class StorePrompt(
    val enabled: Boolean = false,
    val trigger: String = "midpoint",
    val position: ClosePosition = ClosePosition.TOP_LEFT,
    val platform: StorePromptPlatform = StorePromptPlatform.ANDROID,
)

/** Play Install Prompt (Android) / SKOverlay (iOS) config (`skoverlay` node): a native,
 * SDK-presented install banner, independent of the creative click handler. */
internal data class SkOverlayConfig(
    val enabled: Boolean = false,
    val timing: OverlayTiming = OverlayTiming.ON_CLICK,
    val delaySeconds: Int = 0,
    val position: OverlayPosition = OverlayPosition.BOTTOM,
    val dismissible: Boolean = true,
)

internal data class AdBehavior(
    val close: CloseBehavior = CloseBehavior(),
    val storeOpen: StoreOpen = StoreOpen.EXTERNAL,
    val storePrompt: StorePrompt? = null,
    val skoverlay: SkOverlayConfig? = null,
)
