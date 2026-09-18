package ad.simula.ad.sdk.ads

/**
 * API environment used for the current application process.
 *
 * [Staging] is available only in exact `X.Y.Z-dev.N` SDK artifacts and when the host application's
 * manifest explicitly opts in with Boolean metadata. Initialization reads that metadata directly.
 */
enum class SimulaApiEnvironment {
    Production,
    Staging,
}
