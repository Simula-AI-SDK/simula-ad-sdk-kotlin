package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.telemetry.SIMULA_SDK_VERSION
import android.content.Context
import android.os.Build
import java.util.Locale

/**
 * Builds and caches the custom User-Agent the SDK stamps on every native HTTP request
 * (User-Agent for Apps SDK PRD). Format is the standard ad-SDK layout:
 *
 * ```
 * Simula-SDK/{sdkVersion} ({os} {osVersion}; {locale}; {deviceModel}; Build/{buildId}; {bundleId})
 * ```
 *
 * e.g. `Simula-SDK/1.0.3 (Android 13; en_US; SM-G990B; Build/TP1A.220624.014; com.publisher.app)`
 *
 * Every field is read from Android system APIs (no permissions). The string is built once at SDK
 * init (both the imperative `SimulaAds.initialize` and the declarative `SimulaProvider` paths) and
 * [SimulaHttp] reads [value] and sets it as the `User-Agent` header on each connection — the SDK
 * uses raw [java.net.HttpURLConnection], not OkHttp, so there is no shared client/interceptor.
 */
internal object SimulaUserAgent {

    /** The composed UA string, or null until [build] has run. Read by [SimulaHttp.open]. */
    @Volatile
    var value: String? = null
        private set

    /** Build (idempotently) from the application context. The first call wins; later calls no-op. */
    fun build(context: Context): String {
        value?.let { return it }
        // deviceModel uses the hardware model id (Build.MODEL, e.g. SM-G990B), not a friendly name.
        // buildId is the OS build number (Build.ID, e.g. TP1A.220624.014).
        val ua = compose(
            sdkVersion = SIMULA_SDK_VERSION,
            osVersion = Build.VERSION.RELEASE ?: "",
            locale = currentLocale(),
            deviceModel = Build.MODEL ?: "",
            buildId = Build.ID ?: "",
            bundleId = context.packageName,
        )
        value = ua
        return ua
    }

    /** Underscore locale (en_US) per the PRD — not the hyphenated BCP-47 tag (en-US). */
    private fun currentLocale(): String {
        val l = Locale.getDefault()
        val lang = l.language.ifBlank { "und" }
        val country = l.country
        return if (country.isBlank()) lang else "${lang}_$country"
    }

    /** Pure assembly, split out so it's unit-testable without Android [Build] statics. */
    internal fun compose(
        sdkVersion: String,
        osVersion: String,
        locale: String,
        deviceModel: String,
        buildId: String,
        bundleId: String,
    ): String =
        "Simula-SDK/$sdkVersion (Android $osVersion; $locale; $deviceModel; Build/$buildId; $bundleId)"
}
