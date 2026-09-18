package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.BuildConfig
import ad.simula.ad.sdk.SimulaAdSdkInfo
import ad.simula.ad.sdk.ads.SimulaApiEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiEnvironmentTest {

    private val production = "https://production.example"
    private val staging = "https://staging.example"

    @Test
    fun `stable SDK always selects production`() {
        val selected = resolveApiEnvironment(
            inputs = ApiEnvironmentResolverInputs(
                requestedEnvironment = SimulaApiEnvironment.Staging,
                stagingCapable = false,
                stagingBaseUrl = "",
                stagingManifestValue = true,
            ),
            productionBaseUrl = production,
        )

        assertEquals(ApiEnvironment.Production, selected.environment)
        assertEquals(production, selected.baseUrl)
    }

    @Test
    fun `pure resolver requires request capability and exact boolean manifest gate`() {
        fun resolve(
            requested: SimulaApiEnvironment,
            manifestValue: Any?,
        ) = resolveApiEnvironment(
            inputs = ApiEnvironmentResolverInputs(
                requestedEnvironment = requested,
                stagingCapable = true,
                stagingBaseUrl = staging,
                stagingManifestValue = manifestValue,
            ),
            productionBaseUrl = production,
        )

        assertEquals(production, resolve(SimulaApiEnvironment.Production, true).baseUrl)
        assertEquals(production, resolve(SimulaApiEnvironment.Staging, false).baseUrl)
        assertEquals(staging, resolve(SimulaApiEnvironment.Staging, true).baseUrl)
    }

    @Test
    fun `pure resolver fails closed for missing and malformed manifest gates`() {
        listOf(null, "true", 1).forEach { manifestValue ->
            val selected = resolveApiEnvironment(
                inputs = ApiEnvironmentResolverInputs(
                    requestedEnvironment = SimulaApiEnvironment.Staging,
                    stagingCapable = true,
                    stagingBaseUrl = staging,
                    stagingManifestValue = manifestValue,
                ),
                productionBaseUrl = production,
            )

            assertEquals(ApiEnvironment.Production, selected.environment)
        }
    }

    @Test
    fun `policy uses the pure resolver for staging configuration`() {
        val policy = ApiEnvironmentPolicy(
            stagingCapable = false,
            stagingBaseUrl = "",
            productionBaseUrl = production,
        )

        assertEquals(
            ApiEnvironment.Production,
            policy.requestedConfiguration(SimulaApiEnvironment.Staging, true).environment,
        )
    }

    @Test
    fun `blank generated staging URL fails safely to production`() {
        val selected = ApiEnvironmentPolicy(
            stagingCapable = true,
            stagingBaseUrl = " ",
            productionBaseUrl = production,
        ).requestedConfiguration(SimulaApiEnvironment.Staging, true)

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

        val first = policy.configure(SimulaApiEnvironment.Staging, true)
        val conflict = policy.configure(SimulaApiEnvironment.Production, null)
        val same = policy.configure(SimulaApiEnvironment.Staging, true)

        assertEquals(ApiEnvironment.Staging, first.configuration.environment)
        assertEquals(first.configuration, conflict.configuration)
        assertEquals(first.configuration, same.configuration)
        assertTrue(conflict.conflictsWithFrozenEnvironment)
        assertFalse(same.conflictsWithFrozenEnvironment)
    }

    @Test
    fun `failed staging gate freezes production and later staging cannot split traffic`() {
        val policy = ApiEnvironmentPolicy(
            stagingCapable = true,
            stagingBaseUrl = staging,
            productionBaseUrl = production,
        )

        val denied = policy.configure(SimulaApiEnvironment.Staging, null)
        val laterAllowed = policy.configure(SimulaApiEnvironment.Staging, true)

        assertEquals(ApiEnvironment.Production, denied.configuration.environment)
        assertEquals(ApiEnvironment.Production, laterAllowed.configuration.environment)
        assertTrue(laterAllowed.conflictsWithFrozenEnvironment)
    }

    @Test
    fun `initialization host default preserves explicitly configured staging without conflict`() {
        val policy = ApiEnvironmentPolicy(
            stagingCapable = true,
            stagingBaseUrl = staging,
            productionBaseUrl = production,
        )

        val configured = policy.configure(SimulaApiEnvironment.Staging, true)
        val initialized = policy.ensureDefault(false)

        assertEquals(ApiEnvironment.Staging, configured.configuration.environment)
        assertEquals(ApiEnvironment.Staging, initialized.environment)
        assertEquals(ApiEnvironment.Staging, policy.currentOrProduction().environment)
    }

    @Test
    fun `initialization selects staging directly from host metadata`() {
        val policy = ApiEnvironmentPolicy(
            stagingCapable = true,
            stagingBaseUrl = staging,
            productionBaseUrl = production,
        )

        assertEquals(ApiEnvironment.Staging, policy.ensureDefault(true).environment)
        assertEquals(ApiEnvironment.Staging, policy.effectiveEnvironmentOrProduction())
    }

    @Test
    fun `effective environment read does not freeze production before host default`() {
        val policy = ApiEnvironmentPolicy(
            stagingCapable = true,
            stagingBaseUrl = staging,
            productionBaseUrl = production,
        )

        assertEquals(ApiEnvironment.Production, policy.effectiveEnvironmentOrProduction())
        assertEquals(ApiEnvironment.Staging, policy.ensureDefault(true).environment)
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
