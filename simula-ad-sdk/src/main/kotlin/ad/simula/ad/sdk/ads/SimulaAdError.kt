package ad.simula.ad.sdk.ads

/**
 * Failure cases for the imperative interstitial API. Mirrors the Swift SDK's
 * `SimulaAdError`.
 *
 * Extends [Exception] so an error can be logged with a stack trace or rethrown,
 * while remaining a `sealed class` for exhaustive `when` handling.
 */
sealed class SimulaAdError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** [SimulaAds.initialize] was never called. */
    object NotInitialized : SimulaAdError("SimulaAds.initialize() was not called")

    /** A server session could not be created (e.g. network failure). */
    object NoSession : SimulaAdError("Session could not be created")

    /** The server returned no creative to display. */
    object NoFill : SimulaAdError("No ad available (no fill)")

    /** `show()` was called before `load()` completed. */
    object NotReady : SimulaAdError("Ad not ready — call load() first")

    /** `show()` was called while an interstitial is already showing. */
    object AlreadyShowing : SimulaAdError("An interstitial is already showing")

    /** No Activity/context was available to present the interstitial from. */
    object NoPresentationContext : SimulaAdError("No Activity available to present from")

    /** An underlying network/decoding error occurred during load. */
    class Network(cause: Throwable?) : SimulaAdError("Network error", cause)
}

/** Stable, low-cardinality code for this error, used as the `error_code` on telemetry events. */
internal fun SimulaAdError.telemetryCode(): String = when (this) {
    SimulaAdError.NotInitialized -> "not_initialized"
    SimulaAdError.NoSession -> "no_session"
    SimulaAdError.NoFill -> "no_fill"
    SimulaAdError.NotReady -> "not_ready"
    SimulaAdError.AlreadyShowing -> "already_showing"
    SimulaAdError.NoPresentationContext -> "no_presentation_context"
    is SimulaAdError.Network -> "network"
}
