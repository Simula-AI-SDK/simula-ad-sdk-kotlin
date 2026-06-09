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

    /**
     * `show()` was called on an ad that loaded more than an hour ago. A loaded ad
     * expires after 1 hour; load a fresh one. The message is part of the public
     * contract (shared verbatim with the Swift SDK).
     */
    object Stale : SimulaAdError("Ad is stale, please load again")

    /**
     * `load()` was blocked because a matching ad — same (ad unit id, character id,
     * character name, session id) — is already loaded or in flight. Re-loads of the
     * same key are throttled for 5 minutes; load with a different ad unit or
     * character to bypass it.
     */
    object DuplicateRequest : SimulaAdError("Duplicate ad request — a matching ad is already loaded or loading")

    /** `show()` was called while an interstitial is already showing. */
    object AlreadyShowing : SimulaAdError("An interstitial is already showing")

    /** No Activity/context was available to present the interstitial from. */
    object NoPresentationContext : SimulaAdError("No Activity available to present from")

    /** An underlying network/decoding error occurred during load. */
    class Network(cause: Throwable?) : SimulaAdError("Network error", cause)
}
