package ad.simula.ad.sdk.telemetry

import ad.simula.ad.sdk.core.LaunchSettledGate
import ad.simula.ad.sdk.core.ProcessLaunchSettledGate
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.network.AdBeaconManager
import ad.simula.ad.sdk.network.RewardVerificationManager
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-wins startup infrastructure shared by imperative and provider-only entry points. The task
 * belongs to the process scope, so disposal/cancellation only abandons a caller's wait. Each step
 * fails open independently while preserving the required beacon -> crash -> recovery order.
 */
internal class StartupInfrastructureCoordinator<T>(
    private val scope: CoroutineScope,
    private val initializeBeaconManager: suspend (T, String) -> Unit,
    private val installCrashGuard: suspend (T, Boolean, LaunchSettledGate) -> Unit,
    private val triggerRewardRecovery: suspend (T, LaunchSettledGate) -> Unit,
    private val triggerBeaconRecovery: suspend (LaunchSettledGate) -> Unit,
) {
    private val beaconInitialization = FirstWinsProcessTask<Unit>(scope)
    private val crashInitialization = FirstWinsProcessTask<Unit>(scope)
    private val recoveryInitialization = FirstWinsProcessTask<Unit>(scope)

    suspend fun initialize(
        owner: T,
        telemetry: EffectiveTelemetryConfig,
        launchSettledGate: LaunchSettledGate,
    ) = initialize(
        owner = owner,
        apiKey = telemetry.apiKey,
        telemetryEnabled = telemetry.enabled,
        launchSettledGate = launchSettledGate,
    )

    suspend fun initialize(
        owner: T,
        apiKey: String,
        telemetryEnabled: Boolean?,
        launchSettledGate: LaunchSettledGate,
    ) {
        beaconInitialization.runOnce {
            settle { initializeBeaconManager(owner, apiKey) }
        }
        if (telemetryEnabled != null) {
            crashInitialization.runOnce {
                settle { installCrashGuard(owner, telemetryEnabled, launchSettledGate) }
            }
        }
        // Recovery is independent of telemetry availability. Its outbound work still waits on the
        // launch-settled gate, while this process-owned launch does not hold startup readiness.
        recoveryInitialization.runOnce {
            scope.launch {
                settle { triggerRewardRecovery(owner, launchSettledGate) }
                settle { triggerBeaconRecovery(launchSettledGate) }
            }
        }
    }

    private suspend fun settle(step: suspend () -> Unit) {
        try {
            step()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Startup diagnostics and durable recovery must never block ad/session readiness.
        }
    }
}

internal object ProcessStartupInfrastructure {
    private val coordinator = StartupInfrastructureCoordinator(
        scope = SimulaScope,
        initializeBeaconManager = { context: Context, apiKey ->
            AdBeaconManager.init(context.applicationContext, apiKey)
        },
        installCrashGuard = { context, enabled, launchSettledGate ->
            withContext(Dispatchers.Main) {
                SimulaCrashGuard.install(
                    context.applicationContext,
                    enabled = enabled,
                    launchSettledGate = launchSettledGate,
                )
            }
        },
        triggerRewardRecovery = { context, launchSettledGate ->
            RewardVerificationManager.triggerProcessQueue(context.applicationContext, launchSettledGate)
        },
        triggerBeaconRecovery = { launchSettledGate ->
            AdBeaconManager.triggerProcessQueue(launchSettledGate)
        },
    )

    suspend fun initialize(
        context: Context,
        apiKey: String,
        telemetry: EffectiveTelemetryConfig?,
        launchSettledGate: LaunchSettledGate = ProcessLaunchSettledGate,
    ) = coordinator.initialize(
        owner = context.applicationContext,
        apiKey = apiKey,
        telemetryEnabled = telemetry?.enabled,
        launchSettledGate = launchSettledGate,
    )
}
