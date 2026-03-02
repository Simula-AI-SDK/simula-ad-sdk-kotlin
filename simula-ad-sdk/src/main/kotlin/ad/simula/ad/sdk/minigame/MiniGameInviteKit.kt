package ad.simula.ad.sdk.minigame

import ad.simula.ad.sdk.model.MiniGameInvitationAnimation

/**
 * Grouped access to mini game invite components.
 * Equivalent to React's MiniGameInviteKit export from index.ts:
 *
 * ```typescript
 * export const MiniGameInviteKit = {
 *   Invitation: MiniGameInvitation,
 *   Button: MiniGameButton,
 *   Interstitial: MiniGameInterstitial,
 * } as const;
 * ```
 *
 * Usage in Kotlin:
 * ```kotlin
 * MiniGameInviteKit.Invitation(...)
 * MiniGameInviteKit.Button(...)
 * MiniGameInviteKit.Interstitial(...)
 * ```
 */
object MiniGameInviteKit {
    /**
     * Banner invitation to play a game.
     * Use [MiniGameInvitation] directly as a composable.
     */
    @androidx.compose.runtime.Composable
    fun Invitation(
        titleText: String = "Want to play a game?",
        subText: String = "Take a break and challenge yourself!",
        ctaText: String = "Play a Game",
        charImage: String,
        animation: MiniGameInvitationAnimation = MiniGameInvitationAnimation.AUTO,
        theme: ad.simula.ad.sdk.model.MiniGameInvitationTheme = ad.simula.ad.sdk.model.MiniGameInvitationTheme(),
        isOpen: Boolean = false,
        autoCloseDuration: Long? = null,
        width: Any? = null,
        top: Any? = 0.05,
        onClick: () -> Unit,
        onClose: (() -> Unit)? = null,
    ) = MiniGameInvitation(titleText, subText, ctaText, charImage, animation, theme, isOpen, autoCloseDuration, width, top, onClick, onClose)

    /**
     * Trigger button to open the game menu.
     * Use [MiniGameButton] directly as a composable.
     */
    @androidx.compose.runtime.Composable
    fun Button(
        text: String? = null,
        showPulsate: Boolean = false,
        showBadge: Boolean = false,
        theme: ad.simula.ad.sdk.model.MiniGameButtonTheme = ad.simula.ad.sdk.model.MiniGameButtonTheme(),
        width: Any? = null,
        onClick: () -> Unit,
    ) = MiniGameButton(text, showPulsate, showBadge, theme, width, onClick)

    /**
     * Full-screen interstitial invitation.
     * Use [MiniGameInterstitial] directly as a composable.
     */
    @androidx.compose.runtime.Composable
    fun Interstitial(
        charImage: String,
        invitationText: String = "Want to play a game?",
        ctaText: String = "Play a Game",
        backgroundImage: String? = null,
        theme: ad.simula.ad.sdk.model.MiniGameInterstitialTheme = ad.simula.ad.sdk.model.MiniGameInterstitialTheme(),
        isOpen: Boolean,
        onClick: () -> Unit,
        onClose: (() -> Unit)? = null,
    ) = MiniGameInterstitial(charImage, invitationText, ctaText, backgroundImage, theme, isOpen, onClick, onClose)
}
