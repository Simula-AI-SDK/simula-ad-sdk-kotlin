package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.model.AdBehavior
import ad.simula.ad.sdk.model.AdUnitType
import ad.simula.ad.sdk.model.AutoStoreRedirect
import ad.simula.ad.sdk.model.AutoStoreRedirectTrigger
import ad.simula.ad.sdk.model.CloseAction
import ad.simula.ad.sdk.model.CloseBehavior
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.CloseTreatment
import ad.simula.ad.sdk.model.Creative
import ad.simula.ad.sdk.model.CreativeType
import ad.simula.ad.sdk.model.DEFAULT_FALLBACK_CLOSE_DELAY_SECONDS
import ad.simula.ad.sdk.model.Experiment
import ad.simula.ad.sdk.model.MAX_CLOSE_DELAY_SECONDS
import ad.simula.ad.sdk.model.MAX_CONTRACT_2_SK_OVERLAY_DELAY_SECONDS
import ad.simula.ad.sdk.model.MAX_SK_OVERLAY_DELAY_SECONDS
import ad.simula.ad.sdk.model.OverlayPosition
import ad.simula.ad.sdk.model.OverlayTiming
import ad.simula.ad.sdk.model.ProgressBarBehavior
import ad.simula.ad.sdk.model.ProgressBarStyle
import ad.simula.ad.sdk.model.RewardBehavior
import ad.simula.ad.sdk.model.RewardEarnAt
import ad.simula.ad.sdk.model.SkOverlayConfig
import ad.simula.ad.sdk.model.StoreOpen
import ad.simula.ad.sdk.model.StorePrompt
import ad.simula.ad.sdk.model.StorePromptPlatform
import ad.simula.ad.sdk.model.VideoBehavior
import ad.simula.ad.sdk.model.VideoChromeStyle
import ad.simula.ad.sdk.model.VideoSegment
import ad.simula.ad.sdk.model.fallbackCloseTreatment
import ad.simula.ad.sdk.model.validatedHexColor
import ad.simula.ad.sdk.model.admittedRemoteAssetUrl
import ad.simula.ad.sdk.telemetry.Telemetry
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal object LenientNullableBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientNullableBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean? {
        val jsonDecoder = decoder as? JsonDecoder ?: return runCatching { decoder.decodeBoolean() }.getOrNull()
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return null
        return primitive.takeUnless(JsonPrimitive::isString)?.booleanOrNull
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) encoder.encodeNull() else encoder.encodeBoolean(value)
    }
}

internal object LenientNullableIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientNullableInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int? {
        val jsonDecoder = decoder as? JsonDecoder ?: return runCatching { decoder.decodeInt() }.getOrNull()
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return null
        return primitive.takeUnless(JsonPrimitive::isString)?.intOrNull
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}

internal object LenientNullableStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientNullableString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return runCatching { decoder.decodeString() }.getOrNull()
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return null
        return primitive.takeIf(JsonPrimitive::isString)?.content
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

internal object LenientNullableExperimentSerializer : KSerializer<ApiExperiment?> {
    override val descriptor: SerialDescriptor = ApiExperiment.serializer().descriptor

    override fun deserialize(decoder: Decoder): ApiExperiment? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return runCatching { decoder.decodeSerializableValue(ApiExperiment.serializer()) }.getOrNull()
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonObject) return null
        return runCatching {
            jsonDecoder.json.decodeFromJsonElement(ApiExperiment.serializer(), element)
        }.getOrNull()
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: ApiExperiment?) {
        if (value == null) encoder.encodeNull()
        else encoder.encodeSerializableValue(ApiExperiment.serializer(), value)
    }
}

internal object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder ?: return runCatching { decoder.decodeInt() }.getOrDefault(0)
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return 0
        return primitive.takeUnless(JsonPrimitive::isString)?.intOrNull ?: 0
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

@Serializable
internal data class SessionResponse(
    @SerialName("sessionId") val sessionId: String? = null,
    // Optional server-side telemetry directive: a runtime kill-switch + perf sampling rate,
    // letting telemetry volume be dialed without an SDK release. Absent → SDK defaults apply.
    @SerialName("telemetry_enabled") val telemetryEnabled: Boolean? = null,
    @SerialName("telemetry_sample_rate") val telemetrySampleRate: Double? = null,
)

/**
 * Structured error body the backend returns on a 4xx for the load endpoints:
 * `{"code": "...", "message": "..."}`. The stable `code` is the SDK-facing contract
 * (HTTP status alone is ambiguous); `ad_unit_not_found` means the publisher doesn't own
 * the requested ad unit id.
 */
