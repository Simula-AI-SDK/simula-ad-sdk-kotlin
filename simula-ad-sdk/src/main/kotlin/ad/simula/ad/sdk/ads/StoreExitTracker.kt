package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.network.BeaconPersistenceOutcome
import ad.simula.ad.sdk.network.CLICK_PERSISTENCE_WAIT_MS
import ad.simula.ad.sdk.network.ClickInteraction
import ad.simula.ad.sdk.network.ClickInteractionClaim
import ad.simula.ad.sdk.network.ClickPersistenceHandoff
import ad.simula.ad.sdk.network.ClickPersistencePart
import ad.simula.ad.sdk.network.ClickRouteStart
import ad.simula.ad.sdk.network.PresentationRouteResult
import ad.simula.ad.sdk.network.ClickSources
import ad.simula.ad.sdk.telemetry.Telemetry
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** One presentation's HTML store request, including cancellation before an external handoff. */
internal class CreativeStoreRequestGate {
    private var suppressed = false
    private var handoff: ClickPersistenceHandoff? = null

    @Synchronized
    fun canRequest(): Boolean = !suppressed && handoff == null

    fun track(candidate: ClickPersistenceHandoff): Boolean {
        val accepted = synchronized(this) {
            if (suppressed || handoff != null) false else {
                handoff = candidate
                true
            }
        }
        if (!accepted) candidate.cancel()
        return accepted
    }

    @Synchronized
    fun canOpen(): Boolean = !suppressed

    @Synchronized
    fun finish(candidate: ClickPersistenceHandoff) {
        if (handoff === candidate) handoff = null
    }

    fun dismiss() {
        val pending = synchronized(this) {
            suppressed = true
            handoff.also { handoff = null }
        }
        pending?.cancel()
    }
}

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

internal fun <T : Any> prepareDeferredCtaRoute(
    prepare: suspend () -> PreparedCtaOpen,
    requestRoute: (route: (T) -> Boolean, completion: (Boolean) -> Unit) -> PresentationRouteResult,
    completion: (Boolean) -> Unit,
    open: (T, PreparedCtaOpen) -> Boolean,
): ClickRouteStart {
    CreativeCtaRouter.prepareInBackground(prepare) { prepared ->
        val result = requestRoute({ host -> open(host, prepared) }, completion)
        if (result == PresentationRouteResult.REJECTED) completion(false)
    }
    return ClickRouteStart.STARTED
}

internal fun prepareAutomaticCtaRoute(
    gate: AutomaticNavigationGate,
    lifecycle: Lifecycle,
    prepare: suspend (PendingAutomaticNavigation) -> PreparedCtaOpen,
    canOpen: () -> Boolean,
    open: (PreparedCtaOpen) -> AutomaticNavigationOutcome,
    completion: (Boolean) -> Unit,
    registerCancellation: (() -> Unit) -> Unit,
) {
    val attempt = gate.beginPending()
    if (attempt == null) {
        completion(false)
        return
    }
    val job = CreativeCtaRouter.prepareInBackground(
        prepare = { prepare(attempt.route) },
        onPrepared = { prepared ->
            runWhenLifecycleResumed(
                lifecycle = lifecycle,
                canRun = { gate.isActive(attempt) && canOpen() },
                onResumed = {
                    val outcome = runCatching { open(prepared) }
                        .getOrDefault(AutomaticNavigationOutcome.FAILED)
                    gate.complete(attempt, outcome)
                    completion(outcome != AutomaticNavigationOutcome.FAILED)
                },
                onUnavailable = {
                    gate.complete(attempt, AutomaticNavigationOutcome.FAILED)
                    completion(false)
                },
            )
        },
    )
    registerCancellation {
        job.cancel()
        gate.abandonInFlight()
    }
}

internal fun runWhenLifecycleResumed(
    lifecycle: Lifecycle,
    canRun: () -> Boolean,
    onResumed: () -> Unit,
    onUnavailable: () -> Unit,
) {
    if (!canRun() || lifecycle.currentState == Lifecycle.State.DESTROYED) {
        onUnavailable()
        return
    }
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        onResumed()
        return
    }
    lateinit var observer: LifecycleEventObserver
    observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                lifecycle.removeObserver(observer)
                if (canRun()) onResumed() else onUnavailable()
            }
            Lifecycle.Event.ON_DESTROY -> {
                lifecycle.removeObserver(observer)
                onUnavailable()
            }
            else -> Unit
        }
    }
    lifecycle.addObserver(observer)
}

internal fun canDismissFullscreen(
    dismissUnlocked: Boolean,
    clickHandoffPending: Boolean,
    displayAdmitted: Boolean = true,
    storeVisitPending: Boolean = false,
): Boolean = displayAdmitted && dismissUnlocked && !clickHandoffPending && !storeVisitPending

