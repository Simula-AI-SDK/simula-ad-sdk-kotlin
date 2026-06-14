package ad.simula.ad.sdk.model

import ad.simula.ad.sdk.util.ColorUtil
import ad.simula.ad.sdk.util.FontUtil
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * Pre-resolved theme values.
 *
 * Color/font strings are parsed once per theme identity (via `remember(theme)`)
 * instead of on every recomposition — important for the menu carousel and the
 * button's infinite pulsate/ping animations, which recompose continuously.
 * Mirrors the Swift SDK's `resolved*` theme properties.
 */

// ── MiniGameMenu ─────────────────────────────────────────────────────────────

internal data class ResolvedMenuTheme(
    val titleFont: FontFamily,
    val secondaryFont: FontFamily,
    val titleFontColor: Color,
    val secondaryFontColor: Color,
    val borderColor: Color,
    val backgroundColor: Color,
)

internal fun MiniGameTheme.resolve(): ResolvedMenuTheme = ResolvedMenuTheme(
    titleFont = FontUtil.parseFont(titleFont ?: Defaults.MiniGameMenuTheme.TITLE_FONT),
    secondaryFont = FontUtil.parseFont(secondaryFont ?: Defaults.MiniGameMenuTheme.SECONDARY_FONT),
    titleFontColor = ColorUtil.parseColor(titleFontColor ?: Defaults.MiniGameMenuTheme.TITLE_FONT_COLOR),
    secondaryFontColor = ColorUtil.parseColor(secondaryFontColor ?: Defaults.MiniGameMenuTheme.SECONDARY_FONT_COLOR),
    borderColor = ColorUtil.parseColor(borderColor ?: Defaults.MiniGameMenuTheme.BORDER_COLOR),
    backgroundColor = if (backgroundColor != null) ColorUtil.parseColor(backgroundColor) else Color(0xFF0B0B0F),
)

// ── MiniGameInvitation ───────────────────────────────────────────────────────

internal data class ResolvedInvitationTheme(
    val cornerRadius: Int,
    val bgColor: Color,
    val titleTextColor: Color,
    val subTextColor: Color,
    val ctaTextColor: Color,
    val closeButtonColor: Color,
    val ctaColor: Color,
    val charImageCornerRadius: Int,
    val charImageAnchor: String,
    val borderWidth: Int,
    val borderColor: Color,
    val fontFamily: FontFamily,
    val fontSize: Int,
)

internal fun MiniGameInvitationTheme.resolve(): ResolvedInvitationTheme {
    val fallbackText = textColor ?: Defaults.MiniGameInvitationDefaults.TEXT_COLOR
    return ResolvedInvitationTheme(
        cornerRadius = cornerRadius ?: Defaults.MiniGameInvitationDefaults.CORNER_RADIUS,
        bgColor = ColorUtil.parseColor(backgroundColor ?: Defaults.MiniGameInvitationDefaults.BACKGROUND_COLOR),
        titleTextColor = ColorUtil.parseColor(titleTextColor ?: fallbackText),
        subTextColor = ColorUtil.parseColor(subTextColor ?: fallbackText),
        ctaTextColor = ColorUtil.parseColor(ctaTextColor ?: fallbackText),
        closeButtonColor = ColorUtil.parseColor(fallbackText),
        ctaColor = ColorUtil.parseColor(ctaColor ?: Defaults.MiniGameInvitationDefaults.CTA_COLOR),
        charImageCornerRadius = charImageCornerRadius ?: Defaults.MiniGameInvitationDefaults.CHAR_IMAGE_CORNER_RADIUS,
        charImageAnchor = charImageAnchor ?: Defaults.MiniGameInvitationDefaults.CHAR_IMAGE_ANCHOR,
        borderWidth = borderWidth ?: Defaults.MiniGameInvitationDefaults.BORDER_WIDTH,
        borderColor = ColorUtil.parseColor(borderColor ?: Defaults.MiniGameInvitationDefaults.BORDER_COLOR),
        fontFamily = FontUtil.parseFont(fontFamily),
        fontSize = fontSize ?: Defaults.MiniGameInvitationDefaults.FONT_SIZE,
    )
}

// ── MiniGameInterstitial ─────────────────────────────────────────────────────

internal data class ResolvedInterstitialTheme(
    val ctaCornerRadius: Int,
    val characterSize: Int,
    val titleTextColor: Color,
    val titleFontSize: Int,
    val ctaTextColor: Color,
    val ctaFontSize: Int,
    val fontFamily: FontFamily,
    val ctaColor: Color,
)

