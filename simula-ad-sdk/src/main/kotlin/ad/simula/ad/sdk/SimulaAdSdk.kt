@file:JvmName("SimulaAdSdk")

package ad.simula.ad.sdk

/**
 * Simula Ad SDK for Android (Jetpack Compose).
 *
 * Public API surface — re-exports all components and types for easy access.
 *
 * ## Quick Start
 *
 * ```kotlin
 * // 1. Wrap your app with SimulaProvider
 * SimulaProvider(apiKey = "your-api-key") {
 *     // Your app content
 * }
 *
 * // 2. Use mini game components
 * MiniGameButton(onClick = { isMenuOpen = true })
 * MiniGameMenu(isOpen = isMenuOpen, onClose = { isMenuOpen = false }, ...)
 * MiniGameInvitation(charImage = "...", onClick = { ... })
 * MiniGameInterstitial(charImage = "...", isOpen = ..., onClick = { ... })
 *
 * // 3. Or use the grouped kit
 * MiniGameInviteKit.Button(onClick = { ... })
 * MiniGameInviteKit.Invitation(...)
 * MiniGameInviteKit.Interstitial(...)
 * ```
 */
object SimulaAdSdkInfo {
    const val VERSION = "1.0.0"
    const val SDK_NAME = "simula-ad-sdk-kotlin"
}
