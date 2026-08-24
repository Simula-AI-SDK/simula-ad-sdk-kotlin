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
    private val initialization = FirstWinsProcessTask<Unit>(scope)

    suspend fun initialize(
        owner: T,
        telemetry: EffectiveTelemetryConfig,
        launchSettledGate: LaunchSettledGate,
    ) {
        initialization.runOnce {
            settle { initializeBeaconManager(owner, telemetry.apiKey) }
            settle { installCrashGuard(owner, telemetry.enabled, launchSettledGate) }
            // Queue construction above is readiness-critical. Prior-process drains are not: launch
            // them process-owned so the startup gate remains limited to consent, telemetry, and
            // beacon availability while their outbound work still waits on the launch-settled gate.
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
        telemetry: EffectiveTelemetryConfig,
        launchSettledGate: LaunchSettledGate = ProcessLaunchSettledGate,
    ) = coordinator.initialize(context.applicationContext, telemetry, launchSettledGate)
}
