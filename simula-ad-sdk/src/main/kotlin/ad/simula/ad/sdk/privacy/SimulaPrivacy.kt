package ad.simula.ad.sdk.privacy

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.telemetry.Telemetry
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.InvocationTargetException

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

    // Serializes concurrent refresh triggers (startup + ON_RESUME + provider effects can overlap):
    // the winner reads from Play Services while the others WAIT for it — a caller returning from
    // [refreshAdvertisingId] must find the GAID state settled (sequenced callers, e.g. a session
    // create that follows, rely on that).
    private val gaidRefreshMutex = Mutex()

    private const val ZERO_GAID = "00000000-0000-0000-0000-000000000000"

    /** How long a collected GAID is considered fresh before a foreground re-read is allowed. */
    private const val GAID_REFRESH_TTL_MS = 4 * 60 * 60 * 1000L // 4 hours

    // CMPs write the IAB keys asynchronously and may refresh them later; pick
    // changes up automatically.
    //
    // Registered for the process lifetime and intentionally never unregistered: SimulaPrivacy is a
    // process-wide singleton with no teardown, mirroring the SDK's overall lifecycle (SimulaScope,
    // the WebViewPool/ImageCache ComponentCallbacks2, and ActivityLifecycleCallbacks are likewise
    // process-scoped). The SDK does not support runtime teardown / dynamic-feature unload by design —
    // process death reclaims everything; there is deliberately no SimulaAds.shutdown().
    //
    // Key-filtered: the host's default prefs file can be written constantly by non-SDK code;
    // only an IAB-key (or bulk/null) change can alter the consent snapshot.
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (shouldRecomputePrivacy(key)) recompute()
        }

    /**
     * Attach an application context so IAB keys can be auto-read. Idempotent —
     * safe to call from every `SimulaProvider` composition.
     */
    fun attach(context: Context) {
        val app = context.applicationContext
        // The IAB in-app spec stores consent in the *default* SharedPreferences,
        // i.e. "<package>_preferences" — replicated here without pulling in androidx.preference.
        // getSharedPreferences() does a first-access disk read, so resolve it OUTSIDE the lock —
        // initialize() calls attach() inside its own synchronized block on the main thread, and we
        // must not hold a lock across I/O. Android caches the instance, so a concurrent/repeat call
        // is cheap and idempotent.
        val p = app.getSharedPreferences("${app.packageName}_preferences", Context.MODE_PRIVATE)
        // Pre-touch OUTSIDE any lock: getSharedPreferences() only STARTS the async disk load —
        // the first actual read blocks in awaitLoadedLocked() until it lands. Without this,
        // the first recompute() holds the privacy lock across that blocking read, and any
        // main-thread contender (ON_RESUME refreshAdvertisingId, a CMP apply at cold start)
        // stalls behind it.
        p.contains("IABTCF_TCString")
        synchronized(lock) {
            if (appContext != null) return@attach
            appContext = app
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
     *
     * Returns with the GAID state settled: concurrent callers coalesce onto the
     * in-flight read (double-checked under [gaidRefreshMutex]) instead of racing a
     * second one, so a caller that sequences work after this function (e.g. a
     * `/session/create`) sees the refreshed value.
     *
     * When collection is enabled but [attach] hasn't supplied a context yet (e.g. an
     * ON_RESUME fired ahead of the deferred startup attach), the call is a no-op that
     * deliberately does NOT consume the freshness stamp — the next trigger retries,
     * instead of the first real read being throttled away for the full TTL.
     */
    suspend fun refreshAdvertisingId() {
        val enabled: Boolean
        val ctx: Context?
        synchronized(lock) {
            enabled = explicitConfig.enableAdvertisingId && !explicitConfig.coppaApplies
            ctx = appContext
        }

        // Throttle the reflective Play Services lookup (see shouldReadGaidNow): the first
        // call always proceeds, any change to the collection gate forces an immediate
        // re-read, and the disabled path always falls through to null out the id below —
        // that path doesn't touch Play Services.
        val now = System.currentTimeMillis()
        if (!shouldReadGaidNow(enabled, ctx != null, lastGaidEnabled, lastGaidRefreshAt, now, GAID_REFRESH_TTL_MS)) return

        gaidRefreshMutex.withLock {
            // Double-checked under the mutex: a concurrent caller that just finished already
            // refreshed, so skip the duplicate binder read but still return after its write.
            if (!shouldReadGaidNow(enabled, ctx != null, lastGaidEnabled, lastGaidRefreshAt, now, GAID_REFRESH_TTL_MS)) return
            val id = if (enabled && ctx != null) raceGaidRead({ readGaid(ctx) }, GAID_READ_TIMEOUT_MS) else null
            synchronized(lock) { collectedAdvertisingId = id }
            // Stamp even a timed-out read: the TTL then throttles retries instead of every
            // ON_RESUME firing a fresh binder call on a wedged device.
            lastGaidEnabled = enabled
            lastGaidRefreshAt = now
            // Publish INSIDE the mutex: a coalesced waiter returns from the double-check above
            // the instant this lock is released, so the snapshot must already carry the new id
            // by then — otherwise a sequenced /session/create can still read the stale
            // (GAID-less) snapshot in the gap before a post-release recompute().
            recompute()
        }
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
        } catch (_: ClassNotFoundException) {
            // play-services-ads-identifier genuinely absent from the host — an expected,
            // supported configuration (the dependency is optional), so stay silent.
            null
        } catch (t: Throwable) {
            // Anything else is unexpected: Play Services is present but the call failed
            // (transient binder failure), or a minified host build renamed the reflectively
            // read members. Record so the failure is visible in the field instead of
            // silently degrading to a null GAID; deduped by signature in the pipeline.
            // Unwrap the reflection wrapper so the real cause is what gets reported.
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            Telemetry.recordError(
                signature = "privacy:gaid_read_failed",
                errorCode = cause::class.java.simpleName,
                message = cause.message,
            )
            null
        }
    }
}

