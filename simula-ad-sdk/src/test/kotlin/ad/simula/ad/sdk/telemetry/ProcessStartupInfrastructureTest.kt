package ad.simula.ad.sdk.telemetry

import ad.simula.ad.sdk.core.LaunchSettledGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessStartupInfrastructureTest {

    @Test
    fun `provider-only startup initializes beacon before recovery and readiness`() = runTest {
        val steps = mutableListOf<String>()
        val coordinator = StartupInfrastructureCoordinator(
            scope = backgroundScope,
            initializeBeaconManager = { _: Unit, apiKey -> steps += "beacon:$apiKey" },
            installCrashGuard = { _, enabled, _ -> steps += "crash:$enabled" },
            triggerRewardRecovery = { _, _ -> steps += "reward" },
            triggerBeaconRecovery = { _ -> steps += "beacon_recovery" },
        )

        coordinator.initialize(
            Unit,
            EffectiveTelemetryConfig("provider-key", devMode = false, enabled = true),
            LaunchSettledGate.Open,
        )
        steps += "provider_ready"
        runCurrent()

        assertEquals("beacon:provider-key", steps.first())
        assertTrue(steps.indexOf("beacon:provider-key") < steps.indexOf("provider_ready"))
        assertEquals(1, steps.count { it == "reward" })
        assertEquals(1, steps.count { it == "beacon_recovery" })
    }

    @Test
    fun `disabled first config controls later enabled startup claim`() = runTest {
        val beaconKeys = mutableListOf<String>()
        val crashStates = mutableListOf<Boolean>()
        val coordinator = StartupInfrastructureCoordinator(
            scope = backgroundScope,
            initializeBeaconManager = { _: Unit, apiKey -> beaconKeys += apiKey },
            installCrashGuard = { _, enabled, _ -> crashStates += enabled },
            triggerRewardRecovery = { _, _ -> },
            triggerBeaconRecovery = {},
        )
        val effective = EffectiveTelemetryConfig("first", devMode = false, enabled = false)

        coordinator.initialize(Unit, effective, LaunchSettledGate.Open)
        coordinator.initialize(
            Unit,
            EffectiveTelemetryConfig("second", devMode = true, enabled = true),
            LaunchSettledGate.Open,
        )

        assertEquals(listOf("first"), beaconKeys)
        assertEquals(listOf(false), crashStates)
    }

    @Test
    fun `telemetry failure still initializes beacons and launches durable recovery`() = runTest {
        val steps = mutableListOf<String>()
        val coordinator = StartupInfrastructureCoordinator(
            scope = backgroundScope,
            initializeBeaconManager = { _: Unit, apiKey -> steps += "beacon:$apiKey" },
            installCrashGuard = { _, _, _ -> steps += "crash" },
            triggerRewardRecovery = { _, _ -> steps += "reward" },
            triggerBeaconRecovery = { _ -> steps += "beacon_recovery" },
        )

        coordinator.initialize(
            owner = Unit,
            apiKey = "winning-key",
            telemetryEnabled = null,
            launchSettledGate = LaunchSettledGate.Open,
        )
        steps += "ready"
        runCurrent()

        assertEquals("beacon:winning-key", steps.first())
        assertTrue("crash" !in steps)
        assertTrue(steps.indexOf("beacon:winning-key") < steps.indexOf("ready"))
        assertEquals(1, steps.count { it == "reward" })
        assertEquals(1, steps.count { it == "beacon_recovery" })
    }

    @Test
    fun `concurrent callers share one readiness task and one recovery launch`() = runTest {
        val beaconEntered = CompletableDeferred<Unit>()
        val releaseBeacon = CompletableDeferred<Unit>()
        var beaconInitializations = 0
        var recoveries = 0
        val coordinator = StartupInfrastructureCoordinator(
            scope = backgroundScope,
            initializeBeaconManager = { _: Unit, _ ->
                beaconInitializations++
                beaconEntered.complete(Unit)
                releaseBeacon.await()
            },
            installCrashGuard = { _, _, _ -> },
            triggerRewardRecovery = { _, _ -> recoveries++ },
            triggerBeaconRecovery = { _ -> recoveries++ },
        )
        val config = EffectiveTelemetryConfig("key", devMode = false, enabled = true)

        val first = async { coordinator.initialize(Unit, config, LaunchSettledGate.Open) }
        beaconEntered.await()
        val second = async { coordinator.initialize(Unit, config, LaunchSettledGate.Open) }
        runCurrent()
        assertEquals(1, beaconInitializations)

        releaseBeacon.complete(Unit)
        first.await()
        second.await()
        runCurrent()

        assertEquals(1, beaconInitializations)
        assertEquals(2, recoveries)
    }

    @Test
    fun `canceling one waiter does not cancel process readiness`() = runTest {
        val beaconEntered = CompletableDeferred<Unit>()
        val releaseBeacon = CompletableDeferred<Unit>()
        var crashInstalls = 0
        val coordinator = StartupInfrastructureCoordinator(
            scope = backgroundScope,
            initializeBeaconManager = { _: Unit, _ ->
                beaconEntered.complete(Unit)
                releaseBeacon.await()
            },
            installCrashGuard = { _, _, _ -> crashInstalls++ },
            triggerRewardRecovery = { _, _ -> },
            triggerBeaconRecovery = {},
        )
        val config = EffectiveTelemetryConfig("key", devMode = false, enabled = true)
        val canceled = launch { coordinator.initialize(Unit, config, LaunchSettledGate.Open) }
        beaconEntered.await()

        canceled.cancel()
        canceled.join()
        val later = async { coordinator.initialize(Unit, config, LaunchSettledGate.Open) }
        releaseBeacon.complete(Unit)
        later.await()

        assertEquals(1, crashInstalls)
    }

    @Test
    fun `startup step failures are isolated`() = runTest {
        val steps = mutableListOf<String>()
        val releaseRecovery = CompletableDeferred<Unit>()
        val coordinator = StartupInfrastructureCoordinator(
            scope = backgroundScope,
            initializeBeaconManager = { _: Unit, _ ->
                steps += "beacon"
                error("beacon unavailable")
            },
            installCrashGuard = { _, _, _ -> steps += "crash" },
            triggerRewardRecovery = { _, _ ->
                steps += "reward"
                releaseRecovery.await()
                error("reward unavailable")
            },
            triggerBeaconRecovery = { _ -> steps += "beacon_recovery" },
        )

        coordinator.initialize(
            Unit,
            EffectiveTelemetryConfig("key", devMode = false, enabled = true),
            LaunchSettledGate.Open,
        )
        steps += "ready"
        runCurrent()
        assertTrue(steps.indexOf("crash") < steps.indexOf("ready"))
        assertTrue("recovery must not hold readiness", "ready" in steps)

        releaseRecovery.complete(Unit)
        runCurrent()

        assertTrue(steps.indexOf("ready") < steps.indexOf("beacon_recovery"))
        assertEquals(listOf("beacon", "crash", "reward", "ready", "beacon_recovery"), steps)
    }
}
