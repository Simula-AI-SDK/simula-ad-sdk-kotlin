package ad.simula.ad.sdk.telemetry

import ad.simula.ad.sdk.privacy.SimulaPrivacy
import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json

/**
 * SDK version stamped on every telemetry batch. Keep in sync with the `coordinates(...)`
 * version in `simula-ad-sdk/build.gradle.kts`.
 */
internal const val SIMULA_SDK_VERSION = "1.0.3"

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
     * is false nothing is created (host opt-out). [primaryUserId] is gated dynamically by the
     * live consent snapshot, so a later consent change is honored.
     */
    fun initialize(
        context: Context,
        apiKey: String,
        devMode: Boolean,
        enabled: Boolean,
        sessionIdProvider: () -> String?,
        primaryUserId: String?,
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
            store = SharedPrefsTelemetryStore(appCtx, json),
            sender = ApiTelemetrySender(apiKey),
            sessionIdProvider = sessionIdProvider,
            // Re-gate on every flush: ppid only with consent (& not under COPPA); the
            // advertising id is already nulled by the snapshot when not collectible.
            primaryUserIdProvider = { if (SimulaPrivacy.current.allowsPrimaryUserID) primaryUserId else null },
            advertisingIdProvider = { SimulaPrivacy.current.advertisingId },
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

    fun recordOperation(name: String, durationMs: Long, success: Boolean) =
        manager?.recordOperation(name, durationMs, success) ?: Unit

    fun recordLifecycle(
        stage: String,
        adFormat: String? = null,
        adUnitId: String? = null,
        adId: String? = null,
        serveId: String? = null,
        durationMs: Long? = null,
        errorCode: String? = null,
    ) = manager?.recordLifecycle(stage, adFormat, adUnitId, adId, serveId, durationMs, errorCode) ?: Unit

    fun recordError(signature: String, errorCode: String? = null, message: String? = null, breadcrumb: String? = null) =
        manager?.recordError(signature, errorCode, message, breadcrumb) ?: Unit

    /** Persist + attempt delivery now (e.g. app background). */
    fun flush() = manager?.flushNow() ?: Unit
}
