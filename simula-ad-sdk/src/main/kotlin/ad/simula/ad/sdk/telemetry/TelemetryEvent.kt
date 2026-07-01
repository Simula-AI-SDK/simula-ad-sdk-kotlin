package ad.simula.ad.sdk.telemetry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Telemetry event type discriminators (the wire `type` field). */
internal const val TYPE_NETWORK = "network"
internal const val TYPE_OPERATION = "operation"
internal const val TYPE_LIFECYCLE = "ad_lifecycle"
internal const val TYPE_ERROR = "error"
internal const val TYPE_META = "meta"

/**
 * A single telemetry datum. One flat, optional-field shape covers every [type] so the
 * batch is a homogeneous JSON array the backend can stream-parse on the `type`
 * discriminator (mirrors the existing best-effort tracking payloads, which also use
 * loosely-typed bodies).
 *
 * Durations ([durationMs]) are measured with a **monotonic** clock by the caller;
 * [timestamp] is wall-clock epoch millis. Never put PII in [name]/[message] — see
 * [TelemetryManager] for the sanitization + consent rules. [count] aggregates repeated
 * errors of the same signature. [eventId] is a per-event idempotency key (also used
 * internally to remove flushed events without positional races).
 */
@Serializable
internal data class TelemetryEvent(
    val type: String,
    val name: String,
    @SerialName("event_id") val eventId: String,
    val timestamp: Long,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("status_code") val statusCode: Int? = null,
    @SerialName("response_bytes") val responseBytes: Long? = null,
    @SerialName("request_bytes") val requestBytes: Long? = null,
    @SerialName("failure_class") val failureClass: String? = null,
    val success: Boolean? = null,
    @SerialName("ad_format") val adFormat: String? = null,
    @SerialName("ad_unit_id") val adUnitId: String? = null,
    @SerialName("ad_id") val adId: String? = null,
    @SerialName("serve_id") val serveId: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null,
    val breadcrumb: String? = null,
    // Symbolicated SDK frames for crash/exit errors (structured, alongside the compacted message).
    val stack: List<String>? = null,
    @SerialName("cache_hit") val cacheHit: Boolean? = null,
    @SerialName("retry_count") val retryCount: Int? = null,
    // Store-exit click type for store_opened/returned/abandoned: cta | store_prompt | auto_redirect.
    val trigger: String? = null,
    // Native load source for load_success: preload | cache | network.
    @SerialName("cache_source") val cacheSource: String? = null,
    // Wall-clock staleness, stamped at flush time = clock() - timestamp. Detects offline/queued events.
    @SerialName("event_age_ms") val eventAgeMs: Long? = null,
    // Mutable so an error signature seen repeatedly within a flush window aggregates
    // in place instead of flooding the buffer.
    var count: Int? = null,
)

/**
 * The batch wrapper: per-process/device context sent once, plus the [events] array.
 * Built fresh on each flush so the latest [sessionId] / consent-gated PII is attached.
 */
@Serializable
internal data class TelemetryEnvelope(
    @SerialName("sdk_version") val sdkVersion: String,
    val platform: String,
    @SerialName("os_version") val osVersion: String,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("host_app_id") val hostAppId: String,
    @SerialName("dev_mode") val devMode: Boolean,
    @SerialName("session_id") val sessionId: String? = null,
    // Consent-gated: only populated when the resolved ConsentSnapshot allows.
    @SerialName("primary_user_id") val primaryUserId: String? = null,
    @SerialName("advertising_id") val advertisingId: String? = null,
    // Resolved at flush time: wifi | cellular | none | unknown. Best-effort; never blocks.
    @SerialName("connection_type") val connectionType: String? = null,
    // Experiment assignment for per-variant conversion analysis (server-driven, set when an ad
    // resolves with experiment metadata). Session-scoped; last assignment wins.
    @SerialName("experiment_id") val experimentId: String? = null,
    @SerialName("variant_id") val variantId: String? = null,
    // Device/network diagnostics. Always-on (like device_model/connection_type), not consent-gated;
    // statics resolved at init, battery/carrier resolved best-effort at flush. Any field may be null.
    val manufacturer: String? = null,
    val locale: String? = null,
    @SerialName("device_ram_mb") val deviceRamMb: Long? = null,
    @SerialName("battery_level") val batteryLevel: Float? = null,
    @SerialName("battery_charging") val batteryCharging: Boolean? = null,
    val carrier: String? = null,
    val radio: String? = null,
    @SerialName("build_type") val buildType: String? = null,
    val events: List<TelemetryEvent>,
)
