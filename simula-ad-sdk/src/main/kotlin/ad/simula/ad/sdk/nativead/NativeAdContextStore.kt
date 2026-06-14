package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.model.SimulaAdContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder for the publisher's native-ad targeting [SimulaAdContext].
 *
 * Context is set at the provider level, not per slot (PRD): [ad.simula.ad.sdk.provider.SimulaProvider]
 * and [ad.simula.ad.sdk.ads.SimulaAds.initialize] seed it, and either may replace it wholesale at
 * runtime via `updateContext`. Every `POST /load/native` reads [current] from here, so a slot never
 * has to thread context through. Mirrors the single-source pattern of
 * [ad.simula.ad.sdk.privacy.SimulaPrivacy].
 *
 * Updates are a full replacement, not a merge. Ads already preloaded under the previous context are
 * unaffected — they were fetched with the value that was current at preload time.
 */
internal object NativeAdContextStore {

    private val _snapshot = MutableStateFlow<SimulaAdContext?>(null)

    /** Latest context, or null if the publisher never set one. */
    val current: SimulaAdContext? get() = _snapshot.value

    /** Observable snapshot (unused by the SDK today; exposed for parity with SimulaPrivacy). */
    val snapshot: StateFlow<SimulaAdContext?> = _snapshot.asStateFlow()

    /** Replace the context in full. A null clears it. */
    fun set(context: SimulaAdContext?) {
        _snapshot.value = context
    }
}
