package ad.simula.ad.sdk.ads

/**
 * API environment requested for the current application process.
 *
 * [Staging] is available only in exact `X.Y.Z-dev.N` SDK artifacts and when the host application's
 * manifest explicitly opts in with Boolean metadata. Request an environment with
 * [SimulaAds.configureApiEnvironment] before calling `SimulaAds.initialize` or composing a
 * `SimulaProvider`.
 */
enum class SimulaApiEnvironment {
    Production,
    Staging,
}
