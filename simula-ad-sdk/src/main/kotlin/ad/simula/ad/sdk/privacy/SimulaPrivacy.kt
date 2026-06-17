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

    // GAID re-read throttle: the reflective Play Services lookup runs on every ON_RESUME,
    // but the id rarely changes within a session. Refresh at most once per [GAID_REFRESH_TTL_MS]
    // unless the consent / limit-tracking gate actually changed (see [refreshAdvertisingId]).
    // Volatile so the foreground (resume) read on any thread sees the latest stamp without a lock.
    @Volatile private var lastGaidRefreshAt = 0L
    // The collection-enabled gate (enableAdvertisingId && !coppaApplies) used for the last refresh,
    // so a consent change forces an immediate re-read regardless of the TTL. null = never refreshed.
    @Volatile private var lastGaidEnabled: Boolean? = null

    private const val ZERO_GAID = "00000000-0000-0000-0000-000000000000"

    /** How long a collected GAID is considered fresh before a foreground re-read is allowed. */
    private const val GAID_REFRESH_TTL_MS = 4 * 60 * 60 * 1000L // 4 hours

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
        tcfPurpose1Consent: Boolean? = null,
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
                tcfPurpose1Consent = tcfPurpose1Consent ?: explicitConfig.tcfPurpose1Consent,
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
     * Clears the named explicit consent overrides back to "unset". The store then
     * falls back to any auto-read IAB value (or null). Unlike [update], where null
     * means "leave unchanged", this is how you *remove* a signal you set.
     */
    fun clearConsent(
        tcString: Boolean = false,
        uspString: Boolean = false,
        gppString: Boolean = false,
        gppSid: Boolean = false,
        gdprApplies: Boolean = false,
        tcfPurpose1Consent: Boolean = false,
    ) {
        synchronized(lock) {
            explicitConfig = explicitConfig.copy(
                tcString = if (tcString) null else explicitConfig.tcString,
                uspString = if (uspString) null else explicitConfig.uspString,
                gppString = if (gppString) null else explicitConfig.gppString,
                gppSid = if (gppSid) null else explicitConfig.gppSid,
                gdprApplies = if (gdprApplies) null else explicitConfig.gdprApplies,
                tcfPurpose1Consent = if (tcfPurpose1Consent) null else explicitConfig.tcfPurpose1Consent,
            )
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

        // Throttle the reflective Play Services lookup. The first call always proceeds
        // (lastGaidEnabled == null), and any change to the collection gate forces an immediate
        // re-read; otherwise honor the TTL so a frequently-foregrounded app doesn't re-read on
        // every ON_RESUME (the id is stable within the window). When collection is disabled we
        // still fall through to null out the id below — that path doesn't touch Play Services.
        val now = System.currentTimeMillis()
        val gateChanged = lastGaidEnabled != enabled
        if (enabled && !gateChanged && now - lastGaidRefreshAt < GAID_REFRESH_TTL_MS) return

        val id = if (enabled && ctx != null) withContext(Dispatchers.IO) { readGaid(ctx) } else null
        synchronized(lock) { collectedAdvertisingId = id }
        lastGaidEnabled = enabled
        lastGaidRefreshAt = now
        recompute()
    }

    // ── Snapshot building ─────────────────────────────────────────────────────

    private fun recompute() {
        // Build and publish the snapshot atomically under a single lock so two
        // concurrent updates can't interleave and let an older snapshot clobber a
        // newer one (SharedPreferences reads are in-memory and cheap).
        synchronized(lock) {
            val cfg = explicitConfig
            val p = prefs
            // Explicit (provider/CMP) values win; otherwise fall back to IAB keys.
            _snapshot.value = ConsentSnapshot(
                hasPrivacyConsent = cfg.hasPrivacyConsent,
                tcString = cfg.tcString ?: getStringSafe(p, "IABTCF_TCString"),
                uspString = cfg.uspString ?: getStringSafe(p, "IABUSPrivacy_String"),
                gppString = cfg.gppString ?: getStringSafe(p, "IABGPP_HDR_GppString"),
                gppSid = cfg.gppSid ?: readGppSid(p),
                gdprApplies = cfg.gdprApplies ?: readGdprApplies(p),
                coppaApplies = cfg.coppaApplies,
                tcfPurpose1Consent = cfg.tcfPurpose1Consent ?: readPurpose1(p),
                advertisingId = if (cfg.coppaApplies) null else collectedAdvertisingId,
            )
        }
    }

    // ── IAB key readers ───────────────────────────────────────────────────────

    /**
     * Reads a key as a non-empty string, coercing Numbers (some CMPs store IAB
     * fields as Numbers, e.g. a single-section `IABGPP_GppSID` or `gdprApplies`).
     * Returns null for missing / empty / uncoercible values.
     */
    private fun getStringSafe(p: SharedPreferences?, key: String): String? {
        p ?: return null
        return try {
            // Common path: avoid the full-map copy that `p.all` would allocate.
            p.getString(key, null)?.takeIf { it.isNotEmpty() }
        } catch (_: ClassCastException) {
            // CMP stored a non-string (e.g. a single-section GppSID as Int). Coerce.
            when (val v = p.all[key]) {
                is Int, is Long, is Float, is Double -> v.toString()
                else -> null
            }
        }
    }

    /** `IABTCF_gdprApplies` is stored as a Number (0/1), occasionally a String. */
    private fun readGdprApplies(p: SharedPreferences?): Boolean? {
        val s = getStringSafe(p, "IABTCF_gdprApplies") ?: return null
        return s == "1"
    }

    /**
     * `IABGPP_GppSID` may be a String ("2_6"), a single Number, or a `Set<String>`
     * depending on the CMP. Read the raw value and normalize via [normalizeGppSid].
     */
    private fun readGppSid(p: SharedPreferences?): String? {
        p ?: return null
        if (!p.contains("IABGPP_GppSID")) return null
        val str = try { p.getString("IABGPP_GppSID", null) } catch (_: ClassCastException) { null }
        return normalizeGppSid(str ?: p.all["IABGPP_GppSID"])
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

/**
 * Normalizes `IABGPP_GppSID` to a comma-separated string of section IDs across the
 * shapes CMPs use: an underscore-/comma-separated String, a single Number, or a
 * `Set<*>` (order isn't preserved by SharedPreferences, so it is sorted numerically).
 */
internal fun normalizeGppSid(raw: Any?): String? = when (raw) {
    is String -> raw.takeIf { it.isNotEmpty() }?.replace("_", ",")
    is Int, is Long, is Float, is Double -> raw.toString()
    is Set<*> -> raw.mapNotNull { it?.toString()?.takeIf(String::isNotEmpty) }
        .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
        .joinToString(",")
        .takeIf { it.isNotEmpty() }
    else -> null
}
