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
    object Stale : SimulaAdError(
        "The loaded ad has expired (1 hour limit) and can no longer be shown. " +
            "Call load() to request a new ad.",
    )

    /**
     * `load()` was blocked because a matching ad — same (ad unit id, character id,
     * character name, session id) — is already loaded or in flight. Re-loads of the
     * same key are throttled for 5 minutes; load with a different ad unit or
     * character to bypass it. The message says whether the matching ad is ready or
     * still loading (and, when ready, how long until `load()` unblocks). Messages are
     * part of the public contract, shared with the Swift SDK — verbatim except the
     * "loading" copy names this platform's load callback (`onAdLoaded`, vs the iOS
     * `didLoad` delegate callback).
     */
    class DuplicateRequest private constructor(message: String) : SimulaAdError(message) {
        internal companion object {
            /** The matching ad is loaded and showable; [remainingSec] is the time left in the dedup window. */
            fun ready(remainingSec: Int) = DuplicateRequest(
                "An ad for this placement is already loaded. Call show() to display it, " +
                    "or load() again in $remainingSec seconds.",
            )

            /** The matching ad's load is still in flight. */
            fun loading() = DuplicateRequest(
                "An ad for this placement is already loading. " +
                    "Wait for the onAdLoaded callback before calling load() again.",
            )
        }
    }

    /** `show()` was called while an interstitial is already showing. */
    object AlreadyShowing : SimulaAdError("An interstitial is already showing")

    /** No Activity/context was available to present the interstitial from. */
    object NoPresentationContext : SimulaAdError("No Activity available to present from")

    /** An underlying network/decoding error occurred during load. */
    class Network(cause: Throwable?) : SimulaAdError("Network error", cause)
}
