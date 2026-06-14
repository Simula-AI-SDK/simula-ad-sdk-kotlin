package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.ads.SimulaAdError
import ad.simula.ad.sdk.network.SimulaApiClient
import kotlinx.coroutines.CancellationException

/**
 * Resolves a session and performs one `POST /load/native`, mapping transport failures to the public
 * [SimulaAdError] taxonomy per the PRD's error table. The native targeting context is read from the
 * process-wide [NativeAdContextStore], so a slot never threads it through.
 *
 * A no-fill (`ad_inserted == false`) is NOT an error here — it comes back as a normal
 * [SimulaApiClient.NativeAdResult] with `adInserted == false`, and the caller collapses the slot
 * silently. Only genuine failures throw.
 */
internal object NativeAdController {

    /**
     * @param ensureSession the session resolver to use — the provider's (declarative slot) or
     *        `SimulaAds.store`'s (imperative preload). Both coalesce concurrent callers.
     */
    suspend fun load(
        ensureSession: suspend () -> String?,
        adUnitId: String?,
        position: Int,
        theme: String? = null,
    ): SimulaApiClient.NativeAdResult {
        val sessionId = ensureSession()
        if (sessionId.isNullOrBlank()) throw SimulaAdError.NoSession

        return try {
            SimulaApiClient.loadNative(
                position = position,
                sessionId = sessionId,
                adUnitId = adUnitId,
                context = NativeAdContextStore.current,
                theme = theme,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalArgumentException) {
            // 401 bad/unknown session — non-retryable (PRD).
            throw SimulaAdError.NoSession
        } catch (e: Exception) {
            throw SimulaAdError.Network(e)
        }
    }
}
