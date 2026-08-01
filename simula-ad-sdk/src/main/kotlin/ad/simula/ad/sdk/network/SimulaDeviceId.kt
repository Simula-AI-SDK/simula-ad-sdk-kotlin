package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.core.SimulaScope
import android.content.Context
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
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

    private val primer = DeviceIdPrimer<Context>(SimulaScope) { context ->
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    @Volatile
    private var appContext: Context? = null

    /** The resolved device id, or null until priming succeeds with a non-blank value. */
    val value: String? get() = primer.value

    /**
     * Kick off (idempotent) resolution off the main thread. `Settings.Secure.getString` is a
     * synchronous ContentProvider/binder call, so resolving it on [SimulaScope] (IO) keeps it off
     * the app-start / composition critical path it used to run on. Until it completes [value] is null
     * and the `X-Device-Id` header is simply omitted — the very first native request may go without
     * it, which the contract already allows.
     */
    fun prime(context: Context) {
        val app = context.applicationContext
        appContext = app
        primer.prime(app)
    }

    /**
     * Request-path retry trigger. Header construction calls this before reading [value], so a blank
     * or failed startup resolution gets another real production attempt. It only schedules the
     * existing single flight on [SimulaScope]; it never performs the binder read inline.
     */
    fun retryIfNeeded() {
        if (value != null) return
        appContext?.let { primer.prime(it) }
    }
}

/**
 * Testable single-flight engine behind [SimulaDeviceId]. Resolution always runs in [scope], never
 * inline in [prime]. A blank value or exception releases the flight so a later call can retry;
 * the first non-blank value is retained only in memory for the process lifetime.
 */
internal class DeviceIdPrimer<T>(
    private val scope: CoroutineScope,
    private val retryDelayMs: Long = 30_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val resolve: suspend (T) -> String?,
) {
    @Volatile
    var value: String? = null
        private set

    private val priming = AtomicBoolean(false)
    @Volatile private var nextAttemptAtMs = 0L

    fun prime(input: T) {
        if (value != null || nowMs() < nextAttemptAtMs || !priming.compareAndSet(false, true)) return
        try {
            val job = scope.launch {
                val resolved = runCatching { resolve(input) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                if (resolved != null) value = resolved
            }
            // Completion also covers cancellation before the coroutine body starts (for example,
            // an injected/test scope that was already cancelled), so no failed launch can wedge it.
            job.invokeOnCompletion {
                if (value == null) nextAttemptAtMs = nowMs() + retryDelayMs
                priming.set(false)
            }
        } catch (_: Exception) {
            nextAttemptAtMs = nowMs() + retryDelayMs
            priming.set(false)
        }
    }
}
