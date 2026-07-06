# Simula Ad SDK (Kotlin) — AI Development Guide

Rules for any AI agent or developer writing code in this repository. This SDK ships inside third-party apps: a bug here crashes someone else's app.

## Prime directive

**The SDK must never crash the host app.** Priorities, in order:

1. **Stability** — fail gracefully. Degrade to null / blank UI / skipped beacon, never throw into host code.
2. **Performance** — no main-thread I/O, bounded memory everywhere, reuse pooled resources.
3. **Cross-platform parity** — event names, wire keys, and error strings match the Swift and React SDKs.
4. **Maintainability** — extend existing layers; never add a parallel code path or new dependency.

## When in doubt (defaults — do not stop to ask)

- Unsure if something can throw? Wrap it in `runCatching`, record telemetry, return a safe default.
- Unsure about threading? WebView and Compose state are main-thread; I/O and decode go through `SimulaScope`.
- Unsure how to structure a feature? Copy the nearest exemplar file (table below). Never invent a new pattern when an existing one fits.
- Unsure if a dependency is allowed? It isn't. This SDK uses no third-party HTTP, image, analytics, or crash libraries.

## Architecture

```
Host app
  → SimulaProvider (Compose) | SimulaAds.initialize (imperative)   ← must stay behavior-identical
    → SimulaSessionStore.ensureSession()
      → SimulaApiClient (suspend, IO)
        → SimulaHttp (HttpURLConnection — the only HTTP transport)
  → UI: NativeAdSlot / MiniGame / Interstitial / Rewarded
    → ImageCache (only image pipeline) · WebViewPool (only WebView source on hot paths)
    → Telemetry.record* (fire-and-forget, never throws)
```

Package map: `provider/` entry + session · `ads/` fullscreen ads · `nativead/` inline ads · `minigame/` game UI + WebView pool · `network/` HTTP, API client, queues · `telemetry/` pipeline + crash guard · `privacy/` consent · `image/` cache + decode · `model/` shared DTOs · `core/` `SimulaScope`.

Everything is `internal` unless it is deliberately public host-facing API.

## Hard rules (mechanically checkable)

Never introduce:

- `!!` (non-null assertion) — restructure or use safe defaults
- `GlobalScope` — use `SimulaScope` (process-wide) or `LaunchedEffect` (UI-bound)
- `Thread.sleep` on any SDK thread
- New `implementation(...)` dependencies — especially OkHttp/Retrofit/Ktor (use `SimulaHttp`) and Coil/Glide (use `ImageCache`)
- `HttpURLConnection.disconnect()` after successful reads (breaks keep-alive)
- WebView creation, acquire, release, or attach off the main thread
- `clearCache()` on WebView pool release
- New dangerous permissions in the SDK manifest
- Query strings, tokens, or PII in telemetry `message`/`breadcrumb`/paths

## Copy an existing pattern (golden examples)

| Task | Model it on |
|---|---|
| New durable retry queue | `network/AdBeaconQueue.kt` |
| New API endpoint | `network/SimulaApiClient.kt` + models in `network/ApiModels.kt` |
| New inline/Compose ad surface | `nativead/NativeAdSlot.kt` |
| New fullscreen ad format | `ads/SimulaInterstitialAd.kt` + its Activity |
| New device/network signal | `network/SimulaConnectionType.kt` |
| Engine test with fakes | `src/test/.../TelemetryManagerTest.kt` |
| Pure-function test | `src/test/.../SimulaConnectionTypeTest.kt` |

Numeric limits (pool sizes, cache caps, timeouts) live as constants in those files — read them there; do not trust docs for values.

## Canonical patterns

Graceful degradation at every boundary:

```kotlin
val decoded = runCatching { decode(bytes) }.getOrElse { t ->
    Telemetry.recordError(signature = "image:decode_failed", message = t.message)
    return DecodedImage.Failed   // blank UI, not a crash
}
```

Telemetry (signatures are low-cardinality `domain:detail` keys; lifecycle stages are stable snake_case names shared with iOS):

```kotlin
Telemetry.recordLifecycle(stage = "load_success", adFormat = "interstitial", adUnitId = id, durationMs = ms)
Telemetry.recordError(signature = "webview:render_gone", breadcrumb = "surface=native_ad")
```

Background work:

```kotlin
// Process-wide, survives composition: SimulaScope (SupervisorJob + IO + crash handler).
SimulaScope.launch { queue.processPending() }
// UI-bound, cancels with the composable: LaunchedEffect / rememberCoroutineScope.
```

## Non-negotiable behaviors

- **Provider/imperative parity**: `SimulaProvider` and `SimulaAds.initialize` must perform identical priming (`SimulaUserAgent.build`, `SimulaDeviceId.prime`, `SimulaConnectionType.prime`), privacy resolution, session setup, and telemetry install. A change to one is a change to both.
- **Uninitialized SDK**: return null / render blank / log one warning. `require()` only at documented init boundaries.
- **WebView render-process death**: absorb and report (`webview:render_gone`); returning "unhandled" kills the host process.
- **Durable work** (beacons, reward verification, telemetry): serializable item + persistent store + backoff (drop permanent 4xx, retry transient) + `triggerProcessQueue()` on init for process recovery.
- **Consent**: headers come from `SimulaPrivacy` at request time; PII (PPID/GAID) is re-read at flush from live consent, never cached.
- **Connection type**: `X-Connection-Type` is read live per request from `SimulaConnectionType`, never cached at init.
- **Coalescing**: only idempotent reads (catalog, fallbacks). Never session create, ad load, or verify-reward.
- **Lifecycle**: Activity references are `WeakReference`, cleared on destroy (not pause).

## Testing

Tests live in `src/test/kotlin/...` mirroring main packages. JUnit 4 + `kotlinx-coroutines-test`. Extract pure functions for JVM tests; inject fakes (store, sender, clock, backoff, random) into engines; use `runTest` with virtual time. No Robolectric, no real network.

## Version sync (all three, always together)

1. `simula-ad-sdk/build.gradle.kts` — publish coordinates
2. `telemetry/Telemetry.kt` — `SIMULA_SDK_VERSION`
3. `SimulaAdSdk.kt` — `SimulaAdSdkInfo.VERSION`

## Definition of done — mandatory gate

The task is not complete until both commands pass:

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
```

CI (`.github/workflows/ci.yml`) runs these plus `assembleRelease`. If you changed public API or behavior, check whether the same change is needed in `../simula-ad-sdk-swift` and say so in your summary.
