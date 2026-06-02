package ad.simula.ad.sdk.privacy

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Process-wide consent store and source of truth for the SDK's privacy signals.
 *
 * Responsibilities:
 * - **Auto-reads** the IAB-standard keys a CMP writes to the default
 *   `SharedPreferences` (`IABTCF_TCString`, `IABTCF_gdprApplies`,
 *   `IABTCF_PurposeConsents`, `IABUSPrivacy_String`, `IABGPP_HDR_GppString`,
 *   `IABGPP_GppSID`).
 * - **Auto-refreshes** when the CMP updates those keys (registers an
 *   `OnSharedPreferenceChangeListener`).
 * - Merges **explicit overrides** from [SimulaPrivacyConfig] / [update] on top.
 * - Owns GAID collection, gated by `enableAdvertisingId` and `coppaApplies`,
 *   read reflectively so Play Services is not a required dependency.
 * - Exposes [snapshot] as a [StateFlow] for Compose, plus [current] for
 *   thread-safe reads from the API client.
 *
 * Mirrors the Swift SDK's `SimulaPrivacy`.
 */
object SimulaPrivacy {

    private val _snapshot = MutableStateFlow(ConsentSnapshot())

    /** Observable snapshot for Compose (`collectAsState()`). */
    val snapshot: StateFlow<ConsentSnapshot> = _snapshot.asStateFlow()

    /** Current resolved snapshot for non-UI consumers (e.g. the API client). */
    val current: ConsentSnapshot get() = _snapshot.value

    private val lock = Any()
    private var explicitConfig = SimulaPrivacyConfig()
    private var collectedAdvertisingId: String? = null
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    private const val ZERO_GAID = "00000000-0000-0000-0000-000000000000"

