# Simula MiniGame SDK for Android

A Kotlin SDK for integrating sponsored mini-games into AI chat Android applications using Jetpack Compose.

## Key Features

- Sponsored mini-games that users can play with AI characters
- Easy integration with existing Android Compose apps
- Privacy-first design with consent management
- Google Play Store compliant

## Requirements

- Android minSdk 24 (Android 7.0+)
- Jetpack Compose
- Kotlin 2.1+

## Installation

### Kotlin DSL (build.gradle.kts)

```kotlin
dependencies {
    implementation("ad.simula:ad-sdk:1.0.3")
}
```

### Groovy DSL (build.gradle)

```groovy
dependencies {
    implementation 'ad.simula:ad-sdk:1.0.3'
}
```

> The SDK declares the `INTERNET` permission in its own manifest, which merges automatically with your app.

## Quick Start

### 1. Provider Setup

Wrap your app or screen content with `SimulaProvider`:

```kotlin
import ad.simula.ad.sdk.provider.SimulaProvider

@Composable
fun App() {
    SimulaProvider(
        apiKey = "YOUR_API_KEY",
        hasPrivacyConsent = true
    ) {
        YourChatApp()
    }
}
```

### 2. MiniGame Menu

Add the `MiniGameMenu` component to your chat interface:

```kotlin
import ad.simula.ad.sdk.minigame.MiniGameMenu
import ad.simula.ad.sdk.model.MiniGameTheme

@Composable
fun ChatScreen() {
    var showGames by remember { mutableStateOf(false) }

    Button(onClick = { showGames = true }) {
        Text("Play Games")
    }

    MiniGameMenu(
        isOpen = showGames,
        onClose = { showGames = false },
        charName = "Luna",
        charID = "char_123",
        charImage = "https://example.com/avatar.png",
        messages = messages,
        theme = MiniGameTheme(
            accentColor = "#3B82F6",
            titleFont = "sans-serif",
            secondaryFont = "sans-serif",
        )
    )
}
```

## Optional Components

The following components are optional UI helpers for triggering the MiniGame Menu. Use any combination that fits your app, or use your own buttons/triggers instead.

### Game Button

```kotlin
import ad.simula.ad.sdk.minigame.MiniGameButton
import ad.simula.ad.sdk.model.MiniGameButtonTheme

MiniGameButton(
    text = "Play a Game",
    showPulsate = true,
    showBadge = true,
    theme = MiniGameButtonTheme(
        backgroundColor = "#3B82F6",
        textColor = "#FFFFFF",
        cornerRadius = 8,
    ),
    onClick = { showGames = true }
)
```

### Invitation Banner

```kotlin
import ad.simula.ad.sdk.minigame.MiniGameInvitation
import ad.simula.ad.sdk.model.MiniGameInvitationTheme
import ad.simula.ad.sdk.model.MiniGameInvitationAnimation

MiniGameInvitation(
    charImage = "https://example.com/avatar.png",
    titleText = "Want to play a game?",
    subText = "Take a break and challenge yourself!",
    ctaText = "Play a Game",
    animation = MiniGameInvitationAnimation.AUTO,
    isOpen = showInvitation,
    autoCloseDuration = 10000L, // auto-close after 10s (optional)
    theme = MiniGameInvitationTheme(
        backgroundColor = "rgba(0, 0, 0, 0.65)",
        titleTextColor = "#FFFFFF",
        subTextColor = "#FFFFFF",
        ctaTextColor = "#FFFFFF",
        ctaColor = "#3B82F6",
        fontFamily = "sans-serif",
    ),
    onClick = { showGames = true },
    onClose = { showInvitation = false }
)
```

### Full-Screen Interstitial

```kotlin
import ad.simula.ad.sdk.minigame.MiniGameInterstitial
import ad.simula.ad.sdk.model.MiniGameInterstitialTheme

MiniGameInterstitial(
    charImage = "https://example.com/avatar.png",
    invitationText = "Want to play a game?",
    ctaText = "Play a Game",
    backgroundImage = null, // uses bundled default background
    isOpen = showInterstitial,
    theme = MiniGameInterstitialTheme(
        titleTextColor = "#FFFFFF",
        ctaTextColor = "#FFFFFF",
        ctaColor = "#3B82F6",
        titleFontSize = 24,
        ctaFontSize = 16,
        fontFamily = "sans-serif",
    ),
    onClick = { showGames = true },
    onClose = { showInterstitial = false }
)
```

### Grouped Kit (Alternative)

```kotlin
import ad.simula.ad.sdk.minigame.MiniGameInviteKit

MiniGameInviteKit.Button(onClick = { showGames = true })
MiniGameInviteKit.Invitation(charImage = "...", isOpen = true, onClick = { })
MiniGameInviteKit.Interstitial(charImage = "...", isOpen = true, onClick = { })
```

## Theming Reference

### MiniGameInvitationTheme

