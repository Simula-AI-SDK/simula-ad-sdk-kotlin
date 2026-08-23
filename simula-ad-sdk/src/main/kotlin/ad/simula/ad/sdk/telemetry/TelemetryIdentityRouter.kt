package ad.simula.ad.sdk.telemetry

import java.util.concurrent.atomic.AtomicReference

/** Process-wide envelope identity resolved live at telemetry flush time. */
internal data class TelemetryIdentity(
    val sessionId: String?,
    val primaryUserId: String?,
)

internal class ProviderTelemetryIdentityToken internal constructor()

/**
 * Routes telemetry envelope identity without tying the process pipeline to its first entry point.
 * Once present, the imperative store wins because an envelope has one process-wide identity;
 * provider-only hosts use the latest active provider store committed by Compose.
 */
internal class TelemetryIdentityRouter {
    private class Source(
        val sessionId: () -> String?,
        val primaryUserId: () -> String?,
    )

    private val providerLock = Any()
    private val providers = LinkedHashMap<ProviderTelemetryIdentityToken, Source>()
    private val provider = AtomicReference<Source?>(null)
    private val imperative = AtomicReference<Source?>(null)

    fun createProviderToken(): ProviderTelemetryIdentityToken = ProviderTelemetryIdentityToken()

    fun bindProvider(
        token: ProviderTelemetryIdentityToken,
        sessionId: () -> String?,
        primaryUserId: () -> String?,
    ) {
        val source = Source(sessionId, primaryUserId)
        synchronized(providerLock) {
            // LinkedHashMap does not reorder an existing key, so remove first to make a committed
            // replacement the latest active provider.
            providers.remove(token)
            providers[token] = source
            provider.set(source)
        }
    }

    fun unbindProvider(token: ProviderTelemetryIdentityToken) {
        synchronized(providerLock) {
            if (providers.remove(token) == null) return
            provider.set(providers.entries.lastOrNull()?.value)
        }
    }

    fun bindImperative(
        sessionId: () -> String?,
        primaryUserId: () -> String?,
    ) {
        imperative.set(Source(sessionId, primaryUserId))
    }

    fun identity(): TelemetryIdentity {
        val source = imperative.get() ?: provider.get()
        if (source == null) return TelemetryIdentity(null, null)
        return TelemetryIdentity(
            sessionId = runCatching { source.sessionId() }.getOrNull(),
            primaryUserId = runCatching { source.primaryUserId() }.getOrNull(),
        )
    }
}

internal val ProcessTelemetryIdentityRouter = TelemetryIdentityRouter()