@Serializable
internal data class ApiErrorResponse(
    val code: String = "",
    val message: String = "",
)

/** Response body for `GET /frequency-cap/status`: `{"capped": true|false}`. Defaults to `false`
 * (not capped) so a malformed/partial payload never falsely hides an ad surface. */
@Serializable
internal data class FrequencyCapResponse(
    val capped: Boolean = false,
)

@Serializable
internal data class InitMinigameRequestBody(
    @SerialName("game_type") val gameType: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("conv_id") val convId: String? = null,
    @SerialName("currency_mode") val currencyMode: Boolean = false,
    val w: Int,
    val h: Int,
    @SerialName("char_id") val charId: String? = null,
    @SerialName("char_name") val charName: String? = null,
    @SerialName("char_image") val charImage: String? = null,
    @SerialName("char_desc") val charDesc: String? = null,
    val messages: List<MessageBody>? = null,
    @SerialName("delegate_char") val delegateChar: Boolean = true,
    @SerialName("menu_id") val menuId: String? = null,
)

@Serializable
internal data class CharacterSelectorRequestBody(
    @SerialName("session_id") val sessionId: String,
    val fill: Int,
)

@Serializable
internal data class MessageBody(
    val role: String,
    val content: String,
)

@Serializable
internal data class MinigameApiResponse(
    val adType: String? = null,
    val adInserted: Boolean = false,
    val adResponse: AdResponseBody? = null,
)

@Serializable
internal data class AdResponseBody(
    @SerialName("ad_id") val adId: String? = null,
    // The minigame serve id — the handle for `GET /load/fallbacks/{impression_id}`.
    @SerialName("serve_id") val serveId: String? = null,
    @SerialName("iframe_url") val iframeUrl: String? = null,
)

/** Payload from `GET /load/fallbacks/{impression_id}` — every ad screen linked to the serve
 * (campaign creative, then the "Get the App" end screen) in reveal order. */
@Serializable
internal data class FallbackAdsApiResponse(
    @SerialName("impression_id") val impressionId: String = "",
    @SerialName("native_click_beacon_v1_enabled")
    @Serializable(with = LenientNullableBooleanSerializer::class)
    val nativeClickBeaconV1Enabled: Boolean? = null,
    @SerialName("video_contract") @Serializable(with = LenientNullableIntSerializer::class)
    val videoContract: Int? = null,
    @Serializable(with = LossyFallbackAdBodiesSerializer::class)
    val ads: List<FallbackAdBody> = emptyList(),
)

@Serializable
internal data class FallbackAdBody(
    // Each screen carries its own ad id (the per-screen impression for report/tracking).
    @SerialName("ad_id") val adId: String = "",
    @SerialName("native_click_beacon_v1_enabled")
    @Serializable(with = LenientNullableBooleanSerializer::class)
    val nativeClickBeaconV1Enabled: Boolean? = null,
    val type: String? = null,
    val creative: ApiCreative? = null,
    @SerialName("rendered_html") val renderedHtml: String? = null,
    // Shipped fallback payloads used `html`; keep decode-only compatibility while preferring rendered_html.
    val html: String? = null,
    val url: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @Serializable(with = LenientNullableStringSerializer::class)
    val cta: String? = null,
    @SerialName("app_icon_url")
    @Serializable(with = LenientNullableStringSerializer::class)
    val appIconUrl: String? = null,
    @SerialName("app_name")
    @Serializable(with = LenientNullableStringSerializer::class)
    val appName: String? = null,
    @Serializable(with = LenientNullableStringSerializer::class)
    val subtitle: String? = null,
    @SerialName("video_pool")
    @Serializable(with = LenientNullableStringSerializer::class)
    val videoPool: String? = null,
    @Serializable(with = LenientNullableStringSerializer::class)
    val pool: String? = null,
    @SerialName("clip_index")
    @Serializable(with = LenientNullableIntSerializer::class)
    val clipIndex: Int? = null,
    @Serializable(with = LenientNullableStringSerializer::class)
    val destination: String? = null,
    @SerialName("tracking_url")
    @Serializable(with = LenientNullableStringSerializer::class)
    val trackingUrl: String? = null,
    @SerialName("android_store_url")
    @Serializable(with = LenientNullableStringSerializer::class)
    val androidStoreUrl: String? = null,
    @SerialName("ios_store_url")
    @Serializable(with = LenientNullableStringSerializer::class)
    val iosStoreUrl: String? = null,
    // Keep fallback behavior raw so malformed or partial close config cannot drop the screen.
    @SerialName("ad_behavior") val adBehavior: JsonElement? = null,
    @Transient val sourceIndex: Int = -1,
    @Transient val routingFieldsPresent: Boolean = listOf(
        trackingUrl,
        androidStoreUrl,
    ).any { !it.isNullOrBlank() },
)