internal fun MiniGameInterstitialTheme.resolve(): ResolvedInterstitialTheme = ResolvedInterstitialTheme(
    ctaCornerRadius = ctaCornerRadius ?: Defaults.MiniGameInterstitialDefaults.CTA_CORNER_RADIUS,
    characterSize = characterSize ?: Defaults.MiniGameInterstitialDefaults.CHARACTER_SIZE,
    titleTextColor = ColorUtil.parseColor(titleTextColor ?: Defaults.MiniGameInterstitialDefaults.TITLE_TEXT_COLOR),
    titleFontSize = titleFontSize ?: Defaults.MiniGameInterstitialDefaults.TITLE_FONT_SIZE,
    ctaTextColor = ColorUtil.parseColor(ctaTextColor ?: Defaults.MiniGameInterstitialDefaults.CTA_TEXT_COLOR),
    ctaFontSize = ctaFontSize ?: Defaults.MiniGameInterstitialDefaults.CTA_FONT_SIZE,
    fontFamily = FontUtil.parseFont(fontFamily),
    ctaColor = ColorUtil.parseColor(ctaColor ?: Defaults.MiniGameInterstitialDefaults.CTA_COLOR),
)

// ── MiniGameButton ───────────────────────────────────────────────────────────

internal data class ResolvedButtonTheme(
    val cornerRadius: Int,
    val bgColor: Color,
    val textColor: Color,
    val fontSize: Int,
    val fontFamily: FontFamily,
    val borderWidth: Int,
    val borderColor: Color,
    val pulsateColor: Color,
    val badgeColor: Color,
)

internal fun MiniGameButtonTheme.resolve(): ResolvedButtonTheme {
    val bg = ColorUtil.parseColor(backgroundColor ?: Defaults.MiniGameButtonDefaults.BACKGROUND_COLOR)
    return ResolvedButtonTheme(
        cornerRadius = cornerRadius ?: Defaults.MiniGameButtonDefaults.CORNER_RADIUS,
        bgColor = bg,
        textColor = ColorUtil.parseColor(textColor ?: Defaults.MiniGameButtonDefaults.TEXT_COLOR),
        fontSize = fontSize ?: Defaults.MiniGameButtonDefaults.FONT_SIZE,
        fontFamily = FontUtil.parseFont(fontFamily),
        borderWidth = borderWidth ?: Defaults.MiniGameButtonDefaults.BORDER_WIDTH,
        borderColor = ColorUtil.parseColor(borderColor ?: Defaults.MiniGameButtonDefaults.BORDER_COLOR),
        pulsateColor = if (!pulsateColor.isNullOrBlank()) ColorUtil.parseColor(pulsateColor) else bg,
        badgeColor = ColorUtil.parseColor(badgeColor ?: Defaults.MiniGameButtonDefaults.BADGE_COLOR),
    )
}

// ── CharacterSelector ──────────────────────────────────────────────────────────

internal data class ResolvedCharacterSelectorTheme(
    val backgroundColor: Color,
    val titleFontColor: Color,
    val secondaryFontColor: Color,
    val accentColor: Color,
    val ctaFontColor: Color,
    val cardBackgroundColor: Color,
    val cardBorderColor: Color,
    val cardCornerRadius: Int,
    val fontFamily: FontFamily,
)

internal fun CharacterSelectorTheme.resolve(): ResolvedCharacterSelectorTheme {
    val d = Defaults.CharacterSelectorDefaults
    return ResolvedCharacterSelectorTheme(
        backgroundColor = ColorUtil.parseColor(backgroundColor ?: d.BACKGROUND_COLOR),
        titleFontColor = ColorUtil.parseColor(titleFontColor ?: d.TITLE_FONT_COLOR),
        secondaryFontColor = ColorUtil.parseColor(secondaryFontColor ?: d.SECONDARY_FONT_COLOR),
        accentColor = ColorUtil.parseColor(accentColor ?: d.ACCENT_COLOR),
        ctaFontColor = ColorUtil.parseColor(ctaFontColor ?: d.CTA_FONT_COLOR),
        cardBackgroundColor = ColorUtil.parseColor(cardBackgroundColor ?: d.CARD_BACKGROUND_COLOR),
        cardBorderColor = ColorUtil.parseColor(cardBorderColor ?: d.CARD_BORDER_COLOR),
        cardCornerRadius = cardCornerRadius ?: d.CARD_CORNER_RADIUS,
        fontFamily = FontUtil.parseFont(fontFamily ?: d.FONT_FAMILY),
    )
}
