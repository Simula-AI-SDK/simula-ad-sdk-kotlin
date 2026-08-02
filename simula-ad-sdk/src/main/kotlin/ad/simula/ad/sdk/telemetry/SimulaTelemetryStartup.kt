package ad.simula.ad.sdk.telemetry

import ad.simula.ad.sdk.core.SimulaScope
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Session identity callbacks are published as one volatile value so readers never see a torn pair. */
internal data class TelemetryIdentityProviders(
    val sessionId: () -> String?,
    val primaryUserId: () -> String?,
)

/** 4 s telemetry + 8 s GAID leaves 3 s before the outer 15 s startup gate. */
internal const val TELEMETRY_READY_TIMEOUT_MS = 4_000L

/**
 * Waits for the process-wide telemetry startup without transferring cancellation to its shared
 * deferred. A wedged first registration must not strand either entry point's local startup gate.
 */
internal suspend fun awaitTelemetryReady(
    ready: Deferred<Unit>,
    timeoutMs: Long = TELEMETRY_READY_TIMEOUT_MS,
): Boolean = withTimeoutOrNull(timeoutMs) { ready.await(); true } ?: false

/**
 * Testable first-registration-wins, single-flight startup engine. Registration is intentionally
 * separate from construction so Compose callers can commit it from an effect rather than from
 * speculative composition. Later registrations retain the first config and atomically replace the
 * live identity-provider pair only when their priority is at least the current provider's.
 *
 * [runStartup] is the gated section: [ready] completes only when it finishes, and both entry
 * paths await that before releasing ad requests. [runUngated] is launched right after the gated
 * section finishes (so it can rely on the telemetry manager being installed) but as a sibling
 * job that [ready] never awaits — for work that must not hold the ad-request gate (e.g. a
 * main-thread install: a stalled main looper would otherwise freeze every ad load).
 */
internal class TelemetryStartupEngine<C>(
    private val scope: CoroutineScope,
    private val runStartup: suspend (C, () -> TelemetryIdentityProviders) -> Unit,
    private val runUngated: (suspend (C) -> Unit)? = null,
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
            job.invokeOnCompletion {
                ready.complete(Unit)
                // Ungated follow-up (crash-guard install): strictly after the gated section —
                // its replay records into the telemetry manager, so it must follow
                // Telemetry.initialize — but never awaited by `ready`, so a stalled main
                // thread cannot hold the ad-request gate.
                runUngated?.let { ungated -> scope.launch { runCatching { ungated(config) } } }
            }
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

    private val engine = TelemetryStartupEngine<Config>(
        scope = SimulaScope,
        runStartup = { config, currentProviders ->
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
        },
        runUngated = { config ->
            // The crash guard needs the main thread, but ad requests must never wait on
            // main-thread health — so this install runs as an ungated follow-up (strictly
            // after telemetry install, which its crash replay records into) instead of inside
            // the gated startup. Swift mirrors this with a fire-and-forget
            // DispatchQueue.main.async in SimulaCrashGuard.install.
            withContext(Dispatchers.Main) {
                runCatching { SimulaCrashGuard.install(config.context, config.enabled) }
            }
        },
    )

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