| Property | Type | Default | Description |
|---|---|---|---|
| `cornerRadius` | `Int?` | `16` | Container border radius (dp) |
| `backgroundColor` | `String?` | `rgba(0, 0, 0, 0.65)` | Banner background color |
| `textColor` | `String?` | `#FFFFFF` | Fallback text color for all text |
| `titleTextColor` | `String?` | `#FFFFFF` | Title text color (overrides textColor) |
| `subTextColor` | `String?` | `#FFFFFF` | Subtitle text color (overrides textColor) |
| `ctaTextColor` | `String?` | `#FFFFFF` | CTA button text color (overrides textColor) |
| `ctaColor` | `String?` | `#3B82F6` | CTA button background color |
| `charImageCornerRadius` | `Int?` | `12` | Character image corner radius (dp) |
| `charImageAnchor` | `String?` | `left` | Character image side: `"left"` or `"right"` |
| `borderWidth` | `Int?` | `1` | Border width (dp) |
| `borderColor` | `String?` | `rgba(255, 255, 255, 0.1)` | Border color |
| `fontFamily` | `String?` | `sans-serif` | Font family (CSS string, mapped to Android fonts) |
| `fontSize` | `Int?` | `16` | Title font size (sp) |

### MiniGameInterstitialTheme

| Property | Type | Default | Description |
|---|---|---|---|
| `ctaCornerRadius` | `Int?` | `16` | CTA button border radius (dp) |
| `characterSize` | `Int?` | `120` | Character image size (dp, displayed as circle) |
| `titleTextColor` | `String?` | `#FFFFFF` | Invitation text color |
| `titleFontSize` | `Int?` | `24` | Invitation text font size (sp) |
| `ctaTextColor` | `String?` | `#FFFFFF` | CTA button text color |
| `ctaFontSize` | `Int?` | `16` | CTA button font size (sp) |
| `ctaColor` | `String?` | `#3B82F6` | CTA button background color |
| `fontFamily` | `String?` | `sans-serif` | Font family (CSS string, mapped to Android fonts) |

### MiniGameButtonTheme

| Property | Type | Default | Description |
|---|---|---|---|
| `cornerRadius` | `Int?` | `8` | Button border radius (dp) |
| `backgroundColor` | `String?` | `#3B82F6` | Button background color |
| `textColor` | `String?` | `#FFFFFF` | Text color |
| `fontSize` | `Int?` | `14` | Font size (sp) |
| `fontFamily` | `String?` | `sans-serif` | Font family (CSS string, mapped to Android fonts) |
| `padding` | `Any?` | `10dp x 20dp` | Padding (String or Number) |
| `borderWidth` | `Int?` | `0` | Border width (dp) |
| `borderColor` | `String?` | `transparent` | Border color |
| `pulsateColor` | `String?` | _(uses backgroundColor)_ | Pulsate glow color |
| `badgeColor` | `String?` | `#EF4444` | Notification badge color |

### MiniGameTheme (Menu)

| Property | Type | Default | Description |
|---|---|---|---|
| `backgroundColor` | `String?` | white | Menu background color |
| `headerColor` | `String?` | transparent | Header section background |
| `borderColor` | `String?` | `rgba(0, 0, 0, 0.08)` | Border/divider color |
| `titleFont` | `String?` | `sans-serif` | Title font family |
| `secondaryFont` | `String?` | `sans-serif` | Secondary text font family |
| `titleFontColor` | `String?` | `#1F2937` | Title text color |
| `secondaryFontColor` | `String?` | `#6B7280` | Secondary text color |
| `iconCornerRadius` | `Int?` | `8` | Game icon border radius (dp) |
| `accentColor` | `String?` | `#3B82F6` | Accent color for interactive elements |
| `playableHeight` | `Any?` | fullscreen | Game iframe height (Number for px, String with % for percentage) |
| `playableBorderColor` | `String?` | `#262626` | Bottom sheet border color |

### Font Family Mapping

The SDK accepts CSS font-family strings and maps them to Android `FontFamily` equivalents:

| CSS Value | Android FontFamily |
|---|---|
| `sans-serif`, `Inter`, `Roboto`, `Arial`, `system-ui` | `FontFamily.SansSerif` |
| `serif`, `Georgia`, `Times New Roman` | `FontFamily.Serif` |
| `monospace`, `SF Mono`, `Consolas`, `Courier New` | `FontFamily.Monospace` |
| `cursive` | `FontFamily.Cursive` |

### Color Format Support

All color properties accept these formats:

- Hex: `#RGB`, `#RRGGBB`, `#RRGGBBAA`
- CSS: `rgb(255, 0, 0)`, `rgba(255, 0, 0, 0.5)`
- Named: `transparent`

## Privacy & Compliance

- [Google Play Data Safety Guide](docs/GOOGLE_PLAY_DATA_SAFETY.md)

## Documentation

For complete documentation including advanced usage, visit:

[Full Documentation](https://simula-ad.notion.site/Simula-x-Saylo-Minigame-SDK-2f4af70f6f0d804e805dcb2726f29079)

## Support

- Email: admin@simula.ad
- Website: [simula.ad](https://simula.ad)

## License

MIT
