# Simula Ad SDK for Android

AI-powered native ads, interstitial ads, and rewarded ads for Android apps using Jetpack Compose.

Simula delivers ads that feel native to AI chat and character-driven applications. The SDK handles ad rendering, contextual targeting, privacy compliance, and server-side reward verification out of the box.

## Ad Formats

| Format | Description |
|---|---|
| **NativeAdSlot** | Inline ad card that fits naturally into Compose layouts |
| **Interstitial Ad** | Full-screen ad with preload/show lifecycle |
| **Rewarded Ad** | Play-to-earn ad with server-side reward verification |

## Requirements

- Android API 24+ (Android 7.0)
- Jetpack Compose
- Kotlin 1.9+

## Getting Started

Full integration guides, API references, and examples are available at:

**[docs.simula.ad/kotlin-sdk](https://docs.simula.ad/kotlin-sdk/quick-start)**

- [Quick Start](https://docs.simula.ad/kotlin-sdk/quick-start) -- installation, provider setup, privacy, and error handling
- [NativeAdSlot](https://docs.simula.ad/kotlin-sdk/native-ad-slot) -- inline ad composable
- [Interstitial Ad](https://docs.simula.ad/kotlin-sdk/interstitial-ad) -- full-screen ad
- [Rewarded Ad](https://docs.simula.ad/kotlin-sdk/rewarded-ad) -- rewarded ad with server-side verification

## Video behavior

Videos start unmuted, including legacy plans; unavailable audio focus falls back to muted playback.
Contract-2 stitched clips emit their own start, 50%, and completion events with clip-local position,
duration, and muted/unmuted watch time. Playback uses one cached asset and one player.

For rewarded contract-2 units, an admitted primary video that fails before its gate can still earn
at the final rendered end screen's gate. Delivery and verification wait until the whole unit closes.
If neither the primary nor a rendered end screen reaches a gate, failures do not create a reward.
An explicit `verified: false` permanently reconciles verification; malformed responses remain retryable.

Android video downloads honor the host's process-wide `CookieHandler`, including its cookie policy
for each validated redirect destination. The SDK never replaces the handler or forwards cookie
headers itself. Public-address checks, redirect limits, and download budgets still apply.
The impression GET remains cookie-isolated: when a global handler is installed, it is skipped with
`impression:cookie_isolation_unavailable` because `HttpURLConnection` cannot disable that handler
for an individual request.
Impression GETs reuse the browser User-Agent when already captured from an SDK WebView; otherwise
they use the platform default. Measurement never creates a WebView solely to obtain its agent.

## Publisher Metadata

Attach non-sensitive string metadata to ad loads for reporting and attribution:

```kotlin
val interstitial = SimulaInterstitialAd("ad-unit-id").apply {
    setMetadata(mapOf("placement" to "home", "experiment" to "hero_v2"))
}
interstitial.load()

NativeAdSlot(
    adUnitId = "native-unit-id",
    metadata = mapOf("placement" to "feed"),
)

val preloadedAdId = SimulaAds.preloadNativeAd(
    adUnitId = "native-unit-id",
)

NativeAdSlot(
    adUnitId = "native-unit-id",
    preloadedAdId = preloadedAdId,
    metadata = mapOf("placement" to "feed"),
)
```

`SimulaRewardedAd` supports the same `setMetadata` overloads. Metadata is snapshotted per impression;
changing metadata later does not rewrite one already in flight. A normal or preload-fallback native
slot sends its component snapshot on `/load`. Native preloads accept no metadata; when consumed, the
mounting `NativeAdSlot` sends its snapshot on the durable `/seen` beacon instead, and the native cache
preserves that pending snapshot across remounts.

Metadata is limited to 10 entries. Keys must be non-empty, at most 64 Unicode code points, must not
start with `$`, and must not contain `.`. Values are limited to 256 Unicode code points. Invalid or
over-limit entries are ignored safely and reported in Logcat and SDK telemetry. Do not include PII,
credentials, tokens, or other secrets.

## Video Contract

Imperative interstitial and rewarded video use the stitched-asset video contract 2. A video is Ready
only after its bounded local download completes; playback never streams the remote URL. See
[`docs/VIDEO_CONTRACT_2.md`](docs/VIDEO_CONTRACT_2.md) for the wire, reward, click, impression, cache,
telemetry, and progress-bar contract.

## Staging Environment

Staging is restricted to exact `X.Y.Z-dev.N` SDK artifacts and is selected directly by an explicit
host manifest opt-in:

```xml
<application>
    <meta-data
        android:name="SimulaStagingEnvironmentEnabled"
        android:value="true" />
</application>
```

The first process environment wins. Missing or non-Boolean metadata, metadata lookup failure, and
stable SDK artifacts fail closed to production. `SimulaAds.apiEnvironment` reports the effective
value. Development hosts may explicitly call `configureApiEnvironment(...)` before initialization;
stable artifacts refuse staging. `devMode` remains independent and does not select the environment.

Initial advertising-ID collection is best effort. Session startup waits at most 2.5 seconds for the
first lookup, then proceeds without the ID; a late lookup can still enrich later requests.

## Dashboard

Create and manage ad units, view analytics, and configure server-side verification at [publisher.simula.ad](https://publisher.simula.ad).

## Support

- Documentation: [docs.simula.ad](https://docs.simula.ad)
- Email: admin@simula.ad
- Website: [simula.ad](https://simula.ad)

## License

MIT
