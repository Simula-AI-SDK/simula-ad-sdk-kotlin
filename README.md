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
- Kotlin 1.9+

## Installation

### Kotlin DSL (build.gradle.kts)

```kotlin
dependencies {
    implementation("com.simula.ad:simula-ad-sdk:1.0.0")
}
```

### Groovy DSL (build.gradle)

```groovy
dependencies {
    implementation 'com.simula.ad:simula-ad-sdk:1.0.0'
}
```

> The SDK declares the `INTERNET` permission in its own manifest, which merges automatically with your app.

## Quick Start

### 1. Provider Setup

Wrap your app or screen content with `SimulaProvider`:

```kotlin
import com.simula.ad.sdk.provider.SimulaProvider

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
import com.simula.ad.sdk.minigame.MiniGameMenu

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
        messages = messages
    )
}
```

### 3. Game Button

```kotlin
import com.simula.ad.sdk.minigame.MiniGameButton

MiniGameButton(
    text = "Play a Game",
    showPulsate = true,
    showBadge = true,
    onClick = { showGames = true }
)
```

### 4. Invitation Banner

```kotlin
import com.simula.ad.sdk.minigame.MiniGameInvitation

MiniGameInvitation(
    charImage = "https://example.com/avatar.png",
    isOpen = showInvitation,
    onClick = { showGames = true },
    onClose = { showInvitation = false }
)
```

### 5. Full-Screen Interstitial

```kotlin
import com.simula.ad.sdk.minigame.MiniGameInterstitial

MiniGameInterstitial(
    charImage = "https://example.com/avatar.png",
    isOpen = showInterstitial,
    onClick = { showGames = true },
    onClose = { showInterstitial = false }
)
```

### 6. Grouped Kit (Alternative)

```kotlin
import com.simula.ad.sdk.minigame.MiniGameInviteKit

MiniGameInviteKit.Button(onClick = { showGames = true })
MiniGameInviteKit.Invitation(charImage = "...", isOpen = true, onClick = { })
MiniGameInviteKit.Interstitial(charImage = "...", isOpen = true, onClick = { })
```

## Privacy & Compliance

- [Google Play Data Safety Guide](docs/GOOGLE_PLAY_DATA_SAFETY.md)

## Documentation

For complete documentation including all props, theming options, and advanced usage, visit:

[Full Documentation](https://simula-ad.notion.site/Simula-x-Saylo-Minigame-SDK-2f4af70f6f0d804e805dcb2726f29079)

## Support

- Email: admin@simula.ad
- Website: [simula.ad](https://simula.ad)

## License

MIT
