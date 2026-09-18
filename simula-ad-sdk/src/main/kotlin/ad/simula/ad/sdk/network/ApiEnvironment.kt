package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.BuildConfig
import ad.simula.ad.sdk.ads.SimulaApiEnvironment
import android.content.Context
import android.content.pm.PackageManager
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

internal data class ApiEnvironmentResolverInputs(
    val stagingCapable: Boolean,
    val stagingBaseUrl: String,
    val stagingManifestValue: Any?,
)

internal fun resolveApiEnvironment(
    inputs: ApiEnvironmentResolverInputs,
    productionBaseUrl: String = ApiEnvironmentPolicy.PRODUCTION_BASE_URL,
): ApiEndpointConfiguration {
    val stagingEnabled =
        inputs.stagingCapable &&
            inputs.stagingBaseUrl.isNotBlank() &&
            (inputs.stagingManifestValue as? Boolean) == true
    return if (stagingEnabled) {
        ApiEndpointConfiguration(ApiEnvironment.Staging, inputs.stagingBaseUrl.trimEnd('/'))
    } else {
        ApiEndpointConfiguration(ApiEnvironment.Production, productionBaseUrl.trimEnd('/'))
    }
}

/** First request wins for the life of the process, preventing mixed production/staging traffic. */
internal class ApiEnvironmentPolicy(
    private val stagingCapable: Boolean,
    private val stagingBaseUrl: String,
    private val productionBaseUrl: String = PRODUCTION_BASE_URL,
) {
    private val frozen = AtomicReference<ApiEndpointConfiguration?>(null)

    /** Initialization derives its process-wide environment from host metadata. */
    fun ensureDefault(stagingManifestValue: Any?): ApiEndpointConfiguration {
        while (true) {
            frozen.get()?.let { return it }
            val hostDefault = resolvedConfiguration(stagingManifestValue)
            if (frozen.compareAndSet(null, hostDefault)) return hostDefault
        }
    }

    fun currentOrProduction(): ApiEndpointConfiguration = frozen.get() ?: ensureDefault(null)

    fun effectiveEnvironmentOrProduction(): ApiEnvironment =
        frozen.get()?.environment ?: ApiEnvironment.Production

    internal fun resolvedConfiguration(stagingManifestValue: Any?): ApiEndpointConfiguration = resolveApiEnvironment(
        inputs = ApiEnvironmentResolverInputs(
            stagingCapable = stagingCapable,
            stagingBaseUrl = stagingBaseUrl,
            stagingManifestValue = stagingManifestValue,
        ),
        productionBaseUrl = productionBaseUrl,
    )

    internal companion object {
        const val PRODUCTION_BASE_URL = "https://simula-api-701226639755.us-central1.run.app"
    }
}

internal object ProcessApiEnvironment {
    private val policy = ApiEnvironmentPolicy(
        stagingCapable = BuildConfig.SIMULA_STAGING_CAPABLE,
        stagingBaseUrl = BuildConfig.SIMULA_STAGING_BASE_URL,
    )
    fun ensureDefault(stagingManifestValue: Any?): ApiEndpointConfiguration =
        policy.ensureDefault(stagingManifestValue)

    val effectiveEnvironment: SimulaApiEnvironment
        get() = policy.effectiveEnvironmentOrProduction().toPublicEnvironment()

    /** A pre-initialization caller safely freezes production rather than allowing later traffic to split. */
    val current: ApiEndpointConfiguration get() = policy.currentOrProduction()
}

internal const val STAGING_ENVIRONMENT_METADATA = "SimulaStagingEnvironmentEnabled"

/** Performs the single bounded PackageManager metadata lookup allowed by pre-initialization config. */
internal fun readStagingEnvironmentManifestValue(context: Context): Any? {
    if (!BuildConfig.SIMULA_STAGING_CAPABLE) return null
    return runCatching {
        val appContext = context.applicationContext ?: context
        val metadata = appContext.packageManager
            .getApplicationInfo(appContext.packageName, PackageManager.GET_META_DATA)
            .metaData
        if (metadata?.containsKey(STAGING_ENVIRONMENT_METADATA) == true) {
            metadata.getBoolean(STAGING_ENVIRONMENT_METADATA, false)
        } else {
            null
        }
    }.getOrNull()
}

private fun ApiEnvironment.toPublicEnvironment(): SimulaApiEnvironment = when (this) {
    ApiEnvironment.Production -> SimulaApiEnvironment.Production
    ApiEnvironment.Staging -> SimulaApiEnvironment.Staging
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
