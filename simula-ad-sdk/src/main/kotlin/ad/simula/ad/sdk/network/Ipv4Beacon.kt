package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.core.SimulaScope
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * IPv4 resolution beacon.
 *
 * WHY: most device IPs the SDK sees are IPv6, but our identity-resolution partners (5x5 /
 * LiveRamp) match on IPv4. Firing a lightweight GET to a host configured with ONLY A records
 * (no AAAA) forces the OS to resolve over IPv4, so the backend captures the device's public
 * IPv4 from that request and links it to the session that loads ads.
 *
 * Replaces the stop-gap that lived in the React Native wrapper's JS layer. Living natively it
 * can carry the server session id (`sid`), which the JS layer could never reach — the backend
 * resolves the capture by `sid` first and falls back to `ppid` only when `sid` is absent.
 *
 * SAFETY: fire-and-forget. Never throws, never blocks init or session creation, and no-ops when
 * no beacon URL is configured. It is intentionally NOT consent-gated — parity with the prior RN
 * implementation (ensure this is covered by privacy policy / publisher agreements).
 *
 * BACKEND CONTRACT: a GET to [DEFAULT_URL] with query params:
 *   k    — apiKey
 *   sid  — server session id   (omitted if session creation failed)
 *   ppid — primary user id     (omitted if absent)
 *   did  — native X-Device-Id  (omitted if unavailable)
 *   p    — platform ("android" from this SDK)
 *   r    — reason ("init" | "ppid_update")
 *   t    — client timestamp (cache-buster)
 * The endpoint reads the client IPv4 from `x-forwarded-for` and stores it keyed by `sid`,
 * falling back to `ppid` (then `did`) when absent. The response body is ignored.
 *
 * DEDUP: one successful capture per (apiKey, sessionId, ppid) identity, with in-flight
 * coalescing; failures stay retryable. A new session id (e.g. a consent-driven resync) or a new
 * ppid is a new identity and re-fires. [onLogout] clears the bookkeeping so a re-login — even
 * with the same ppid — gets a fresh capture for the new session.
 */
internal object Ipv4Beacon {

    /**
     * The A-record-only host to beacon. MUST be a domain configured with ONLY A records (no
     * AAAA) so the request is forced to resolve over IPv4. Blank = DISABLED.
     */
    const val DEFAULT_URL = "https://ip4.simula.ad/px"

    const val REASON_INIT = "init"
    const val REASON_PPID_UPDATE = "ppid_update"

    // Collaborators are mutable internals so unit tests can substitute fakes (the object has no
    // constructor to inject through); production never touches them.
    @Volatile
    internal var url: String = DEFAULT_URL
    internal var scope: CoroutineScope = SimulaScope
    internal var send: suspend (String) -> Boolean = { u ->
        // instrument=false: a different host than the API — keep it out of API network telemetry.
        SimulaHttp.request(u, instrument = false).isSuccessful
    }
    internal var deviceIdProvider: () -> String? = { SimulaDeviceId.value }
    internal var clock: () -> Long = System::currentTimeMillis

    private val lock = Any()

    /** Identities whose beacon has SUCCESSFULLY fired this process (failures stay retryable). */
    private val captured = mutableSetOf<String>()

    /** Identities with a beacon in flight — claimed under [lock] so parallel fires coalesce. */
    private val inFlight = mutableSetOf<String>()

    /**
     * Bumped on every [onLogout]. A fire captures the generation it started with and re-checks
     * it before recording; a mismatch means the session moved on mid-flight, so the completion
     * is discarded instead of resurrecting stale dedup state after a logout reset.
     */
    private var generation = 0

    /**
     * Fire (fire-and-forget) for the given identity. Deduped per (apiKey, sessionId, ppid) —
     * see the class doc. Never throws.
     */
    fun fire(apiKey: String, sessionId: String?, ppid: String?, reason: String) {
        val base = url.trim()
        if (base.isEmpty() || apiKey.isBlank()) return

        // NUL-joined (not naive concatenation) so distinct identities can never collide onto
        // the same key, e.g. apiKey="ab",sid="c" vs apiKey="a",sid="bc".
        val key = listOf(apiKey, sessionId, ppid).joinToString("\u0000")
        val gen: Int
        synchronized(lock) {
            if (key in captured || key in inFlight) return
            inFlight.add(key)
            gen = generation
        }

        scope.launch {
            val ok = runCatching {
                send(buildUrl(base, apiKey, sessionId, ppid, deviceIdProvider(), reason, clock()))
            }.getOrDefault(false)
            synchronized(lock) {
                // A logout while in flight already cleared this key; bail instead of
                // resurrecting a stale captured/in-flight entry into the new session.
                if (gen != generation) return@launch
                inFlight.remove(key)
                if (ok) captured.add(key)
            }
        }
    }

    /**
     * Logout ends the capture session: clear the dedup bookkeeping (and invalidate anything
     * still in flight via the generation bump) so a later re-login — even with the same ppid —
     * runs a fresh capture instead of being skipped by stale state.
     */
    fun onLogout() {
        synchronized(lock) {
            generation++
            captured.clear()
            inFlight.clear()
        }
    }

    /** Pure URL construction — exposed for tests. */
    internal fun buildUrl(
        base: String,
        apiKey: String,
        sessionId: String?,
        ppid: String?,
        deviceId: String?,
        reason: String,
        timestamp: Long,
    ): String {
        val params = buildList {
            add("k" to apiKey)
            sessionId?.takeIf { it.isNotBlank() }?.let { add("sid" to it) }
            ppid?.takeIf { it.isNotBlank() }?.let { add("ppid" to it) }
            deviceId?.takeIf { it.isNotBlank() }?.let { add("did" to it) }
            add("p" to "android")
            add("r" to reason)
            add("t" to timestamp.toString())
        }
        val qs = params.joinToString("&") { (name, value) ->
            "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        return "$base${if ("?" in base) "&" else "?"}$qs"
    }

    /** Test-only: reset dedup state and restore production collaborators. */
    internal fun resetForTests() {
        synchronized(lock) {
            generation = 0
            captured.clear()
            inFlight.clear()
        }
        url = DEFAULT_URL
        scope = SimulaScope
        send = { u -> SimulaHttp.request(u, instrument = false).isSuccessful }
        deviceIdProvider = { SimulaDeviceId.value }
        clock = System::currentTimeMillis
    }
}
