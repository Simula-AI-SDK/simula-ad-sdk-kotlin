package ad.simula.ad.sdk.privacy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Public privacy / consent configuration supplied to `SimulaProvider`.
 *
 * Every field defaults to a contextual, no-tracking posture, so existing
 * integrations behave exactly as before until a host opts in. Explicit non-null
 * values take precedence over anything the SDK auto-reads from the IAB-standard
 * `SharedPreferences` keys a CMP writes (see [SimulaPrivacy]).
 *
 * Mirrors the Swift SDK's `SimulaPrivacyConfig`.
 */
data class SimulaPrivacyConfig(
    /** Legacy coarse consent flag. When false, suppresses PII (the `ppid`). */
    val hasPrivacyConsent: Boolean = true,
    /** IAB TCF v2.2 consent string (mirror of `IABTCF_TCString`). */
    val tcString: String? = null,
    /** IAB US Privacy (CCPA) string, e.g. "1YNN" (mirror of `IABUSPrivacy_String`). */
    val uspString: String? = null,
    /** IAB Global Privacy Platform string (mirror of `IABGPP_HDR_GppString`). */
    val gppString: String? = null,
    /** Applicable GPP section IDs, comma-separated e.g. "2,6" (mirror of `IABGPP_GppSID`). */
    val gppSid: String? = null,
    /** Whether GDPR applies. null = unknown/unset (mirror of `IABTCF_gdprApplies`). */
    val gdprApplies: Boolean? = null,
    /**
     * Explicit TCF Purpose 1 ("store/access information on a device") consent. When
     * set, takes precedence over the auto-read `IABTCF_PurposeConsents`. Useful for
     * hosts without a TCF CMP and for testing storage-degradation behavior.
     */
    val tcfPurpose1Consent: Boolean? = null,
    /** COPPA (child-directed) treatment. When true, PII and the GAID are suppressed. */
    val coppaApplies: Boolean = false,
    /**
     * Opt-in switch for advertising-identifier (GAID) collection. Default false
     * keeps the SDK contextual-only. Even when true, the GAID is read only when
     * Play Services is present, ad personalization is not limited, and COPPA does
     * not apply.
     */
    val enableAdvertisingId: Boolean = false,
)

/**
 * Immutable, resolved snapshot of the privacy state at a point in time. Produced
 * by [SimulaPrivacy] (merging explicit config over IAB-read values); consumed by
 * the API client to build request headers and the `/session/create` body.
 *
 * Mirrors the Swift SDK's `ConsentSnapshot`.
 */
data class ConsentSnapshot(
    val hasPrivacyConsent: Boolean = true,
    val tcString: String? = null,
    val uspString: String? = null,
    val gppString: String? = null,
    val gppSid: String? = null,
    val gdprApplies: Boolean? = null,
    val coppaApplies: Boolean = false,
    /** TCF Purpose 1 consent (first char of `IABTCF_PurposeConsents`); null = unknown. */
    val tcfPurpose1Consent: Boolean? = null,
    /** Collected advertising id (GAID), or null when not collected/allowed. */
    val advertisingId: String? = null,
) {
    /** Whether the host's `primaryUserID` (`ppid`) may be forwarded. */
    val allowsPrimaryUserID: Boolean get() = hasPrivacyConsent && !coppaApplies

    /**
     * Whether non-essential local storage / caching is permitted. Under TCF a
     * denied Purpose 1 means the SDK must avoid non-essential on-device storage.
     * When GDPR applies, an *unknown* Purpose 1 is treated as denied (consent must
     * be explicit); outside GDPR we permit by default (contextual).
     */
    val allowsLocalStorage: Boolean
        get() = when {
            gdprApplies == true -> tcfPurpose1Consent == true
            else -> tcfPurpose1Consent ?: true
        }

    /**
     * Consent *metadata* as request headers (for ad-serving / tracking calls). The
     * raw advertising identifier is intentionally **not** included here (it travels
     * only in the session body) to minimize PII exposure on per-request calls.
     */
    fun consentHeaders(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        gdprApplies?.let { h["X-Simula-GDPR-Applies"] = if (it) "1" else "0" }
        tcString?.takeIf { it.isNotEmpty() }?.let { h["X-Simula-Consent-TCString"] = it }
        uspString?.takeIf { it.isNotEmpty() }?.let { h["X-Simula-Consent-USP"] = it }
        gppString?.takeIf { it.isNotEmpty() }?.let { h["X-Simula-Consent-GPP"] = it }
        gppSid?.takeIf { it.isNotEmpty() }?.let { h["X-Simula-Consent-GPP-SID"] = it }
        tcfPurpose1Consent?.let { h["X-Simula-Consent-Purpose1"] = if (it) "1" else "0" }
        h["X-Simula-COPPA"] = if (coppaApplies) "1" else "0"
        return h
    }

    /** Consent signals as the `privacy` JSON block embedded in `/session/create`. */
    fun privacyJson(): JsonObject = buildJsonObject {
        put("hasPrivacyConsent", hasPrivacyConsent)
        put("coppaApplies", coppaApplies)
        gdprApplies?.let { put("gdprApplies", if (it) 1 else 0) }
        tcString?.takeIf { it.isNotEmpty() }?.let { put("tcString", it) }
        uspString?.takeIf { it.isNotEmpty() }?.let { put("uspString", it) }
        gppString?.takeIf { it.isNotEmpty() }?.let { put("gppString", it) }
        gppSid?.takeIf { it.isNotEmpty() }?.let { put("gppSid", it) }
        tcfPurpose1Consent?.let { put("tcfPurpose1Consent", it) }
        advertisingId?.takeIf { it.isNotEmpty() }?.let { put("gaid", it) }
    }
}
