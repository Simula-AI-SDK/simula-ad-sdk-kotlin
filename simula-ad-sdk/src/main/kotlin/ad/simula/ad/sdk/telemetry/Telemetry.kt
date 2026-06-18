package ad.simula.ad.sdk.telemetry

import ad.simula.ad.sdk.image.ImageCache
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.privacy.SimulaPrivacy
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.serialization.json.Json

/**
 * SDK version stamped on every telemetry batch. Keep in sync with the `coordinates(...)`
 * version in `simula-ad-sdk/build.gradle.kts`.
 */
internal const val SIMULA_SDK_VERSION = "1.1.0"

/** logcat tag for the dev-mode telemetry mirror. */
private const val LOG_TAG = "SimulaTelemetry"

/**
 * Process-wide facade for in-house telemetry (handled errors + performance), mirroring the
 * singleton style of [SimulaPrivacy] / `RewardVerificationManager`. All record calls are
 * cheap no-ops until [initialize] installs a [TelemetryManager], and a true no-op forever
 * when the host opts out (`telemetryEnabled = false`) — the lowest-overhead path.
 *
 * The manager is decoupled from the network layer: [ad.simula.ad.sdk.network.SimulaHttp]
 * and the ad lifecycle just call these methods; this object owns the Android-specific
 * context + consent-gated PII wiring.
 */
internal object Telemetry {

    @Volatile
    private var manager: TelemetryManager? = null

    /**
     * Install the telemetry pipeline. Call once from `SimulaAds.initialize`. When [enabled]
     * is false nothing is created (host opt-out). [primaryUserIdProvider] is read live on every
     * flush so a mid-session `updatePrimaryUserID` is honored, and it is additionally gated by
     * the live consent snapshot.
     */
    fun initialize(
        context: Context,
        apiKey: String,
        devMode: Boolean,
        enabled: Boolean,
        sessionIdProvider: () -> String?,
        primaryUserIdProvider: () -> String?,
    ) {
        if (!enabled) {
            manager = null
            return
        }
        val appCtx = context.applicationContext
        val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        val ctx = TelemetryContext(
            sdkVersion = SIMULA_SDK_VERSION,
            osVersion = Build.VERSION.RELEASE ?: "",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            hostAppId = appCtx.packageName,
            devMode = devMode,
        )
        manager = TelemetryManager(
            ctx = ctx,
            store = SqliteTelemetryStore(appCtx, json),
            sender = ApiTelemetrySender(apiKey),
            sessionIdProvider = sessionIdProvider,
            // Re-gate on every flush: ppid only with consent (& not under COPPA); the
            // advertising id is already nulled by the snapshot when not collectible.
            primaryUserIdProvider = primaryUserIdProvider,
            advertisingIdProvider = { SimulaPrivacy.current.advertisingId },
            // Resolved fresh on each flush (off the UI path); best-effort, never throws.
            connectionTypeProvider = { resolveConnectionType(appCtx) },
            diagnosticsProvider = { resolveDiagnostics() },
            // In dev mode, mirror every (redacted) event to logcat for local verification.
            debugLog = if (devMode) { line -> Log.d(LOG_TAG, line) } else null,
        ).also { it.start() }
    }

    /** Apply a server-side directive (kill-switch / sampling) from `/session/create`. */
    fun applyServerConfig(enabled: Boolean, sampleRate: Double) {
        manager?.applyServerConfig(enabled, sampleRate)
    }

    fun recordNetwork(
        path: String,
        method: String,
        statusCode: Int?,
        durationMs: Long,
        requestBytes: Long,
        responseBytes: Long,
        failureClass: String?,
    ) = manager?.recordNetwork(path, method, statusCode, durationMs, requestBytes, responseBytes, failureClass) ?: Unit

    fun recordOperation(
        name: String,
        durationMs: Long,
        success: Boolean,
        failureClass: String? = null,
        breadcrumb: String? = null,
    ) = manager?.recordOperation(name, durationMs, success, failureClass, breadcrumb) ?: Unit

    fun recordLifecycle(
        stage: String,
        adFormat: String? = null,
        adUnitId: String? = null,
        adId: String? = null,
        serveId: String? = null,
        durationMs: Long? = null,
        errorCode: String? = null,
        trigger: String? = null,
        cacheSource: String? = null,
        breadcrumb: String? = null,
    ) = manager?.recordLifecycle(stage, adFormat, adUnitId, adId, serveId, durationMs, errorCode, trigger, cacheSource, breadcrumb) ?: Unit

    fun recordError(signature: String, errorCode: String? = null, message: String? = null, breadcrumb: String? = null) =
        manager?.recordError(signature, errorCode, message, breadcrumb) ?: Unit

    /** Persist + attempt delivery now (e.g. app background). */
    fun flush() = manager?.flushNow() ?: Unit

    /** Record the session's experiment assignment (server-driven) for the telemetry envelope. */
    fun setExperiment(experimentId: String?, variantId: String?) =
        manager?.setExperiment(experimentId, variantId) ?: Unit

    /**
     * Best-effort connection class for the envelope, resolved at flush time. Returns
     * `wifi` / `cellular` / `none` / `unknown`; any failure (missing permission, null
     * service, old API) degrades to `unknown` and never throws. Requires
     * `ACCESS_NETWORK_STATE` (a normal, install-time permission) to return anything but `unknown`.
     */
    private fun resolveConnectionType(appCtx: Context): String = runCatching {
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching "unknown"
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?: return@runCatching "none"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "unknown"
        }
    }.getOrDefault("unknown")

    /**
     * Best-effort runtime diagnostics breadcrumb for the periodic `diagnostics` event: JVM heap usage,
     * the image-cache entry count, and the pooled-WebView count. Wrapped so any failure degrades to
     * null (no event) and never throws. Heap figures are MB; the rest are counts.
     */
    private fun resolveDiagnostics(): String? = runCatching {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
        val maxMb = rt.maxMemory() / (1024L * 1024L)
        "mem_used_mb=$usedMb;mem_max_mb=$maxMb;img_cache=${ImageCache.cacheSize()};wv_pool=${WebViewPool.pooledCount}"
    }.getOrNull()
}
