package ad.simula.ad.sdk.telemetry

import java.util.concurrent.atomic.AtomicReference

/** Process-wide envelope identity resolved live at telemetry flush time. */
internal data class TelemetryIdentity(
    val sessionId: String?,
    val primaryUserId: String?,
)

/** One generation-coherent identity snapshot attached to a telemetry envelope. */
internal data class TelemetryEnvelopeIdentity(
    val sessionId: String?,
    val primaryUserId: String?,
    val advertisingId: String?,
)

internal class ProviderTelemetryIdentityToken internal constructor()

/**
 * Routes telemetry envelope identity without tying the process pipeline to its first entry point.
 * Identity is compatible with the telemetry pipeline's first-wins API key: a different-key entry
 * point can never label batches sent through the winning key. A compatible imperative store wins;
 * otherwise provider-only hosts use the latest compatible active provider committed by Compose.
 */
internal class TelemetryIdentityRouter {
    private class Source(
        val apiKey: String,
        val sessionId: () -> String?,
        val primaryUserId: () -> String?,
    )

    private val providerLock = Any()
    private val providers = LinkedHashMap<ProviderTelemetryIdentityToken, Source>()
    private val providerSnapshot = AtomicReference<List<Source>>(emptyList())
    private val imperative = AtomicReference<Source?>(null)

    fun createProviderToken(): ProviderTelemetryIdentityToken = ProviderTelemetryIdentityToken()

    fun bindProvider(
        token: ProviderTelemetryIdentityToken,
        apiKey: String,
        sessionId: () -> String?,
        primaryUserId: () -> String?,
    ) {
        val source = Source(apiKey, sessionId, primaryUserId)
        synchronized(providerLock) {
            // Replacing an existing token updates its source without changing mount/lifetime order.
            // Only a genuinely new provider token becomes the latest active provider.
            providers[token] = source
            providerSnapshot.set(providers.values.toList())
        }
    }

    fun unbindProvider(token: ProviderTelemetryIdentityToken) {
        synchronized(providerLock) {
            if (providers.remove(token) == null) return
            providerSnapshot.set(providers.values.toList())
        }
    }

    fun bindImperative(
        apiKey: String,
        sessionId: () -> String?,
        primaryUserId: () -> String?,
    ) {
        imperative.set(Source(apiKey, sessionId, primaryUserId))
    }

    fun identity(apiKey: String): TelemetryIdentity {
        val source = imperative.get()?.takeIf { it.apiKey == apiKey }
            ?: providerSnapshot.get().lastOrNull { it.apiKey == apiKey }
        if (source == null) return TelemetryIdentity(null, null)
        return TelemetryIdentity(
            sessionId = runCatching { source.sessionId() }.getOrNull(),
            primaryUserId = runCatching { source.primaryUserId() }.getOrNull(),
        )
    }
}

internal val ProcessTelemetryIdentityRouter = TelemetryIdentityRouter()
