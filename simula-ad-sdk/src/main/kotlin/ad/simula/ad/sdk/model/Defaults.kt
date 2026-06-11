package ad.simula.ad.sdk.model

/**
 * Default theme values matching the React SDK exactly.
 */
object Defaults {

    // ── MiniGameMenu Defaults ───────────────────────────────────────────────

    object MiniGameMenuTheme {
        const val TITLE_FONT = "Inter, system-ui, sans-serif"
        const val SECONDARY_FONT = "Inter, system-ui, sans-serif"
        const val TITLE_FONT_COLOR = "#ffffff"
        const val SECONDARY_FONT_COLOR = "rgba(255, 255, 255, 0.75)"
        const val ICON_CORNER_RADIUS = 18
        const val BORDER_COLOR = "rgba(255, 255, 255, 0.06)"
        const val ACCENT_COLOR = "#3B82F6"
    }

    // ── MiniGameInvitation Defaults ─────────────────────────────────────────

    object MiniGameInvitationDefaults {
        const val CORNER_RADIUS = 16
        const val BACKGROUND_COLOR = "rgba(0, 0, 0, 0.65)"
        const val TEXT_COLOR = "#FFFFFF"
        const val TITLE_TEXT_COLOR = "#FFFFFF"
        const val SUB_TEXT_COLOR = "#FFFFFF"
        const val CTA_TEXT_COLOR = "#FFFFFF"
        const val CTA_COLOR = "#3B82F6"
        const val CHAR_IMAGE_CORNER_RADIUS = 12
        const val CHAR_IMAGE_ANCHOR = "left"
        const val BORDER_WIDTH = 1
        const val BORDER_COLOR = "rgba(255, 255, 255, 0.1)"
        const val FONT_FAMILY = "Inter, system-ui, sans-serif"
        const val FONT_SIZE = 16

        const val ANIMATION_DURATION_MS = 300
    }

    // ── MiniGameButton Defaults ─────────────────────────────────────────────

    object MiniGameButtonDefaults {
        const val CORNER_RADIUS = 8
        const val BACKGROUND_COLOR = "#3B82F6"
        const val TEXT_COLOR = "#FFFFFF"
        const val FONT_SIZE = 14
        const val FONT_FAMILY = "Inter, system-ui, sans-serif"
        const val PADDING_VERTICAL_DP = 10
        const val PADDING_HORIZONTAL_DP = 20
        const val BORDER_WIDTH = 0
        const val BORDER_COLOR = "transparent"
        const val BADGE_COLOR = "#EF4444"
    }

    // ── MiniGameInterstitial Defaults ───────────────────────────────────────

    object MiniGameInterstitialDefaults {
        const val CTA_CORNER_RADIUS = 16
        const val CHARACTER_SIZE = 120
        const val TITLE_TEXT_COLOR = "#FFFFFF"
        const val TITLE_FONT_SIZE = 24
        const val CTA_TEXT_COLOR = "#FFFFFF"
        const val CTA_FONT_SIZE = 16
        const val CTA_COLOR = "#3B82F6"
        const val FONT_FAMILY = "Inter, system-ui, sans-serif"
    }

    // ── CharacterPicker Defaults ────────────────────────────────────────────
    // Values mirror the reference "Select Your Game Partner" HTML verbatim.

    object CharacterPickerDefaults {
        const val BACKGROUND_COLOR = "#000000"
        const val TITLE_COLOR = "#ffffff"
        const val TITLE_FONT_SIZE = 26
        const val CARD_BACKGROUND_COLOR = "#14161a"
        const val CARD_BORDER_COLOR = "#343a42"
        const val CARD_CORNER_RADIUS = 18
        const val SELECTED_COLOR = "#3d9a66"
        const val NAME_COLOR = "#ffffff"
        const val LAUNCH_TEXT_COLOR = "#ffffff"
        const val LAUNCH_DISABLED_COLOR = "#3a3a3a"
        const val LAUNCH_CORNER_RADIUS = 14
        const val FONT_FAMILY = "-apple-system, system-ui, Roboto, sans-serif"
    }
}
