package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.core.SimulaScope
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/**
 * Live network-connection-type signal for the `X-Connection-Type` request header (OpenRTB
 * `device.connectiontype` enum) and telemetry's `connection_type` label.
 *
 * A single [ConnectivityManager.registerDefaultNetworkCallback] runs for the life of the
 * process — registered once from [prime], **independent of the telemetry opt-out** — and keeps
 * [value] fresh on every change to the system's *default* (actually-used-for-internet) network,
 * so [SimulaHttp.open] reads a cached int with zero per-request cost. A transport switch (e.g.
 * Wi-Fi → cellular) updates [value] via the callback, so the very next request carries the new
 * value.
 *
 * Deliberately uses `registerDefaultNetworkCallback`, **not** a plain
 * `registerNetworkCallback(request, ...)`: the latter fires for *every* network matching the
 * request, including a background radio a dual-stack device keeps up for fast handoff (e.g.
 * cellular staying "available" while Wi-Fi is the active default) — that would let a background
 * cellular event clobber the cache with `3-7` even though Wi-Fi is still the real connection.
 * `registerDefaultNetworkCallback` only reports the network actually carrying traffic.
 *
 * Requires only `ACCESS_NETWORK_STATE` — already declared in the manifest (normal, install-time,
 * no runtime prompt) for the SDK's telemetry — for the coarse transport (0/1/2/3). Cellular
 * generation (4-7) is an **opportunistic** refinement via `TelephonyManager.dataNetworkType`,
 * attempted only if the host app already holds `READ_PHONE_STATE`; a missing grant throws
 * [SecurityException], caught here, and the SDK simply sends `3` (cellular, unknown gen). No new
 * permission is requested or required.
 *
 * OpenRTB `connectiontype`: `0` unknown/offline · `1` wired · `2` wifi · `3` cellular (unknown
 * gen) · `4` 2G · `5` 3G · `6` 4G · `7` 5G.
 */
internal object SimulaConnectionType {

    /** The current OpenRTB `connectiontype` value. `0` (unknown/offline) until [prime] resolves
     *  the first reading (self-corrects within milliseconds; the very first request may go
     *  without a meaningful value — the same contract as [SimulaDeviceId]). */
    @Volatile
    var value: Int = 0
        private set

    /** Ensures registration is kicked off at most once. */
    private val priming = AtomicBoolean(false)

    /**
     * Registers the network callback (idempotent) and seeds [value] from the currently active
     * network. Safe to call from the main thread — registration itself is lightweight; the
     * one-time seed read (and any binder/Telephony cost) runs on [SimulaScope] (IO), off the
     * app-start / composition critical path, mirroring [SimulaDeviceId.prime].
     */
    fun prime(context: Context) {
        if (!priming.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        SimulaScope.launch {
            runCatching {
                val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return@runCatching
                // Seed synchronously from the current default network so the very first request
                // (before any callback fires) gets a best-effort value instead of staying at 0
                // for the whole session.
                val caps = runCatching { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
                value = classify(appContext, caps)
                registerCallback(appContext, cm)
            }
        }
    }

    /**
     * Registers the default-network callback. `onAvailable`/`onCapabilitiesChanged` only fire for
     * the network the system currently routes internet traffic through — never a background
     * radio — so [value] can never be clobbered by a non-default network's state.
     */
    private fun registerCallback(context: Context, cm: ConnectivityManager) {
        cm.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
                    value = classify(context, caps)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    value = classify(context, caps)
                }

                // The default network is gone; briefly unknown/offline until the system picks
                // (and reports via onAvailable) a new default, if any.
                override fun onLost(network: Network) {
                    value = 0
                }
            },
        )
    }

    /** Resolves [caps] to the OpenRTB value, refining cellular via [context] when applicable. */
    private fun classify(context: Context, caps: NetworkCapabilities?): Int {
        if (caps == null) return 0
        return classify(
            isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            cellularGeneration = { cellularGeneration(context) },
        )
    }

    /**
     * Pure transport -> OpenRTB mapping, testable without a real [NetworkCapabilities] instance.
     * `cellularGeneration` is only invoked when [isCellular] is true (the Telephony read is
     * skipped on wifi/wired/offline).
     */
    internal fun classify(
        isWifi: Boolean,
        isEthernet: Boolean,
        isCellular: Boolean,
        cellularGeneration: () -> Int,
    ): Int = when {
        isWifi -> 2
        isEthernet -> 1
        isCellular -> cellularGeneration()
        else -> 0
    }

    /**
     * Opportunistic cellular-generation refinement via `TelephonyManager.dataNetworkType`.
     * Reading it can require `READ_PHONE_STATE` on some OEM/API combinations; a missing grant
     * throws [SecurityException], caught here — this can never crash the host, and no new
     * permission is requested. Falls back to `3` (cellular, unknown gen) on any failure, on API
     * levels below N, or when the type is undeterminable.
     */
    private fun cellularGeneration(context: Context): Int = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@runCatching 3
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return@runCatching 3
        generationForNetworkType(tm.dataNetworkType)
    }.getOrDefault(3)

    /** Pure `TelephonyManager.NETWORK_TYPE_*` -> OpenRTB generation mapping (testable — these are
     *  plain int constants, no live [TelephonyManager] needed). Mirrors `Telemetry.resolveRadio`'s
     *  string mapping, in OpenRTB int form. */
    internal fun generationForNetworkType(networkType: Int): Int = when (networkType) {
        TelephonyManager.NETWORK_TYPE_NR -> 7
        TelephonyManager.NETWORK_TYPE_LTE -> 6
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        -> 5
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_CDMA,
        -> 4
        else -> 3
    }

    /**
     * Coarse label (`wifi` / `cellular` / `unknown`) for telemetry's `connection_type` field,
     * derived from [value] so the header and telemetry never disagree. `"none"` (offline) isn't
     * distinguishable from this cached int alone; callers that need it can keep resolving that
     * case separately (as `Telemetry.resolveConnectionType` already did before this signal
     * existed) — in practice an offline device won't be flushing telemetry anyway.
     */
    val label: String
        get() = when (value) {
            1, 2 -> "wifi"
            in 3..7 -> "cellular"
            else -> "unknown"
        }
}
