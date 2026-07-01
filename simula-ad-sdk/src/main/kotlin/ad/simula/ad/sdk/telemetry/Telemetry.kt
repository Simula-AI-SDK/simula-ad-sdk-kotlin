package ad.simula.ad.sdk.telemetry

import ad.simula.ad.sdk.image.ImageCache
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.network.SimulaConnectionType
import ad.simula.ad.sdk.privacy.SimulaPrivacy
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import java.util.Locale
import kotlinx.serialization.json.Json

/**
 * SDK version stamped on every telemetry batch. Keep in sync with the `coordinates(...)`
 * version in `simula-ad-sdk/build.gradle.kts`.
 */
internal const val SIMULA_SDK_VERSION = "1.1.1"

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
            // Always-on device diagnostics, resolved once (constant per process).
            manufacturer = Build.MANUFACTURER,
            locale = resolveLocale(),
            deviceRamMb = resolveRamMb(appCtx),
            buildType = if ((appCtx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) "debug" else "release",
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
            // Reads SimulaConnectionType's cached value (kept fresh by its own network callback —
            // see SimulaConnectionType.prime) instead of a fresh binder call per flush.
            connectionTypeProvider = { SimulaConnectionType.label },
            diagnosticsProvider = { resolveDiagnostics() },
            batteryProvider = { resolveBattery(appCtx) },
            carrierProvider = { resolveCarrier(appCtx) },
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

    fun recordError(
        signature: String,
        errorCode: String? = null,
        message: String? = null,
        breadcrumb: String? = null,
        stack: List<String>? = null,
    ) = manager?.recordError(signature, errorCode, message, breadcrumb, stack) ?: Unit

    /** Persist + attempt delivery now (e.g. app background). */
    fun flush() = manager?.flushNow() ?: Unit

    /** Record the session's experiment assignment (server-driven) for the telemetry envelope. */
    fun setExperiment(experimentId: String?, variantId: String?) =
        manager?.setExperiment(experimentId, variantId) ?: Unit

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

    /** Default locale as a BCP-47 tag (e.g. "en-US"). Best-effort; null on failure. */
    private fun resolveLocale(): String? = runCatching {
        Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() && it != "und" }
    }.getOrNull()

    /** Total physical RAM in MB. Best-effort; null when ActivityManager is unavailable. */
    private fun resolveRamMb(appCtx: Context): Long? = runCatching {
        val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@runCatching null
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (mi.totalMem / (1024L * 1024L)).takeIf { it > 0 }
    }.getOrNull()

    /**
     * Battery level (0..1) + charging, read from the sticky ACTION_BATTERY_CHANGED broadcast — no
     * receiver registered, no permission. Resolved at flush; null when unavailable.
     */
    private fun resolveBattery(appCtx: Context): BatteryInfo? = runCatching {
        val intent = appCtx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return@runCatching null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return@runCatching null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        BatteryInfo(level = level.toFloat() / scale.toFloat(), charging = charging)
    }.getOrNull()

    /**
     * Carrier name (needs no permission) + radio generation (needs host-declared READ_PHONE_STATE;
     * null otherwise). Best-effort; resolved at flush; null when nothing resolves / no SIM.
     */
    private fun resolveCarrier(appCtx: Context): CarrierInfo? = runCatching {
        val tm = appCtx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return@runCatching null
        val name = tm.networkOperatorName?.takeIf { it.isNotBlank() }
        val radio = resolveRadio(tm)
        if (name == null && radio == null) null else CarrierInfo(carrier = name, radio = radio)
    }.getOrNull()

    /** Coarse generation label for the current data network. Returns null without READ_PHONE_STATE
     *  (SecurityException) or on older APIs — the caller's runCatching absorbs it. */
    private fun resolveRadio(tm: TelephonyManager): String? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@runCatching null
        when (tm.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_CDMA -> "2G"
            else -> null
        }
    }.getOrNull()
}
