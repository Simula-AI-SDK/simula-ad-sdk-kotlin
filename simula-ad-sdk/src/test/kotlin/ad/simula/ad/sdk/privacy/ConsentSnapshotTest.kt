package ad.simula.ad.sdk.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the consent snapshot wire formats and derived gating.
 * Mirrors the Swift SDK's PrivacyTests for the shared logic. (The IAB
 * SharedPreferences auto-read in SimulaPrivacy is covered cross-platform by the
 * Swift store tests.)
 */
class ConsentSnapshotTest {

    @Test
    fun consentHeaders_includesAllSetSignals() {
        val h = ConsentSnapshot(
            tcString = "CPtc", uspString = "1YNN", gppString = "DBABgpp",
            gppSid = "2,6", gdprApplies = true, coppaApplies = false,
            advertisingId = "GAID1",
        ).consentHeaders()
        assertEquals("1", h["X-Simula-GDPR-Applies"])
        assertEquals("CPtc", h["X-Simula-Consent-TCString"])
        assertEquals("1YNN", h["X-Simula-Consent-USP"])
        assertEquals("DBABgpp", h["X-Simula-Consent-GPP"])
        assertEquals("2,6", h["X-Simula-Consent-GPP-SID"])
        assertEquals("0", h["X-Simula-COPPA"])
        // The raw advertising id is intentionally NOT in headers (session body only).
        assertNull(h["X-Simula-GAID"])
    }

    @Test
    fun consentHeaders_omitsEmptyButKeepsCoppa() {
        val h = ConsentSnapshot().consentHeaders()
        assertNull(h["X-Simula-Consent-TCString"])
        assertNull(h["X-Simula-GAID"])
        assertEquals("0", h["X-Simula-COPPA"])
    }

    @Test
    fun privacyJson_serializesExpectedFields() {
        val json = ConsentSnapshot(
            tcString = "CPtc", gdprApplies = true, coppaApplies = true, advertisingId = "GAID1",
        ).privacyJson().toString()
        assertTrue(json.contains("\"hasPrivacyConsent\":true"))
        assertTrue(json.contains("\"coppaApplies\":true"))
        assertTrue(json.contains("\"gdprApplies\":1"))
        assertTrue(json.contains("\"tcString\":\"CPtc\""))
        assertTrue(json.contains("\"gaid\":\"GAID1\""))
    }

    @Test
    fun gating_coppaAndConsentSuppressPrimaryUserID() {
        assertTrue(ConsentSnapshot(hasPrivacyConsent = true, coppaApplies = false).allowsPrimaryUserID)
        assertFalse(ConsentSnapshot(hasPrivacyConsent = true, coppaApplies = true).allowsPrimaryUserID)
        assertFalse(ConsentSnapshot(hasPrivacyConsent = false).allowsPrimaryUserID)
    }

    @Test
    fun gating_purpose1GatesLocalStorageOutsideGdpr() {
        assertTrue(ConsentSnapshot(tcfPurpose1Consent = null).allowsLocalStorage)  // unknown → permit
        assertTrue(ConsentSnapshot(tcfPurpose1Consent = true).allowsLocalStorage)
        assertFalse(ConsentSnapshot(tcfPurpose1Consent = false).allowsLocalStorage)
    }

    @Test
    fun gating_unknownPurpose1DeniedUnderGdpr() {
        // Under GDPR an unknown Purpose 1 must be treated as denied.
        assertFalse(ConsentSnapshot(gdprApplies = true, tcfPurpose1Consent = null).allowsLocalStorage)
        assertFalse(ConsentSnapshot(gdprApplies = true, tcfPurpose1Consent = false).allowsLocalStorage)
        assertTrue(ConsentSnapshot(gdprApplies = true, tcfPurpose1Consent = true).allowsLocalStorage)
    }

    @Test
    fun normalizeGppSid_handlesStringNumberAndSet() {
        // CMPs write IABGPP_GppSID inconsistently: string, number, or a Set.
        assertEquals("2,6", normalizeGppSid("2_6"))
        assertEquals("2,6", normalizeGppSid("2,6"))
        assertEquals("2", normalizeGppSid(2))
        assertEquals("2", normalizeGppSid(2L))
        assertEquals("2,6,10", normalizeGppSid(setOf("6", "10", "2"))) // numeric sort (order-independent)
        assertNull(normalizeGppSid(null))
        assertNull(normalizeGppSid(""))
        assertNull(normalizeGppSid(emptySet<String>()))
    }
}
