package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.BuildConfig
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class ApiEnvironment {
    Production,
    Staging,
}

internal data class ApiEndpointConfiguration(
    val environment: ApiEnvironment,
    val baseUrl: String,
) {
    fun url(path: String): String = apiUrl(baseUrl, path)

    fun storageName(productionName: String): String = environment.storageName(productionName)
}

internal data class ApiEnvironmentFreezeResult(
    val configuration: ApiEndpointConfiguration,
    val conflictsWithFrozenEnvironment: Boolean,
)

/** First request wins for the life of the process, preventing mixed production/staging traffic. */
internal class ApiEnvironmentPolicy(
    private val stagingCapable: Boolean,
    private val stagingBaseUrl: String,
    private val productionBaseUrl: String = PRODUCTION_BASE_URL,
) {
    private val frozen = AtomicReference<ApiEndpointConfiguration?>(null)

    fun freeze(devMode: Boolean): ApiEnvironmentFreezeResult {
        val requested = requestedConfiguration(devMode)
        while (true) {
            val existing = frozen.get()
            if (existing != null) {
                return ApiEnvironmentFreezeResult(existing, requested.environment != existing.environment)
            }
            if (frozen.compareAndSet(null, requested)) {
                return ApiEnvironmentFreezeResult(requested, false)
            }
        }
    }

    fun currentOrProduction(): ApiEndpointConfiguration =
        frozen.get() ?: freeze(devMode = false).configuration

    internal fun requestedConfiguration(devMode: Boolean): ApiEndpointConfiguration =
        if (devMode && stagingCapable && stagingBaseUrl.isNotBlank()) {
            ApiEndpointConfiguration(ApiEnvironment.Staging, stagingBaseUrl.trimEnd('/'))
        } else {
            ApiEndpointConfiguration(ApiEnvironment.Production, productionBaseUrl.trimEnd('/'))
        }

    internal companion object {
        const val PRODUCTION_BASE_URL = "https://simula-api-701226639755.us-central1.run.app"
    }
}

internal object ProcessApiEnvironment {
    private val policy = ApiEnvironmentPolicy(
        stagingCapable = BuildConfig.SIMULA_STAGING_CAPABLE,
        stagingBaseUrl = BuildConfig.SIMULA_STAGING_BASE_URL,
    )
    private val conflictWarned = AtomicBoolean(false)

    fun freeze(devMode: Boolean): ApiEndpointConfiguration {
        val result = policy.freeze(devMode)
        if (result.conflictsWithFrozenEnvironment && conflictWarned.compareAndSet(false, true)) {
            runCatching {
                Log.w(
                    "SimulaAdSDK",
                    "Ignoring a conflicting devMode API environment; the first process environment remains active.",
                )
            }
        }
        return result.configuration
    }

    /** A pre-initialization caller safely freezes production rather than allowing later traffic to split. */
    val current: ApiEndpointConfiguration get() = policy.currentOrProduction()
}

internal fun apiUrl(baseUrl: String, path: String): String =
    baseUrl.trimEnd('/') + "/" + path.trimStart('/')

internal fun ApiEnvironment.storageName(productionName: String): String {
    if (this == ApiEnvironment.Production) return productionName
    val extensionStart = productionName.lastIndexOf('.').takeIf { it > 0 }
    return if (extensionStart == null) {
        "${productionName}_staging"
    } else {
        productionName.substring(0, extensionStart) + "_staging" + productionName.substring(extensionStart)
    }
}
