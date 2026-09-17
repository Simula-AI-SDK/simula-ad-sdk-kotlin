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

## Staging Environment

Staging is restricted to exact `X.Y.Z-dev.N` SDK artifacts and requires an explicit host manifest
opt-in:

```xml
<application>
    <meta-data
        android:name="SimulaStagingEnvironmentEnabled"
        android:value="true" />
</application>
```

Request staging before `SimulaAds.initialize(...)` or before composing `SimulaProvider`:

```kotlin
val stagingEnabled = SimulaAds.configureApiEnvironment(
    context = applicationContext,
    environment = SimulaApiEnvironment.Staging,
)
```

The first process environment wins. Missing or non-Boolean metadata, metadata lookup failure, and
stable SDK artifacts fail closed to production. `devMode` remains available on existing APIs for
its non-endpoint development behavior, but it does not select the API environment.

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