/**
 * The GAID refresh gate (pure, JVM-testable). Returns true when a Play Services re-read
 * should happen now: on the first call ([lastEnabled] null), whenever the collection
 * gate flipped (consent/COPPA change forces an immediate re-read regardless of TTL),
 * once the TTL has expired — and always when collection is disabled, so the caller
 * falls through and clears any previously collected id (that path never touches Play
 * Services).
 *
 * Enabled but [contextAttached] false (attach() is deferred past `initialize`, so an
 * early ON_RESUME can arrive first) returns false WITHOUT consuming the gate/TTL: the
 * caller skips the read and the next trigger retries. Proceeding here would record a
 * null id and stamp the freshness window, throttling the first real read away for the
 * full TTL — and the first `/session/create` would carry no GAID.
 */
internal fun shouldReadGaidNow(
    enabled: Boolean,
    contextAttached: Boolean,
    lastEnabled: Boolean?,
    lastRefreshAtMs: Long,
    nowMs: Long,
    ttlMs: Long,
): Boolean {
    if (enabled && !contextAttached) return false
    return !enabled || lastEnabled != enabled || nowMs - lastRefreshAtMs >= ttlMs
}

/** Hard bound on one Play Services GAID read (see [raceGaidRead]). */
internal const val GAID_READ_TIMEOUT_MS = 8_000L

/** The IAB CMP keys the consent snapshot reads. The prefs-change listener recomputes ONLY on
 * these (or a bulk/null key) — the host's default prefs file can be written constantly by
 * non-SDK code, and every write used to trigger a full recompute. */
internal val IAB_KEYS = setOf(
    "IABTCF_TCString",
    "IABUSPrivacy_String",
    "IABGPP_HDR_GppString",
    "IABGPP_GppSID",
    "IABTCF_gdprApplies",
    "IABTCF_PurposeConsents",
)

/** True only for an IAB-key update (or a bulk/null callback). Pure and JVM-testable. */
internal fun shouldRecomputePrivacy(key: String?): Boolean = key == null || key in IAB_KEYS

/**
 * Races one GAID read against [timeoutMs]. The Play Services bind is a BLOCKING call with no
 * platform timeout, and a wedged bind must never park the startup gate or `gaidRefreshMutex`
 * forever. The read therefore runs as an ABANDONABLE ORPHAN on [orphanScope]: the caller only
 * suspends on `await()` (a cancellable point), so the timeout fires even when the binder
 * thread is wedged in a non-cancellable blocking call. `withTimeoutOrNull { withContext { …
 * blocking … } }` would NOT work here — cancellation is cooperative, so the coroutine would
 * resume only after the blocking call returned (i.e. never, on a wedged device); that shape
 * also passes unit tests (a cancellable fake times out fine) while being inert in production.
 * The orphan left behind is bounded to one per wedged process: the caller stamps the
 * freshness window, so the TTL throttles retries. JVM-testable with an injected reader,
 * dispatcher, scope, and timeout sink.
 *
 * Only a REAL timeout fires [onTimeout]: the finished-read box keeps a legitimate null
 * result (user opt-out, missing Play Services) distinguishable from `withTimeoutOrNull`'s
 * null-on-timeout — otherwise every opted-out device would emit false
 * `privacy:gaid_read_timeout` errors once per refresh window.
 */
internal suspend fun raceGaidRead(
    reader: suspend () -> String?,
    timeoutMs: Long,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    orphanScope: CoroutineScope = SimulaScope,
    onTimeout: (Long) -> Unit = { ms ->
        Telemetry.recordError(
            signature = "privacy:gaid_read_timeout",
            errorCode = "timeout",
            message = "GAID read exceeded $ms ms",
        )
    },
): String? {
    // NOT a child of the caller: the timeout must be able to leave the read running.
    val orphan = orphanScope.async(dispatcher) { FinishedGaidRead(reader()) }
    val completed = withTimeoutOrNull(timeoutMs) { orphan.await() }
    if (completed == null) {
        onTimeout(timeoutMs)
        return null
    }
    return completed.value
}

/** Boxes a finished GAID read so its (possibly null) value is never confused with a timeout. */
private class FinishedGaidRead(val value: String?)

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
