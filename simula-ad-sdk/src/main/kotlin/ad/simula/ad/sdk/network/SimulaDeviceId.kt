package ad.simula.ad.sdk.network

import android.content.Context
import android.provider.Settings

/**
 * The stable per-device, per-app-signing-key install identifier (`Settings.Secure.ANDROID_ID`),
 * sent as the `X-Device-Id` header on every native request alongside the custom User-Agent. No
 * permission required, and (unlike the advertising id) not consent-gated — it's a device/vendor
 * identifier, not an ad-tracking id. Built once at SDK init; null/blank when the platform doesn't
 * supply one (rare), in which case the header is simply omitted.
 */
internal object SimulaDeviceId {

    /** The resolved device id, or null until [build] has run / the platform supplied none. */
    @Volatile
    var value: String? = null
        private set

    /** Resolve (idempotently) from the application context. The first non-blank value wins. */
    fun build(context: Context): String? {
        value?.let { return it }
        val id = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        value = id
        return id
    }
}
