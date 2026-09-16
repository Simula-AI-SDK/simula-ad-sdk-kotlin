package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.BuildConfig
import ad.simula.ad.sdk.SimulaAdSdkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiEnvironmentTest {

    private val production = "https://production.example"
    private val staging = "https://staging.example"

    @Test
    fun `stable SDK always selects production`() {
        val policy = ApiEnvironmentPolicy(
            stagingCapable = false,
            stagingBaseUrl = "",
            productionBaseUrl = production,
        )

        assertEquals(ApiEnvironment.Production, policy.requestedConfiguration(devMode = false).environment)
        assertEquals(ApiEnvironment.Production, policy.requestedConfiguration(devMode = true).environment)
    }

    @Test
    fun `dev SDK uses devMode as the API selector`() {
        val policy = ApiEnvironmentPolicy(
            stagingCapable = true,
            stagingBaseUrl = staging,
            productionBaseUrl = production,
        )

        assertEquals(production, policy.requestedConfiguration(devMode = false).baseUrl)
        assertEquals(staging, policy.requestedConfiguration(devMode = true).baseUrl)
    }

    @Test
    fun `blank generated staging URL fails safely to production`() {
        val selected = ApiEnvironmentPolicy(
            stagingCapable = true,
            stagingBaseUrl = " ",
            productionBaseUrl = production,
        ).requestedConfiguration(devMode = true)

        assertEquals(ApiEnvironment.Production, selected.environment)
        assertEquals(production, selected.baseUrl)
    }

    @Test
    fun `first frozen environment wins conflicting process attempts`() {
        val policy = ApiEnvironmentPolicy(
            stagingCapable = true,
            stagingBaseUrl = staging,
            productionBaseUrl = production,
        )

        val first = policy.freeze(devMode = true)
        val conflict = policy.freeze(devMode = false)
        val same = policy.freeze(devMode = true)

        assertEquals(ApiEnvironment.Staging, first.configuration.environment)
        assertEquals(first.configuration, conflict.configuration)
        assertEquals(first.configuration, same.configuration)
        assertTrue(conflict.conflictsWithFrozenEnvironment)
        assertFalse(same.conflictsWithFrozenEnvironment)
    }

    @Test
    fun `reading a frozen staging environment does not request production`() {
        val policy = ApiEnvironmentPolicy(
            stagingCapable = true,
            stagingBaseUrl = staging,
            productionBaseUrl = production,
        )

        policy.freeze(devMode = true)

        assertEquals(ApiEnvironment.Staging, policy.currentOrProduction().environment)
    }

    @Test
    fun `URL construction normalizes one path separator`() {
        assertEquals("https://api.example/session/create", apiUrl("https://api.example/", "/session/create"))
    }

    @Test
    fun `production store names and migrations remain unchanged`() {
        assertEquals("simula_ad_sdk_telemetry.db", ApiEnvironment.Production.storageName("simula_ad_sdk_telemetry.db"))
        assertEquals("simula_ad_sdk_beacon_prefs", ApiEnvironment.Production.storageName("simula_ad_sdk_beacon_prefs"))
        assertEquals("simula_crash", ApiEnvironment.Production.storageName("simula_crash"))
    }

    @Test
    fun `staging durable stores and telemetry files use isolated names`() {
        assertEquals("simula_ad_sdk_telemetry_staging.db", ApiEnvironment.Staging.storageName("simula_ad_sdk_telemetry.db"))
        assertEquals("simula_ad_sdk_beacon_prefs_staging", ApiEnvironment.Staging.storageName("simula_ad_sdk_beacon_prefs"))
        assertEquals("simula_crash_staging", ApiEnvironment.Staging.storageName("simula_crash"))
    }

    @Test
    fun `generated staging configuration follows the artifact version tag`() {
        val devTagged = Regex("^\\d+\\.\\d+\\.\\d+-dev\\.\\d+$").matches(SimulaAdSdkInfo.VERSION)
        assertEquals(devTagged, BuildConfig.SIMULA_STAGING_CAPABLE)
        if (devTagged) {
            assertEquals(
                "https://simula-api-staging-701226639755.us-central1.run.app",
                BuildConfig.SIMULA_STAGING_BASE_URL,
            )
        } else {
            assertEquals("", BuildConfig.SIMULA_STAGING_BASE_URL)
        }
    }
}
