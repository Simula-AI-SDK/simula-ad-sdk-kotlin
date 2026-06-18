package ad.simula.ad.sdk.model

import kotlin.math.roundToLong

/**
 * Estimated per-impression revenue for a served ad, in AdMob's `AdValue` shape so it's a drop-in for a
 * publisher's existing analytics / MMP pipeline. Surfaced on the **paid** callback
 * ([ad.simula.ad.sdk.ads.SimulaInterstitialAdListener.onAdPaid] / `NativeAdSlot`'s `onPaid`) at the
 * moment the impression fires — never at load.
 *
 * The SDK does not compute revenue from scratch: the backend ships the bid (CPM) with the ad and the
 * SDK derives the whole block once, on-device, with no network round-trip (see [fromBidCpm]). All
 * figures are estimates known at serve time (from the floor CPM).
 *
 * Mirrors the Swift SDK's `AdValue`.
 */
data class AdValue(
    /** Canonical estimate: per-impression revenue in micros of [currencyCode]. `5000` = $0.005. */
    val valueMicros: Long,
    /** ISO-4217 currency code, e.g. `"USD"`. */
    val currencyCode: String,
    /** Estimate quality. Always [PrecisionType.ESTIMATED] for now (backend-provided). */
    val precisionType: PrecisionType,
    /** Estimated CPM = [valueMicros] / 1_000. Convenience; derived from [valueMicros]. */
    val expectedCpm: Double,
    /** Estimated per-impression revenue = [valueMicros] / 1_000_000. Convenience; derived from [valueMicros]. */
    val expectedRevenue: Double,
) {
    enum class PrecisionType { ESTIMATED }

    companion object {
        /**
         * Builds an [AdValue] from the backend-provided `bid_amt` — the estimated CPM in [currencyCode].
         * The three figures are all derived from a single [valueMicros] so they can never disagree on
         * rounding: `valueMicros = round(bidCpm × 1000)`, `expectedCpm = valueMicros / 1000`,
         * `expectedRevenue = valueMicros / 1_000_000` (e.g. a $5.00 CPM → valueMicros 5000 →
         * $0.005 per impression).
         *
         * Tolerant by construction: a non-finite or negative bid is clamped to 0, so a missing/garbage
         * field yields a $0 [AdValue] rather than throwing — surfacing the paid event must never crash
         * the host app.
         */
        internal fun fromBidCpm(bidCpm: Double, currencyCode: String = "USD"): AdValue {
            val safeBid = if (bidCpm.isFinite() && bidCpm > 0.0) bidCpm else 0.0
            val valueMicros = (safeBid * 1_000.0).roundToLong()
            return AdValue(
                valueMicros = valueMicros,
                currencyCode = currencyCode,
                precisionType = PrecisionType.ESTIMATED,
                expectedCpm = valueMicros / 1_000.0,
                expectedRevenue = valueMicros / 1_000_000.0,
            )
        }
    }
}
