package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.network.BeaconPersistenceOutcome
import ad.simula.ad.sdk.network.CLICK_PERSISTENCE_WAIT_MS
import ad.simula.ad.sdk.network.ClickInteraction
import ad.simula.ad.sdk.network.ClickInteractionClaim
import ad.simula.ad.sdk.network.ClickPersistenceHandoff
import ad.simula.ad.sdk.network.ClickPersistencePart
import ad.simula.ad.sdk.network.ClickRouteStart
import ad.simula.ad.sdk.network.ClickSources
import ad.simula.ad.sdk.telemetry.Telemetry
import android.os.Handler
import android.os.SystemClock

internal interface ClickHandoffScheduler {
    fun post(block: Runnable)
    fun postDelayed(block: Runnable, delayMs: Long)
    fun remove(block: Runnable)
}

private class HandlerClickHandoffScheduler(
    private val handler: Handler,
) : ClickHandoffScheduler {
    override fun post(block: Runnable) { handler.post(block) }
    override fun postDelayed(block: Runnable, delayMs: Long) { handler.postDelayed(block, delayMs) }
    override fun remove(block: Runnable) { handler.removeCallbacks(block) }
}

/**
 * Wait for both click durability attempts without doing I/O on the caller thread. Every queue
 * success releases its barrier part; failures remain queued for recovery and fall through the short
 * timeout so a broken/wedged store cannot swallow navigation indefinitely.
 */
internal fun coordinateClickPersistence(
    mainHandler: Handler,
    claim: ClickInteractionClaim,
    enqueueBeacon: ((BeaconPersistenceOutcome) -> Unit) -> Unit,
    recordTelemetry: (() -> Unit) -> Unit,
    onHandoff: (ClickInteraction) -> Boolean,
    onCreated: (ClickPersistenceHandoff) -> Unit = {},
    onFinished: (ClickPersistenceHandoff) -> Unit = {},
): ClickPersistenceHandoff = coordinateClickPersistence(
    scheduler = HandlerClickHandoffScheduler(mainHandler),
    claim = claim,
    enqueueBeacon = enqueueBeacon,
    recordTelemetry = recordTelemetry,
    onHandoff = onHandoff,
    onCreated = onCreated,
    onFinished = onFinished,
)

internal fun coordinateClickPersistence(
    scheduler: ClickHandoffScheduler,
    claim: ClickInteractionClaim,
    enqueueBeacon: ((BeaconPersistenceOutcome) -> Unit) -> Unit,
    recordTelemetry: (() -> Unit) -> Unit,
    onHandoff: (ClickInteraction) -> Boolean,
    onCreated: (ClickPersistenceHandoff) -> Unit = {},
    onFinished: (ClickPersistenceHandoff) -> Unit = {},
): ClickPersistenceHandoff {
    lateinit var handoff: ClickPersistenceHandoff
    lateinit var timeout: Runnable
    val route = Runnable {
        handoff.handoff(onHandoff)
        runCatching { onFinished(handoff) }
    }
    timeout = Runnable {
        if (!handoff.timeout()) return@Runnable
        Telemetry.recordError(
            signature = "click:persistence_timeout",
            breadcrumb = "handoff=external",
        )
    }
    handoff = ClickPersistenceHandoff(claim) {
        scheduler.remove(timeout)
        scheduler.post(route)
    }
    runCatching { onCreated(handoff) }.onFailure { handoff.cancel() }
    if (handoff.isTerminal()) return handoff
    scheduler.postDelayed(timeout, CLICK_PERSISTENCE_WAIT_MS)
    runCatching {
        enqueueBeacon { outcome ->
            if (outcome == BeaconPersistenceOutcome.Persisted) {
                handoff.complete(ClickPersistencePart.BEACON)
            }
        }
    }
    runCatching {
        recordTelemetry { handoff.complete(ClickPersistencePart.TELEMETRY) }
    }
    return handoff
}

