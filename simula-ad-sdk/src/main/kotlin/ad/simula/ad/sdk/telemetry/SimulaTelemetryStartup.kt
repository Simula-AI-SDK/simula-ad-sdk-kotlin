package ad.simula.ad.sdk.telemetry

import ad.simula.ad.sdk.core.SimulaScope
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Session identity callbacks are published as one volatile value so readers never see a torn pair. */
internal data class TelemetryIdentityProviders(
    val sessionId: () -> String?,
    val primaryUserId: () -> String?,
)

/**
 * Testable first-registration-wins, single-flight startup engine. Registration is intentionally
 * separate from construction so Compose callers can commit it from an effect rather than from
 * speculative composition. Later registrations retain the first config and atomically replace the
 * live identity-provider pair only when their priority is at least the current provider's.
 */
internal class TelemetryStartupEngine<C>(
    private val scope: CoroutineScope,
    private val runStartup: suspend (C, () -> TelemetryIdentityProviders) -> Unit,
) {
    private data class Registration<C>(val config: C)

    private val lock = Any()
    private var registration: Registration<C>? = null
    private var started = false
    private var identityPriority = Int.MIN_VALUE
    private val ready = CompletableDeferred<Unit>()

    @Volatile
    private var identityProviders = TelemetryIdentityProviders(sessionId = { null }, primaryUserId = { null })

    fun register(
        config: C,
        providers: TelemetryIdentityProviders,
        providerPriority: Int = 0,
    ): CompletableDeferred<Unit> =
        synchronized(lock) {
            if (providerPriority >= identityPriority) {
                identityProviders = providers
                identityPriority = providerPriority
            }
            if (registration == null) registration = Registration(config)
            ready
        }

    fun start() {
        val config = synchronized(lock) {
            val current = registration ?: return
            if (started) return
            started = true
            current.config
        }
        try {
            val job = scope.launch { runCatching { runStartup(config) { identityProviders } } }
            job.invokeOnCompletion { ready.complete(Unit) }
        } catch (_: Exception) {
            ready.complete(Unit)
        }
    }
}

/** One process-wide telemetry/crash startup shared by imperative and declarative entry points. */
internal object SimulaTelemetryStartup {
    private data class Config(
        val context: Context,
        val apiKey: String,
        val devMode: Boolean,
        val enabled: Boolean,
    )

    private val engine = TelemetryStartupEngine<Config>(SimulaScope) { config, currentProviders ->
        runCatching {
            Telemetry.initialize(
                context = config.context,
                apiKey = config.apiKey,
                devMode = config.devMode,
                enabled = config.enabled,
                identityProvider = {
                    val providers = currentProviders()
                    TelemetryIdentity(providers.sessionId(), providers.primaryUserId())
                },
            )
        }
        withContext(Dispatchers.Main) {
            runCatching { SimulaCrashGuard.install(config.context, config.enabled) }
        }
    }

    /**
     * Commits configuration for the process. Imperative initialization calls this directly; Compose
     * must call it only from a committed effect. The first config wins, while identity callbacks stay
     * live for mixed provider/imperative integrations.
     */
    fun register(
        context: Context,
        apiKey: String,
        devMode: Boolean,
        enabled: Boolean,
        sessionIdProvider: () -> String?,
        primaryUserIdProvider: () -> String?,
        identityPriority: Int = 0,
    ): CompletableDeferred<Unit> = engine.register(
        Config(context.applicationContext, apiKey, devMode, enabled),
        TelemetryIdentityProviders(sessionIdProvider, primaryUserIdProvider),
        identityPriority,
    )

    fun start() = engine.start()
}