internal fun shouldExitUnavailableCreative(
    creativeUnavailable: Boolean,
    clickHandoffPending: Boolean,
    storeVisitPending: Boolean,
): Boolean = creativeUnavailable && !clickHandoffPending && !storeVisitPending

internal enum class StoreVisitPhase { NONE, LAUNCHING, AWAY }

internal data class ResolvedStoreVisit(val trigger: String, val openedAtMs: Long)

internal class StoreVisitLifecycle {
    var phase: StoreVisitPhase = StoreVisitPhase.NONE
        private set
    private var trigger: String? = null
    private var openedAtMs = 0L

    fun open(trigger: String, openedAtMs: Long): ResolvedStoreVisit? {
        val replaced = abandon()
        this.trigger = trigger
        this.openedAtMs = openedAtMs
        phase = StoreVisitPhase.LAUNCHING
        return replaced
    }

    fun pause(): Boolean {
        if (phase != StoreVisitPhase.LAUNCHING) return false
        phase = StoreVisitPhase.AWAY
        return true
    }

    fun resume(): ResolvedStoreVisit? =
        if (phase == StoreVisitPhase.AWAY) resolve() else null

    fun launchTimedOut(): ResolvedStoreVisit? =
        if (phase == StoreVisitPhase.LAUNCHING) resolve() else null

    fun abandon(): ResolvedStoreVisit? =
        if (phase == StoreVisitPhase.NONE) null else resolve()

    private fun resolve(): ResolvedStoreVisit? {
        val currentTrigger = trigger ?: return null
        val resolved = ResolvedStoreVisit(currentTrigger, openedAtMs)
        trigger = null
        openedAtMs = 0L
        phase = StoreVisitPhase.NONE
        return resolved
    }
}

internal const val STORE_LAUNCH_SETTLE_MS = 2_000L

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
    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val visit = StoreVisitLifecycle()
    private val launchTimeout = Runnable {
        val timedOut = visit.launchTimedOut()
        if (timedOut != null) {
            Telemetry.recordError(
                signature = "store:launch_no_pause",
                breadcrumb = "surface=fullscreen",
            )
        }
        storeVisitPending = visit.phase != StoreVisitPhase.NONE
    }
    private var foregroundMs: Long = 0L
    private var resumedAt: Long = SystemClock.elapsedRealtime()
    private var inForeground: Boolean = true

    private var storeVisitPending by mutableStateOf(false)

    fun hasPendingStoreVisit(): Boolean = storeVisitPending

    /** Activity resumed. A resume while a store visit is outstanding is the return from the store. */
    fun onResume() {
        val now = SystemClock.elapsedRealtime()
        if (!inForeground) {
            resumedAt = now
            inForeground = true
        }
        val resolved = visit.resume() ?: return
        mainHandler.removeCallbacks(launchTimeout)
        storeVisitPending = false
        Telemetry.recordLifecycle(
            stage = "store_returned",
            adFormat = adFormat,
            adUnitId = adUnitId,
            adId = adId,
            serveId = adId.takeIf { adFormat == "interstitial" || adFormat == "rewarded" },
            durationMs = (now - resolved.openedAtMs).coerceAtLeast(0L), // time away
            errorCode = null,
            trigger = resolved.trigger,
        )
    }

    /** Activity paused — bank the foreground time accrued since the last resume. */
    fun onPause() {
        if (inForeground) {
            foregroundMs += (SystemClock.elapsedRealtime() - resumedAt).coerceAtLeast(0L)
            inForeground = false
        }
        if (visit.pause()) mainHandler.removeCallbacks(launchTimeout)
    }

    /**
     * A CTA / store-prompt / auto-redirect opened the store. The legacy store-exit dimension keeps
     * `cta` while the separate click-source contract uses `primary_cta`.
     */
    fun recordStoreOpen(trigger: String) {
        val now = SystemClock.elapsedRealtime()
        val dwellMs = foregroundMs + if (inForeground) (now - resumedAt).coerceAtLeast(0L) else 0L
        val storeExitTrigger = ClickSources.storeExitTrigger(trigger)
        visit.open(storeExitTrigger, now)?.let(::recordAbandoned)
        storeVisitPending = true
        mainHandler.removeCallbacks(launchTimeout)
        mainHandler.postDelayed(launchTimeout, STORE_LAUNCH_SETTLE_MS)
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
        mainHandler.removeCallbacks(launchTimeout)
        val resolved = visit.abandon() ?: return
        storeVisitPending = false
        recordAbandoned(resolved)
    }

    private fun recordAbandoned(resolved: ResolvedStoreVisit) {
        Telemetry.recordLifecycle(
            stage = "store_abandoned",
            adFormat = adFormat,
            adUnitId = adUnitId,
            adId = adId,
            serveId = adId.takeIf { adFormat == "interstitial" || adFormat == "rewarded" },
            durationMs = null,
            errorCode = null,
            trigger = resolved.trigger,
        )
    }
}