internal object LossyFallbackAdBodiesSerializer : KSerializer<List<FallbackAdBody>> {
    private val delegate = ListSerializer(FallbackAdBody.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<FallbackAdBody> {
        val jsonDecoder = decoder as? JsonDecoder
        if (jsonDecoder == null) {
            return decoder.decodeSerializableValue(delegate).mapIndexed { index, item ->
                item.copy(sourceIndex = index)
            }
        }
        val array = jsonDecoder.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapIndexedNotNull { index, element ->
            runCatching {
                val body = jsonDecoder.json.decodeFromJsonElement(FallbackAdBody.serializer(), element)
                body.copy(
                    sourceIndex = index,
                    routingFieldsPresent = (element as? JsonObject)?.hasPresentRoutingField() == true,
                )
            }.getOrNull()
        }
    }

    override fun serialize(encoder: Encoder, value: List<FallbackAdBody>) {
        val jsonEncoder = encoder as? JsonEncoder
        if (jsonEncoder == null) {
            encoder.encodeSerializableValue(delegate, value)
            return
        }
        jsonEncoder.encodeJsonElement(
            JsonArray(value.map { jsonEncoder.json.encodeToJsonElement(FallbackAdBody.serializer(), it) }),
        )
    }
}

private fun JsonObject.hasPresentRoutingField(): Boolean = listOf(
    "tracking_url",
    "android_store_url",
).any { key ->
    when (val value = this[key]) {
        null, JsonNull -> false
        is JsonPrimitive -> !value.isString || !value.content.isBlank()
        else -> true
    }
}

@Serializable
internal data class MenuGameClickBody(
    @SerialName("menu_id") val menuId: String,
    @SerialName("game_name") val gameName: String,
)

@Serializable
internal data class AdLoadRequestBody(
    @SerialName("ad_unit_id") val adUnitId: String,
    @SerialName("session_id") val sessionId: String = "",
    // Optional character context the backend can use to target the creative.
    @SerialName("char_id") val charId: String? = null,
    @SerialName("char_name") val charName: String? = null,
    @SerialName("char_image") val charImage: String? = null,
    @SerialName("char_desc") val charDesc: String? = null,
    // Contextual targeting signals (category, tags, customContext, …) — the same `NativeContext` the
    // native surface sends, now extended to the full-screen formats so they get character-aware
    // targeting too. Null omits the key (the backend treats it as no context).
    val context: NativeContextBody? = null,
    val metadata: Map<String, String>? = null,
    val contracts: Map<String, Int> = mapOf("video" to 2),
    // Device capability snapshot so the backend never assigns an unsupported variant. Defaults to a
    // neutral value (no framework access) so pure-JVM tests can construct this; the ad path injects
    // the real values via `currentDeviceCapabilities()`.
    val capabilities: ApiDeviceCapabilities = ApiDeviceCapabilities(),
)

@Serializable
internal data class AdLoadApiResponse(
    // The impression (minigame serve) id — the SDK's single handle for fallbacks,
    // tracking and reporting. Nullable: the server sends an explicit null on a no-fill.
    @SerialName("impression_id") val impressionId: String? = null,
    @SerialName("ad_inserted") val adInserted: Boolean = false,
    @SerialName("ad_unit_id") val adUnitId: String = "",
    val destination: String = "appstore",
    @SerialName("rendered_format") val renderedFormat: String? = null,
    @SerialName("tracking_url") val trackingUrl: String? = null,
    @SerialName("impression_url") @Serializable(with = LenientNullableStringSerializer::class)
    val impressionUrl: String? = null,
    // Raw, unwrapped Play Store link for the advertised app — distinct from the
    // attribution-wrapped tracking_url. The deterministic CTA fallback: opened when the tracker
    // is missing or can't be launched, so the CTA still lands on the store. Null when the
    // campaign has no raw store link.
    @SerialName("android_store_url") val androidStoreUrl: String? = null,
    // Cross-platform load flag. Android carries it for wire parity; StoreKit product prewarming is
    // currently consumed by the iOS SDK only. Missing payloads must remain cold.
    @SerialName("prewarm_sk_product") val prewarmSkProduct: Boolean = false,
    // Server-rendered HTML creative. Interstitials intentionally do not support iframe_url.
    @SerialName("rendered_html") val renderedHtml: String? = null,
    // Cleared bid (the estimated CPM) for this serve, backend-provided. The SDK derives the
    // `adValue` from it (see [ad.simula.ad.sdk.model.AdValue.fromBidCpm]) and surfaces it on the paid
    // event. Defaults to 0.0 → a $0 estimate when the field is absent (e.g. a no-fill).
    @SerialName("bid_amt") val bidAmt: Double = 0.0,
    // Null when the payload omits `ad_behavior` — the renderer falls back to today's defaults.
    @SerialName("ad_behavior") val adBehavior: ApiAdBehavior? = null,
    val creative: ApiCreative? = null,
    @Serializable(with = LenientNullableExperimentSerializer::class)
    val experiment: ApiExperiment? = null,
    @SerialName("video_contract") @Serializable(with = LenientNullableIntSerializer::class)
    val videoContract: Int? = null,
)

// ── Capability handshake ──────────────────────────────────────────────────────

@Serializable
internal data class ApiDeviceCapabilities(
    @SerialName("os_version") val osVersion: String = "",
    @SerialName("api_level") val apiLevel: Int = 0,
    @SerialName("play_services_available") val playServicesAvailable: Boolean = false,
    @SerialName("install_referrer_available") val installReferrerAvailable: Boolean = false,
    // Declares SDK support only. The fallback response separately grants native beacon ownership.
    @SerialName("native_click_beacon_v1") val nativeClickBeaconV1: Boolean = true,
)

/** Reads the running device's capabilities (Android framework). Called from the ad path only —
 * never from pure-JVM parsing tests — so the `Build` access here stays out of those tests. */
internal fun currentDeviceCapabilities(): ApiDeviceCapabilities = ApiDeviceCapabilities(
    osVersion = android.os.Build.VERSION.RELEASE ?: "",
    apiLevel = android.os.Build.VERSION.SDK_INT,
    // Play Install Prompt requires API 21+; refine with a GoogleApiAvailability check if the dep is present.
    playServicesAvailable = android.os.Build.VERSION.SDK_INT >= 21,
    installReferrerAvailable = android.os.Build.VERSION.SDK_INT >= 21,
    nativeClickBeaconV1 = true,
)

// ── Ad behavior (server-driven A/B render config) ─────────────────────────────

@Serializable
internal data class ApiAdBehavior(
    val close: ApiCloseBehavior? = null,
    @SerialName("store_open") val storeOpen: String? = null,
    @SerialName("store_prompt") val storePrompt: ApiStorePrompt? = null,
    val skoverlay: ApiSkOverlay? = null,
    @SerialName("auto_store_redirect") val autoStoreRedirect: ApiAutoStoreRedirect? = null,
    val video: ApiVideoBehavior? = null,
    val reward: JsonElement? = null,
    @SerialName("progress_bar") val progressBar: JsonElement? = null,
)

@Serializable
internal data class ApiCloseBehavior(
    @Serializable(with = LenientIntSerializer::class)
    @SerialName("delay_seconds") val delaySeconds: Int = 0,
    @Serializable(with = LenientNullableStringSerializer::class)
    val treatment: String? = null,
    @Serializable(with = LenientNullableStringSerializer::class)
    val position: String? = null,
    @Serializable(with = LenientNullableStringSerializer::class)
    val action: String? = null,
    @Serializable(with = LenientNullableStringSerializer::class)
    @SerialName("progress_bar_color") val progressBarColor: String? = null,
)

@Serializable
internal data class ApiCreative(
    val type: String? = null,
    @SerialName("bundle_url") val bundleUrl: String? = null,
    val url: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("ad_unit_type") val adUnitType: String? = null,
    @Serializable(with = LenientNullableStringSerializer::class)
    val cta: String? = null,
    @SerialName("app_icon_url")
    @Serializable(with = LenientNullableStringSerializer::class)
    val appIconUrl: String? = null,
    @SerialName("app_name")
    @Serializable(with = LenientNullableStringSerializer::class)
    val appName: String? = null,
    @Serializable(with = LenientNullableStringSerializer::class)
    val subtitle: String? = null,
    @SerialName("video_pool")
    @Serializable(with = LenientNullableStringSerializer::class)
    val videoPool: String? = null,
    @Serializable(with = LenientNullableStringSerializer::class)
    val pool: String? = null,
    @SerialName("clip_index")
    @Serializable(with = LenientNullableIntSerializer::class)
    val clipIndex: Int? = null,
    @Serializable(with = LossyVideoSegmentsSerializer::class)
    val segments: List<ApiVideoSegment> = emptyList(),
)

@Serializable
internal data class ApiVideoSegment(
    @SerialName("clip_index") @Serializable(with = LenientIntSerializer::class)
    val clipIndex: Int = -1,
    @SerialName("video_pool") @Serializable(with = LenientNullableStringSerializer::class)
    val videoPool: String? = null,
    @SerialName("start_seconds") val startSeconds: Double = Double.NaN,
    @SerialName("end_seconds") val endSeconds: Double = Double.NaN,
)

internal object LossyVideoSegmentsSerializer : KSerializer<List<ApiVideoSegment>> {
    private val delegate = ListSerializer(ApiVideoSegment.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<ApiVideoSegment> {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeSerializableValue(delegate)
        val array = jsonDecoder.decodeJsonElement() as? JsonArray ?: return emptyList()
        if (array.size > 3) return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val index = (obj["clip_index"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
                ?: return@mapNotNull null
            val pool = (obj["video_pool"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val start = (obj["start_seconds"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull
                ?: return@mapNotNull null
            val end = (obj["end_seconds"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull
                ?: return@mapNotNull null
            ApiVideoSegment(index, pool, start, end)
        }
    }

    override fun serialize(encoder: Encoder, value: List<ApiVideoSegment>) =
        encoder.encodeSerializableValue(delegate, value)
}

@Serializable
internal data class ApiVideoBehavior(
    @Serializable(with = LenientNullableStringSerializer::class)
    val style: String? = null,
)

@Serializable
internal data class ApiExperiment(
    @SerialName("experiment_id") val experimentId: String? = null,
    @SerialName("variant_id") val variantId: String? = null,
    val layer: String? = null,
)

@Serializable
internal data class ApiStorePrompt(
    val enabled: Boolean = false,
    val trigger: String = "midpoint",
    val position: String? = null,
    val platform: String? = null,
)

@Serializable
internal data class ApiSkOverlay(
    @Serializable(with = LenientNullableBooleanSerializer::class)
    val enabled: Boolean? = null,
    val timing: String? = null,
    @SerialName("delay_seconds")
    @Serializable(with = LenientNullableIntSerializer::class)
    val delaySeconds: Int? = null,
    val position: String? = null,
    val dismissible: Boolean = true,
)

@Serializable
internal data class ApiAutoStoreRedirect(
    val enabled: Boolean = false,
    val trigger: String? = null,
)

/** Maps the wire DTO to the domain model, normalizing enum strings. A null DTO (absent
 * `ad_behavior`) stays null so callers can preserve today's literal behavior. */
internal fun ApiAdBehavior?.toDomain(videoContract2: Boolean = false): AdBehavior? {
    if (this == null) return null
    return AdBehavior(
        close = close.toDomain(),
        storeOpen = StoreOpen.from(storeOpen),
        storePrompt = storePrompt.toDomain(),
        skoverlay = skoverlay.toDomain(videoContract2),
        autoStoreRedirect = autoStoreRedirect.toDomain(),
        video = video.toDomain(),
        reward = (reward as? JsonObject)?.let { value ->
            val earnAt = (value["earn_at"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            RewardBehavior(RewardEarnAt.from(earnAt))
        },
        progressBar = ProgressBarBehavior(
            ProgressBarStyle.from(
                ((progressBar as? JsonObject)?.get("style") as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)?.content,
            ),
        ),
    )
}

internal fun ApiCloseBehavior?.toDomain(): CloseBehavior {
    if (this == null) return CloseBehavior()
    // Every treatment honors the configured corner. (`progress_bar` renders its bar at the top edge
    // regardless; only its resolved close ✕ follows `position`.)
    return CloseBehavior(
        // Clamp to [0, MAX] so a bad/oversized value can't trap the user behind a blocked close.
        delaySeconds = delaySeconds.coerceIn(0, MAX_CLOSE_DELAY_SECONDS),
        treatment = CloseTreatment.from(treatment),
        position = ClosePosition.from(position),
        action = CloseAction.from(action),
        progressBarColor = validatedHexColor(progressBarColor),
    )
}

/** Resolves fallback close chrome independently from primary behavior defaults. All malformed,
 * missing, null, or partial fields fail open to the fallback contract's bounded defaults. */
internal fun fallbackCloseBehavior(adBehavior: JsonElement?): CloseBehavior {
    val close = (adBehavior as? JsonObject)?.get("close") as? JsonObject
        ?: return CloseBehavior(
            delaySeconds = DEFAULT_FALLBACK_CLOSE_DELAY_SECONDS,
            treatment = CloseTreatment.COUNTDOWN_CIRCLE,
        )
    val delayPrimitive = close["delay_seconds"] as? JsonPrimitive
    val delay = delayPrimitive
        ?.takeUnless(JsonPrimitive::isString)
        ?.longOrNull
        ?: DEFAULT_FALLBACK_CLOSE_DELAY_SECONDS.toLong()
    fun stringValue(key: String): String? =
        (close[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    return CloseBehavior(
        delaySeconds = delay.coerceIn(0L, MAX_CLOSE_DELAY_SECONDS.toLong()).toInt(),
        treatment = fallbackCloseTreatment(stringValue("treatment")),
        position = ClosePosition.from(stringValue("position")),
        action = CloseAction.from(stringValue("action")),
        progressBarColor = validatedHexColor(stringValue("progress_bar_color")),
    )
}

internal fun ApiCreative?.toDomain(): Creative? {
    if (this == null) return null
    return Creative(
        type = CreativeType.from(type),
        bundleUrl = bundleUrl,
        url = url,
        posterUrl = posterUrl,
        adUnitType = AdUnitType.from(adUnitType),
        cta = cta?.trim()?.takeIf { it.isNotEmpty() },
        appIconUrl = admittedRemoteAssetUrl(appIconUrl),
        appName = appName?.trim()?.takeIf { it.isNotEmpty() },
        subtitle = subtitle?.trim()?.takeIf { it.isNotEmpty() },
        videoPool = (videoPool ?: pool)?.trim()?.takeIf { it.length in 1..64 },
        clipIndex = clipIndex?.takeIf { it in 0..2 },
        segments = validatedVideoSegments(segments),
    )
}

internal fun validatedVideoSegments(segments: List<ApiVideoSegment>): List<VideoSegment> {
    if (segments.isEmpty() || segments.size > 3) return emptyList()
    var expectedStart = 0.0
    val validated = ArrayList<VideoSegment>(segments.size)
    segments.forEachIndexed { expectedIndex, segment ->
        val pool = segment.videoPool?.trim()?.takeIf { it.length in 1..64 } ?: return emptyList()
        if (segment.clipIndex != expectedIndex || !segment.startSeconds.isFinite() ||
            !segment.endSeconds.isFinite() || kotlin.math.abs(segment.startSeconds - expectedStart) > 0.001 ||
            segment.endSeconds <= segment.startSeconds
        ) return emptyList()
        validated += VideoSegment(
            clipIndex = segment.clipIndex,
            videoPool = pool,
            // Accepted tolerance is transport noise only; attribution uses the exact prior boundary.
            startSeconds = expectedStart,
            endSeconds = segment.endSeconds,
        )
        expectedStart = segment.endSeconds
    }
    return validated
}

internal fun ApiVideoBehavior?.toDomain(): VideoBehavior? {
    if (this == null) return null
    return VideoBehavior(
        style = VideoChromeStyle.from(style),
    )
}

internal fun fallbackVideoBehavior(adBehavior: JsonElement?): VideoBehavior? {
    val video = (adBehavior as? JsonObject)?.get("video") as? JsonObject ?: return null
    val rawStyle = (video["style"] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
    return VideoBehavior(
        style = VideoChromeStyle.from(rawStyle),
    )
}

internal fun fallbackSkOverlayConfig(adBehavior: JsonElement?, videoContract2: Boolean): SkOverlayConfig? {
    val overlay = (adBehavior as? JsonObject)?.get("skoverlay") as? JsonObject
    if (overlay == null && !videoContract2) return null
    fun stringValue(key: String): String? = (overlay?.get(key) as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
    val enabled = (overlay?.get("enabled") as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.booleanOrNull
        ?: false
    val parsedDelay = (overlay?.get("delay_seconds") as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.intOrNull
    val delay = if (videoContract2) {
        parsedDelay?.takeIf { it in 0..MAX_CONTRACT_2_SK_OVERLAY_DELAY_SECONDS } ?: 3
    } else {
        clampSkOverlayDelaySeconds(parsedDelay ?: 0)
    }
    val dismissible = (overlay?.get("dismissible") as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.booleanOrNull
        ?: true
    return SkOverlayConfig(
        enabled = enabled,
        timing = OverlayTiming.from(stringValue("timing")),
        delaySeconds = delay,
        position = OverlayPosition.from(stringValue("position")),
        dismissible = dismissible,
    )
}

internal fun ApiExperiment?.toDomain(): Experiment? {
    if (this == null) return null
    return Experiment(experimentId = experimentId, variantId = variantId, layer = layer)
}

internal fun ApiStorePrompt?.toDomain(): StorePrompt? {
    if (this == null) return null
    return StorePrompt(
        enabled = enabled,
        trigger = trigger,
        position = ClosePosition.from(position),
        platform = StorePromptPlatform.from(platform),
    )
}

internal fun ApiSkOverlay?.toDomain(videoContract2: Boolean = false): SkOverlayConfig? {
    if (this == null) return null
    return SkOverlayConfig(
        enabled = enabled ?: false,
        timing = OverlayTiming.from(timing),
        delaySeconds = if (videoContract2) {
            delaySeconds?.takeIf { it in 0..MAX_CONTRACT_2_SK_OVERLAY_DELAY_SECONDS } ?: 3
        } else {
            clampSkOverlayDelaySeconds(delaySeconds ?: 0)
        },
        position = OverlayPosition.from(position),
        dismissible = dismissible,
    )
}

internal fun clampSkOverlayDelaySeconds(
    delaySeconds: Int,
    recordClamp: () -> Unit = {
        Telemetry.recordOperation(
            name = "skoverlay_delay_clamped",
            durationMs = 0L,
            success = false,
            failureClass = "out_of_range",
        )
    },
): Int {
    val clamped = delaySeconds.coerceIn(0, MAX_SK_OVERLAY_DELAY_SECONDS)
    if (clamped != delaySeconds) runCatching(recordClamp)
    return clamped
}

internal fun ApiAutoStoreRedirect?.toDomain(): AutoStoreRedirect? {
    if (this == null) return null
    return AutoStoreRedirect(
        enabled = enabled,
        trigger = AutoStoreRedirectTrigger.from(trigger),
    )
}

// ── Rewarded minigame (init / verify) ─────────────────────────────────────────

@Serializable
internal data class RewardedInitRequestBody(
    @SerialName("ad_unit_id") val adUnitId: String,
    @SerialName("session_id") val sessionId: String = "",
    // Optional character context the backend can use to target the minigame.
    @SerialName("char_id") val charId: String? = null,
    @SerialName("char_name") val charName: String? = null,
    @SerialName("char_image") val charImage: String? = null,
    @SerialName("char_desc") val charDesc: String? = null,
    // Contextual targeting signals — see [AdLoadRequestBody.context]. Extended to rewarded so the
    // full-screen formats target the same way native does.
    val context: NativeContextBody? = null,
    val metadata: Map<String, String>? = null,
    val contracts: Map<String, Int> = mapOf("video" to 2),
    val capabilities: ApiDeviceCapabilities = ApiDeviceCapabilities(),
)

@Serializable
internal data class RewardedInitApiResponse(
    // The impression (minigame serve) id — replaces the old `serve_id`/`ad_id` pair as the
    // single handle for verify-reward, fallbacks, tracking and reporting.
    @SerialName("impression_id") val impressionId: String? = null,
    // Playables are rendered from server HTML; video assets are described by creative.url.
    @SerialName("rendered_html") val renderedHtml: String? = null,
    val creative: ApiCreative? = null,
    @Serializable(with = LenientNullableExperimentSerializer::class)
    val experiment: ApiExperiment? = null,
    val destination: String = "appstore",
    @SerialName("tracking_url") val trackingUrl: String? = null,
    @SerialName("impression_url") @Serializable(with = LenientNullableStringSerializer::class)
    val impressionUrl: String? = null,
    // Raw, unwrapped Play Store link — see [AdLoadApiResponse.androidStoreUrl].
    @SerialName("android_store_url") val androidStoreUrl: String? = null,
    @SerialName("prewarm_sk_product") val prewarmSkProduct: Boolean = false,
    // Cleared bid (estimated CPM) for this serve — see [AdLoadApiResponse.bidAmt]. Drives `adValue`.
    @SerialName("bid_amt") val bidAmt: Double = 0.0,
    // Mirrors the interstitial response: the play-to-earn gate (`close.delay_seconds`) plus the
    // mid-ad store prompt + its tap routing. Null/absent → no gate / no store prompt.
    @SerialName("ad_behavior") val adBehavior: ApiAdBehavior? = null,
    @SerialName("video_contract") @Serializable(with = LenientNullableIntSerializer::class)
    val videoContract: Int? = null,
)

@Serializable
internal data class VerifyRewardRequestBody(
    @SerialName("serve_id") val serveId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("elapsed_play_time") val elapsedPlayTime: Double,
    @SerialName("completion_reason") val completionReason: String? = null,
)

@Serializable
internal data class VerifyRewardApiResponse(
    val verified: Boolean = false,
    val token: String? = null,
)

// ── Native sponsored-character ad (POST /load/native) ──────────────────────────

/** Body for `POST /load/native` (backend `CaiNativeRequest`). `position` + `session_id` are
 * required; everything else is optional. The native surface has no `char_image` (unlike the
 * interstitial). `width` is accepted but ignored by the API — the card always renders at 100%. */
@Serializable
internal data class NativeAdRequestBody(
    val position: Int,
    @SerialName("session_id") val sessionId: String,
    @SerialName("ad_unit_id") val adUnitId: String? = null,
    val context: NativeContextBody? = null,
    // Sent as a string (the backend accepts float | str); reserved — sizing is client-side.
    val width: String? = null,
    // "light" or "dark"; null omits the key (backend defaults to light).
    val theme: String? = null,
    @SerialName("char_id") val charId: String? = null,
    @SerialName("char_name") val charName: String? = null,
    @SerialName("char_desc") val charDesc: String? = null,
    val metadata: Map<String, String>? = null,
)

@Serializable
internal data class ImpressionMetadataRequestBody(
    val metadata: Map<String, String>,
)

internal fun impressionMetadataRequestBody(
    action: String,
    metadata: Map<String, String>?,
): ImpressionMetadataRequestBody? = metadata
    ?.takeIf { action == "seen" && it.isNotEmpty() }
    ?.let(::ImpressionMetadataRequestBody)

/** The wire `NativeContext` object — camelCase keys (unlike the rest of the snake_case API). */
@Serializable
internal data class NativeContextBody(
    val searchTerm: String? = null,
    val tags: List<String>? = null,
    val category: String? = null,
    val title: String? = null,
    val description: String? = null,
    val userProfile: String? = null,
    val userEmail: String? = null,
    val customContext: Map<String, JsonElement>? = null,
    val nsfw: Boolean = false,
)

/** Response for `POST /load/native` (backend `CaiNativeResponse`). A flat envelope mirroring the
 * imperative [AdLoadApiResponse]: the creative (`rendered_html`) and the click-through
 * params (`destination`, `tracking_url`) sit at the top level (the creative was previously nested
 * under a camelCase `adResponse`). Every field defaults to its empty/no-fill value, so a `{}` or
 * partial payload decodes safely. */
@Serializable
internal data class NativeAdApiResponse(
    @SerialName("impression_id") val impressionId: String? = null,
    @SerialName("ad_inserted") val adInserted: Boolean = false,
    @SerialName("ad_format") val adFormat: String = "",
    // The mountable server-rendered creative; null on a no-fill.
    @SerialName("rendered_html") val renderedHtml: String? = null,
    // Click-through routing (mirrors [AdLoadApiResponse]): `destination` is where a CTA tap goes
    // ("appstore" | "web") and `tracking_url` is the MMP click tracker the SDK routes
    // (attribution-preserving). `tracking_url` is null when the chosen campaign has no tracker.
    val destination: String = "appstore",
    @SerialName("tracking_url") val trackingUrl: String? = null,
    // Raw, unwrapped Play Store link — see [AdLoadApiResponse.androidStoreUrl]. The native CTA's
    // deterministic fallback when the tracker is missing or can't be launched.
    @SerialName("android_store_url") val androidStoreUrl: String? = null,
    // Cleared bid (estimated CPM) for this serve — see [AdLoadApiResponse.bidAmt]. Drives `adValue`.
    @SerialName("bid_amt") val bidAmt: Double = 0.0,
)

/** Maps the public [ad.simula.ad.sdk.model.SimulaAdContext] onto the camelCase wire object.
 * `nsfw` defaults to false on both sides, so it's always emitted. */
internal fun ad.simula.ad.sdk.model.SimulaAdContext.toBody(): NativeContextBody = NativeContextBody(
    searchTerm = searchTerm,
    tags = tags,
    category = category,
    title = title,
    description = description,
    userProfile = userProfile,
    userEmail = userEmail,
    customContext = customContext?.mapValues { (_, v) -> v.toJsonElement() },
    nsfw = nsfw,
)

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}
