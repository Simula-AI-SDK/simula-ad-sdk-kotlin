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

## Publisher Metadata

Attach non-sensitive string metadata to ad loads for reporting and attribution:

```kotlin
val interstitial = SimulaInterstitialAd("ad-unit-id").apply {
    setExtraParameters(mapOf("placement" to "home", "experiment" to "hero_v2"))
}
interstitial.load()

NativeAdSlot(
    adUnitId = "native-unit-id",
    extraParameters = mapOf("placement" to "feed"),
)
```

`SimulaRewardedAd` supports the same `setExtraParameter` and `setExtraParameters` methods. Metadata
is snapshotted when a load starts; changing parameters later affects future loads, not one already in
flight. Native cached/preloaded fills keep the snapshot used when the slot consumes them for the
durable `/seen` beacon.

Metadata is limited to 10 entries. Keys must be non-empty, at most 64 Unicode code points, must not
start with `$`, and must not contain `.`. Values are limited to 256 Unicode code points. Invalid or
over-limit entries are ignored safely and reported in Logcat and SDK telemetry. Do not include PII,
credentials, tokens, or other secrets.

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