    // CMPs write the IAB keys asynchronously and may refresh them later; pick
    // changes up automatically.
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> recompute() }

    /**
     * Attach an application context so IAB keys can be auto-read. Idempotent —
     * safe to call from every `SimulaProvider` composition.
     */
    fun attach(context: Context) {
        synchronized(lock) {
            if (appContext != null) return@attach
            val app = context.applicationContext
            appContext = app
            // The IAB in-app spec stores consent in the *default* SharedPreferences,
            // i.e. "<package>_preferences" — replicated here without pulling in
            // androidx.preference.
            val p = app.getSharedPreferences("${app.packageName}_preferences", Context.MODE_PRIVATE)
            prefs = p
            p.registerOnSharedPreferenceChangeListener(prefsListener)
        }
        recompute()
    }

    /** Replace the explicit configuration wholesale (provider init / CMP handoff). */
    fun apply(config: SimulaPrivacyConfig) {
        synchronized(lock) {
            explicitConfig = config
            if (!config.enableAdvertisingId || config.coppaApplies) collectedAdvertisingId = null
        }
        recompute()
    }

    /** Merge a partial runtime update (CMP refresh). null args leave fields unchanged. */
    fun update(
        hasPrivacyConsent: Boolean? = null,
        tcString: String? = null,
        uspString: String? = null,
        gppString: String? = null,
        gppSid: String? = null,
        gdprApplies: Boolean? = null,
        coppaApplies: Boolean? = null,
        enableAdvertisingId: Boolean? = null,
    ) {
        synchronized(lock) {
            explicitConfig = explicitConfig.copy(
                hasPrivacyConsent = hasPrivacyConsent ?: explicitConfig.hasPrivacyConsent,
                tcString = tcString ?: explicitConfig.tcString,
                uspString = uspString ?: explicitConfig.uspString,
                gppString = gppString ?: explicitConfig.gppString,
                gppSid = gppSid ?: explicitConfig.gppSid,
                gdprApplies = gdprApplies ?: explicitConfig.gdprApplies,
                coppaApplies = coppaApplies ?: explicitConfig.coppaApplies,
                enableAdvertisingId = enableAdvertisingId ?: explicitConfig.enableAdvertisingId,
            )
            if (!explicitConfig.enableAdvertisingId || explicitConfig.coppaApplies) {
                collectedAdvertisingId = null
            }
        }
        recompute()
    }

    /**
     * Reads the GAID when collection is enabled and COPPA does not apply. Suspends
     * because the Play Services call blocks; safe to call from a `LaunchedEffect`.
     * Gracefully no-ops (id stays null) when Play Services is absent or ad
     * personalization is limited.
     */
    suspend fun refreshAdvertisingId() {
        val enabled: Boolean
        val ctx: Context?
        synchronized(lock) {
            enabled = explicitConfig.enableAdvertisingId && !explicitConfig.coppaApplies
            ctx = appContext
        }
        val id = if (enabled && ctx != null) withContext(Dispatchers.IO) { readGaid(ctx) } else null
        synchronized(lock) { collectedAdvertisingId = id }
        recompute()
    }

    // ── Snapshot building ─────────────────────────────────────────────────────

    private fun recompute() {
        val cfg: SimulaPrivacyConfig
        val adId: String?
        val p: SharedPreferences?
        synchronized(lock) {
            cfg = explicitConfig
            adId = collectedAdvertisingId
            p = prefs
        }
        // Explicit (provider/CMP) values win; otherwise fall back to IAB-read keys.
        _snapshot.value = ConsentSnapshot(
            hasPrivacyConsent = cfg.hasPrivacyConsent,
            tcString = cfg.tcString ?: getStringSafe(p, "IABTCF_TCString"),
            uspString = cfg.uspString ?: getStringSafe(p, "IABUSPrivacy_String"),
            gppString = cfg.gppString ?: getStringSafe(p, "IABGPP_HDR_GppString"),
            gppSid = cfg.gppSid ?: readGppSid(p),
            gdprApplies = cfg.gdprApplies ?: readGdprApplies(p),
            coppaApplies = cfg.coppaApplies,
            tcfPurpose1Consent = readPurpose1(p),
            advertisingId = if (cfg.coppaApplies) null else adId,
        )
    }

    // ── IAB key readers ───────────────────────────────────────────────────────

    private fun getStringSafe(p: SharedPreferences?, key: String): String? = try {
        p?.getString(key, null)?.takeIf { it.isNotEmpty() }
    } catch (_: ClassCastException) {
        null
    }

    /** `IABTCF_gdprApplies` is stored as a Number (0/1). null when unset. */
    private fun readGdprApplies(p: SharedPreferences?): Boolean? {
        p ?: return null
        if (!p.contains("IABTCF_gdprApplies")) return null
        return try {
            p.getInt("IABTCF_gdprApplies", 0) == 1
        } catch (_: ClassCastException) {
            getStringSafe(p, "IABTCF_gdprApplies") == "1"
        }
    }

    /**
     * `IABGPP_GppSID` is stored as an underscore-separated string of section IDs
     * (e.g. "2_6"). Normalize to comma-separated to match the wire contract.
     */
    private fun readGppSid(p: SharedPreferences?): String? {
        val s = getStringSafe(p, "IABGPP_GppSID") ?: return null
        return s.replace("_", ",")
    }

    /** TCF Purpose 1 consent = first char of `IABTCF_PurposeConsents` ('1' = consented). */
    private fun readPurpose1(p: SharedPreferences?): Boolean? {
        val s = getStringSafe(p, "IABTCF_PurposeConsents") ?: return null
        return s.firstOrNull() == '1'
    }

    /**
     * Reads the GAID via reflection so `play-services-ads-identifier` stays an
     * optional, host-supplied dependency. Returns null when the class is missing,
     * ad personalization is limited, or the id is the all-zero "unavailable" UUID.
     */
    private fun readGaid(context: Context): String? {
        return try {
            val clientClass = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient")
            val info = clientClass.getMethod("getAdvertisingIdInfo", Context::class.java)
                .invoke(null, context) ?: return null
            val infoClass = info.javaClass
            val limited = infoClass.getMethod("isLimitAdTrackingEnabled").invoke(info) as? Boolean ?: false
            if (limited) return null
            (infoClass.getMethod("getId").invoke(info) as? String)
                ?.takeIf { it.isNotEmpty() && it != ZERO_GAID }
        } catch (_: Throwable) {
            null
        }
    }
}
