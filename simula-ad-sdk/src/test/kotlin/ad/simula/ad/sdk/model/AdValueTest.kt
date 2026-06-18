package ad.simula.ad.sdk.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tier-0 pure-logic tests for the AdMob-shaped [AdValue] derivation from the backend `bid_amt` (CPM).
 * The three figures must always agree (all derived from one `valueMicros`), and a missing/garbage bid
 * must degrade to a $0 estimate rather than throw — surfacing the paid event can't crash the host.
 */
class AdValueTest {

    private val EPS = 1e-9

    @Test
    fun `derives the AdMob block from a whole-dollar CPM`() {
        // PRD worked example: $5.00 CPM → valueMicros 5000 → $0.005 per impression.
        val v = AdValue.fromBidCpm(5.0)
        assertEquals(5000L, v.valueMicros)
        assertEquals("USD", v.currencyCode)
        assertEquals(AdValue.PrecisionType.ESTIMATED, v.precisionType)
        assertEquals(5.0, v.expectedCpm, EPS)        // valueMicros / 1_000
        assertEquals(0.005, v.expectedRevenue, EPS)  // valueMicros / 1_000_000
    }

    @Test
    fun `carries sub-dollar CPM precision now that bid is a float`() {
        val v = AdValue.fromBidCpm(5.5)
        assertEquals(5500L, v.valueMicros)
        assertEquals(5.5, v.expectedCpm, EPS)
        assertEquals(0.0055, v.expectedRevenue, EPS)
    }

    @Test
    fun `the three figures are always consistent (derived from valueMicros)`() {
        val v = AdValue.fromBidCpm(12.34)
        assertEquals(v.valueMicros / 1_000.0, v.expectedCpm, EPS)
        assertEquals(v.valueMicros / 1_000_000.0, v.expectedRevenue, EPS)
    }

    @Test
    fun `zero bid yields a zero estimate`() {
        val v = AdValue.fromBidCpm(0.0)
        assertEquals(0L, v.valueMicros)
        assertEquals(0.0, v.expectedCpm, EPS)
        assertEquals(0.0, v.expectedRevenue, EPS)
        assertEquals(AdValue.PrecisionType.ESTIMATED, v.precisionType)
    }

    @Test
    fun `negative and non-finite bids clamp to zero (never throw)`() {
        for (bad in listOf(-1.0, -0.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val v = AdValue.fromBidCpm(bad)
            assertEquals("bid=$bad should clamp to 0 micros", 0L, v.valueMicros)
            assertEquals(0.0, v.expectedRevenue, EPS)
        }
    }

    @Test
    fun `respects a non-default currency`() {
        assertEquals("EUR", AdValue.fromBidCpm(3.0, currencyCode = "EUR").currencyCode)
    }
}
