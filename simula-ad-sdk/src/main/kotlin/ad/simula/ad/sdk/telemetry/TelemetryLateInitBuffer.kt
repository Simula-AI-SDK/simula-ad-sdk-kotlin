package ad.simula.ad.sdk.telemetry

internal data class TelemetryServerDirective(val enabled: Boolean, val sampleRate: Double)

internal data class TelemetryLateInitSnapshot(
    val serverDirective: TelemetryServerDirective?,
    val reportMissingProviderContext: Boolean,
)

/** Mutable state guarded by Telemetry's publication lock; split out for deterministic JVM tests. */
internal class TelemetryLateInitBuffer {
    private var latestServerDirective: TelemetryServerDirective? = null
    private var missingProviderContextPending = false
    private var missingProviderContextConsumed = false

    fun updateServerDirective(enabled: Boolean, sampleRate: Double) {
        latestServerDirective = TelemetryServerDirective(enabled, sampleRate)
    }

    /** Coalesces any number of pre/post-init misses into one process-lifetime diagnostic. */
    fun requestMissingProviderContext() {
        if (!missingProviderContextConsumed) missingProviderContextPending = true
    }

    fun consumeMissingProviderContext(): Boolean {
        if (!missingProviderContextPending || missingProviderContextConsumed) return false
        missingProviderContextPending = false
        missingProviderContextConsumed = true
        return true
    }

    fun drainForInitialization(): TelemetryLateInitSnapshot {
        val snapshot = TelemetryLateInitSnapshot(
            serverDirective = latestServerDirective,
            reportMissingProviderContext = consumeMissingProviderContext(),
        )
        latestServerDirective = null
        return snapshot
    }
}
