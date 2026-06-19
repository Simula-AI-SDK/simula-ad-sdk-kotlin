package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.ads.SimulaAdError

/**
 * Failure cases a [NativeAdSlot] surfaces through its `onError`. Scoped to the four outcomes an inline
 * native slot can actually produce, unlike the imperative [SimulaAdError] whose `show()`/`load()`
 * lifecycle cases (not-ready, stale, duplicate-request, already-showing, no-presentation-context, …)
 * can never apply to a feed slot. Mirrors the Swift SDK's `NativeAdError`.
 */
enum class NativeAdError {
    /** [ad.simula.ad.sdk.ads.SimulaAds.initialize] was never called. */
    NotInitialized,

    /** A server session could not be created — bad API key / network, or a 401 on the ad request. */
    NoSession,

    /** The server returned no creative for this slot (no fill). The slot also collapses to zero height. */
    NoFill,

    /** A network or decoding error occurred while loading the creative. */
    Network,

    /** The ad unit id isn't registered for this app (wrong id / different publisher). Non-retryable. */
    AdUnitNotFound,
}

/** Stable, low-cardinality code for this error, used as the `error_code` on telemetry events. Matches
 * the imperative [ad.simula.ad.sdk.ads.telemetryCode] strings for the shared cases. */
internal fun NativeAdError.telemetryCode(): String = when (this) {
    NativeAdError.NotInitialized -> "not_initialized"
    NativeAdError.NoSession -> "no_session"
    NativeAdError.NoFill -> "no_fill"
    NativeAdError.Network -> "network"
    NativeAdError.AdUnitNotFound -> "ad_unit_not_found"
}

/**
 * Maps an internal load-path [SimulaAdError] to the public native taxonomy. Only the four native
 * outcomes can occur on the native load path; any other case is mapped defensively to
 * [NativeAdError.Network] so an unexpected error is never dropped.
 */
internal fun SimulaAdError.toNativeAdError(): NativeAdError = when (this) {
    SimulaAdError.NotInitialized -> NativeAdError.NotInitialized
    SimulaAdError.NoSession -> NativeAdError.NoSession
    SimulaAdError.NoFill -> NativeAdError.NoFill
    is SimulaAdError.Network -> NativeAdError.Network
    SimulaAdError.AdUnitNotFound -> NativeAdError.AdUnitNotFound
    else -> NativeAdError.Network
}
