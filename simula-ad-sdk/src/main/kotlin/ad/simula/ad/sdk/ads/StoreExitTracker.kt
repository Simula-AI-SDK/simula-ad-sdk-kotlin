package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.network.BeaconPersistenceOutcome
import ad.simula.ad.sdk.network.CLICK_PERSISTENCE_WAIT_MS
import ad.simula.ad.sdk.network.ClickPersistenceBarrier
import ad.simula.ad.sdk.network.ClickPersistencePart
import ad.simula.ad.sdk.telemetry.Telemetry
import android.os.Handler
import android.os.SystemClock

/**
 * Wait for both click durability attempts without doing I/O on the caller thread. Every queue
 * success releases its barrier part; failures remain queued for recovery and fall through the short
 * timeout so a broken/wedged store cannot swallow navigation indefinitely.
 */
internal fun coordinateClickPersistence(
    mainHandler: Handler,
    enqueueBeacon: ((BeaconPersistenceOutcome) -> Unit) -> Unit,
    recordTelemetry: (() -> Unit) -> Unit,
    onReady: () -> Unit,
) {
    lateinit var barrier: ClickPersistenceBarrier
    val timeout = Runnable { barrier.timeout() }
    barrier = ClickPersistenceBarrier { timedOut ->
        mainHandler.removeCallbacks(timeout)
        if (timedOut) {
            Telemetry.recordError(
                signature = "click:persistence_timeout",
                breadcrumb = "handoff=external",
            )
        }
        mainHandler.post(onReady)
    }
    mainHandler.postDelayed(timeout, CLICK_PERSISTENCE_WAIT_MS)
    runCatching {
        enqueueBeacon { outcome ->
            if (outcome == BeaconPersistenceOutcome.Persisted) {
                barrier.complete(ClickPersistencePart.BEACON)
            }
        }
    }
    runCatching {
        recordTelemetry { barrier.complete(ClickPersistencePart.TELEMETRY) }
    }.onFailure {
        barrier.complete(ClickPersistencePart.TELEMETRY)
    }
}

/**
 * Tracks the store-exit funnel for a single full-screen ad presentation: which click type sent the
 * user to the store, how long they were away, and whether they came back. Emits `store_opened`,
 * `store_returned`, and `store_abandoned` `ad_lifecycle` telemetry (PRD "Better Telemetry Tracking").
 *
 * All methods are **main-thread only** — driven by the host Activity's lifecycle callbacks and Compose
 * click handlers — so no synchronization is needed. Timing uses the monotonic [SystemClock.elapsedRealtime]
 * clock, which keeps counting while the app is backgrounded: exactly what "time away" requires.
 * Every emit is best-effort ([Telemetry.recordLifecycle] never throws into the host).
 */
internal class StoreExitTracker(
    private val adId: String?,
    private val adFormat: String?,
    private val adUnitId: String? = null,
) {
    private var foregroundMs: Long = 0L
    private var resumedAt: Long = SystemClock.elapsedRealtime()
    private var inForeground: Boolean = true

    // The in-flight store visit, if any (the trigger that opened it + when it opened).
    private var pendingTrigger: String? = null
    private var openedAt: Long = 0L

    /** Activity resumed. A resume while a store visit is outstanding is the return from the store. */
    fun onResume() {
        val now = SystemClock.elapsedRealtime()
        if (!inForeground) {
            resumedAt = now
            inForeground = true
        }
        val trigger = pendingTrigger ?: return
        pendingTrigger = null
        Telemetry.recordLifecycle(
            stage = "store_returned",
            adFormat = adFormat,
            adUnitId = adUnitId,
            adId = adId,
            serveId = adId.takeIf { adFormat == "interstitial" || adFormat == "rewarded" },
            durationMs = (now - openedAt).coerceAtLeast(0L), // time away
            errorCode = null,
            trigger = trigger,
        )
    }

    /** Activity paused — bank the foreground time accrued since the last resume. */
    fun onPause() {
        if (inForeground) {
            foregroundMs += (SystemClock.elapsedRealtime() - resumedAt).coerceAtLeast(0L)
            inForeground = false
        }
    }

    /**
     * A CTA / store-prompt / auto-redirect opened the store. [trigger] is one of
     * `primary_cta` / `store_prompt` / `install_banner` / `auto_redirect`. `durationMs` carries the
     * foreground dwell at open.
     */
    fun recordStoreOpen(trigger: String) {
        val now = SystemClock.elapsedRealtime()
        val dwellMs = foregroundMs + if (inForeground) (now - resumedAt).coerceAtLeast(0L) else 0L
        openedAt = now
        pendingTrigger = trigger
        Telemetry.recordLifecycle(
            stage = "store_opened",
            adFormat = adFormat,
            adUnitId = adUnitId,
            adId = adId,
            serveId = adId.takeIf { adFormat == "interstitial" || adFormat == "rewarded" },
            durationMs = dwellMs, // foreground time before leaving
            errorCode = null,
            trigger = trigger,
        )
    }

    /** The ad closed / tore down. If a store visit never resolved with a return, it's an abandon. */
    fun onAdClosed() {
        val trigger = pendingTrigger ?: return
        pendingTrigger = null
        Telemetry.recordLifecycle(
            stage = "store_abandoned",
            adFormat = adFormat,
            adUnitId = adUnitId,
            adId = adId,
            serveId = adId.takeIf { adFormat == "interstitial" || adFormat == "rewarded" },
            durationMs = null,
            errorCode = null,
            trigger = trigger,
        )
    }
}