internal fun coordinateDeferredClickPersistence(
    mainHandler: Handler,
    claim: ClickInteractionClaim,
    enqueueBeacon: ((BeaconPersistenceOutcome) -> Unit) -> Unit,
    recordTelemetry: (() -> Unit) -> Unit,
    onHandoff: (ClickInteraction, (Boolean) -> Unit) -> ClickRouteStart,
    onCreated: (ClickPersistenceHandoff) -> Unit = {},
    onFinished: (ClickPersistenceHandoff) -> Unit = {},
    recordPersistenceIssue: (String, String) -> Unit = { signature, breadcrumb ->
        Telemetry.recordError(signature = signature, breadcrumb = breadcrumb)
    },
): ClickPersistenceHandoff = coordinateDeferredClickPersistence(
    scheduler = HandlerClickHandoffScheduler(mainHandler),
    claim = claim,
    enqueueBeacon = enqueueBeacon,
    recordTelemetry = recordTelemetry,
    onHandoff = onHandoff,
    onCreated = onCreated,
    onFinished = onFinished,
    recordPersistenceIssue = recordPersistenceIssue,
)

internal fun coordinateDeferredClickPersistence(
    scheduler: ClickHandoffScheduler,
    claim: ClickInteractionClaim,
    enqueueBeacon: ((BeaconPersistenceOutcome) -> Unit) -> Unit,
    recordTelemetry: (() -> Unit) -> Unit,
    onHandoff: (ClickInteraction, (Boolean) -> Unit) -> ClickRouteStart,
    onCreated: (ClickPersistenceHandoff) -> Unit = {},
    onFinished: (ClickPersistenceHandoff) -> Unit = {},
    recordPersistenceIssue: (String, String) -> Unit = { signature, breadcrumb ->
        Telemetry.recordError(signature = signature, breadcrumb = breadcrumb)
    },
): ClickPersistenceHandoff {
    lateinit var handoff: ClickPersistenceHandoff
    lateinit var timeout: Runnable
    val route = Runnable { handoff.handoffAsync(onHandoff) }
    timeout = Runnable {
        if (!handoff.timeout()) return@Runnable
        Telemetry.recordError(
            signature = "click:persistence_timeout",
            breadcrumb = "handoff=external",
        )
    }
    handoff = ClickPersistenceHandoff(claim) {
        scheduler.remove(timeout)
        scheduler.post(route)
    }
    val subscription = handoff.addResultListener {
        scheduler.post(Runnable { runCatching { onFinished(handoff) } })
    }
    runCatching { onCreated(handoff) }.onFailure { handoff.cancel() }
    if (handoff.isTerminal()) {
        subscription.cancel()
        return handoff
    }
    scheduler.postDelayed(timeout, CLICK_PERSISTENCE_WAIT_MS)
    runCatching {
        enqueueBeacon { outcome ->
            when (outcome) {
                BeaconPersistenceOutcome.Persisted -> handoff.complete(ClickPersistencePart.BEACON)
                BeaconPersistenceOutcome.Rejected, BeaconPersistenceOutcome.Unavailable -> {
                    runCatching {
                        recordPersistenceIssue(
                            "click:persistence_rejected",
                            "part=beacon;outcome=${outcome.name.lowercase()}",
                        )
                    }
                    handoff.complete(ClickPersistencePart.BEACON)
                }
                BeaconPersistenceOutcome.RetryableFailure -> runCatching {
                    recordPersistenceIssue("click:persistence_retryable", "part=beacon")
                }
            }
        }
    }
    runCatching {
        recordTelemetry { handoff.complete(ClickPersistencePart.TELEMETRY) }
    }
    return handoff
}

internal fun canDismissFullscreen(dismissUnlocked: Boolean, hasPendingClick: Boolean): Boolean =
    dismissUnlocked && !hasPendingClick

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
     * A CTA / store-prompt / auto-redirect opened the store. The legacy store-exit dimension keeps
     * `cta` while the separate click-source contract uses `primary_cta`.
     */
    fun recordStoreOpen(trigger: String) {
        val now = SystemClock.elapsedRealtime()
        val dwellMs = foregroundMs + if (inForeground) (now - resumedAt).coerceAtLeast(0L) else 0L
        val storeExitTrigger = ClickSources.storeExitTrigger(trigger)
        openedAt = now
        pendingTrigger = storeExitTrigger
        Telemetry.recordLifecycle(
            stage = "store_opened",
            adFormat = adFormat,
            adUnitId = adUnitId,
            adId = adId,
            serveId = adId.takeIf { adFormat == "interstitial" || adFormat == "rewarded" },
            durationMs = dwellMs, // foreground time before leaving
            errorCode = null,
            trigger = storeExitTrigger,
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
