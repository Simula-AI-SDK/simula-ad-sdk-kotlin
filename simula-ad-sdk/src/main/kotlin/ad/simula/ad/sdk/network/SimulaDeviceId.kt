package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.core.SimulaScope
import android.content.Context
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/**
 * The stable per-device, per-app-signing-key install identifier (`Settings.Secure.ANDROID_ID`),
 * sent as the `X-Device-Id` header on every native request alongside the custom User-Agent. No
 * permission required, and (unlike the advertising id) not consent-gated — it's a device/vendor
 * identifier, not an ad-tracking id. Resolved once, off the main thread; null/blank when the
 * platform doesn't supply one (rare) or before resolution finishes, in which case the header is
 * simply omitted.
 */
internal object SimulaDeviceId {

    /** The resolved device id, or null until [build] has run / the platform supplied none. */
    @Volatile
    var value: String? = null
        private set

    /** Ensures resolution is kicked off at most once. */
    private val priming = AtomicBoolean(false)

    /**
     * Kick off (idempotent) resolution off the main thread. `Settings.Secure.getString` is a
     * synchronous ContentProvider/binder call, so resolving it on [SimulaScope] (IO) keeps it off
     * the app-start / composition critical path it used to run on. Until it completes [value] is null
     * and the `X-Device-Id` header is simply omitted — the very first native request may go without
     * it, which the contract already allows.
     */
    fun prime(context: Context) {
        if (value != null || !priming.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        SimulaScope.launch { build(appContext) }
    }

    /** Resolve (idempotently) from the application context. The first non-blank value wins. Performs a
     * binder call, so callers should go through [prime]; exposed for tests / synchronous reuse. */
    fun build(context: Context): String? {
        value?.let { return it }
        val id = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        value = id
        return id
    }
}
