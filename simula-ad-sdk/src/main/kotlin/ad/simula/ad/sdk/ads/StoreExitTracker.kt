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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

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

internal data class ResolvedStoreVisit(
    val trigger: String,
    val openedAtMs: Long,
    val opens: Int,
    val contaminated: Boolean?,
)

internal class StoreVisitLifecycle {
    var phase: StoreVisitPhase = StoreVisitPhase.NONE
        private set
    private var trigger: String? = null
    private var openedAtMs = 0L
    private var openCount = 0
    private var contaminated: Boolean? = null

    fun open(trigger: String, openedAtMs: Long): Boolean {
        if (phase != StoreVisitPhase.NONE) return false
        this.trigger = trigger
        this.openedAtMs = openedAtMs
        contaminated = null
        phase = StoreVisitPhase.LAUNCHING
        return true
    }

    fun observeContamination() {
        if (phase != StoreVisitPhase.NONE && contaminated == null) contaminated = false
    }

    fun contaminate() {
        if (phase != StoreVisitPhase.NONE) contaminated = true
    }

    fun pause(): ResolvedStoreVisit? {
        if (phase != StoreVisitPhase.LAUNCHING) return null
        openCount = (openCount + 1).coerceAtMost(MAX_STORE_OPENS)
        phase = StoreVisitPhase.AWAY
        return snapshot()
    }

    fun resume(): ResolvedStoreVisit? =
        if (phase == StoreVisitPhase.AWAY) resolve() else null

    fun launchTimedOut(): Boolean {
        if (phase != StoreVisitPhase.LAUNCHING) return false
        clear()
        return true
    }

    fun abandon(): ResolvedStoreVisit? =
        when (phase) {
            StoreVisitPhase.AWAY -> resolve()
            StoreVisitPhase.LAUNCHING -> null.also { clear() }
            StoreVisitPhase.NONE -> null
        }

    private fun snapshot(): ResolvedStoreVisit? {
        val currentTrigger = trigger ?: return null
        return ResolvedStoreVisit(currentTrigger, openedAtMs, openCount, contaminated)
    }

    private fun resolve(): ResolvedStoreVisit? {
        val resolved = snapshot() ?: return null
        clear()
        return resolved
    }

    private fun clear() {
        trigger = null
        openedAtMs = 0L
        contaminated = null
        phase = StoreVisitPhase.NONE
    }

    private companion object {
        const val MAX_STORE_OPENS = 1_000
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
    private var appContext: Context? = null
    private var screenReceiverRegistered = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) visit.contaminate()
        }
    }
    private val launchTimeout = Runnable {
        val timedOut = visit.launchTimedOut()
        if (timedOut) {
            unregisterScreenOffReceiver()
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
    private var pendingDwellMs: Long = 0L

    private var storeVisitPending by mutableStateOf(false)

    fun hasPendingStoreVisit(): Boolean = storeVisitPending

    /** Retains only the application context so screen-off can be observed while Play owns foreground. */
    fun attach(context: Context) {
        appContext = context.applicationContext
        if (visit.phase != StoreVisitPhase.NONE) registerScreenOffReceiver()
    }

    /** Activity resumed. A resume while a store visit is outstanding is the return from the store. */
    fun onResume() {
        val now = SystemClock.elapsedRealtime()
        if (!inForeground) {
            resumedAt = now
            inForeground = true
        }
        val resolved = visit.resume() ?: return
        mainHandler.removeCallbacks(launchTimeout)
        unregisterScreenOffReceiver()
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
            endEvent = "activity_resumed",
            opens = resolved.opens,
            contaminated = resolved.contaminated,
        )
    }

    /** Activity paused — bank the foreground time accrued since the last resume. */
    fun onPause() {
        if (inForeground) {
            foregroundMs += (SystemClock.elapsedRealtime() - resumedAt).coerceAtLeast(0L)
            inForeground = false
        }
        val confirmed = visit.pause() ?: return
        mainHandler.removeCallbacks(launchTimeout)
        Telemetry.recordLifecycle(
            stage = "store_opened",
            adFormat = adFormat,
            adUnitId = adUnitId,
            adId = adId,
            serveId = adId.takeIf { adFormat == "interstitial" || adFormat == "rewarded" },
            durationMs = pendingDwellMs,
            errorCode = null,
            trigger = confirmed.trigger,
            opens = confirmed.opens,
        )
    }

    /**
     * A CTA / store-prompt / auto-redirect opened the store. The legacy store-exit dimension keeps
     * `cta` while the separate click-source contract uses `primary_cta`.
     */
    fun recordStoreOpen(trigger: String) {
        val now = SystemClock.elapsedRealtime()
        val dwellMs = foregroundMs + if (inForeground) (now - resumedAt).coerceAtLeast(0L) else 0L
        val storeExitTrigger = ClickSources.storeExitTrigger(trigger)
        if (!visit.open(storeExitTrigger, now)) return
        pendingDwellMs = dwellMs
        storeVisitPending = true
        registerScreenOffReceiver()
        mainHandler.removeCallbacks(launchTimeout)
        mainHandler.postDelayed(launchTimeout, STORE_LAUNCH_SETTLE_MS)
    }

    /** The ad closed / tore down. If a store visit never resolved with a return, it's an abandon. */
    fun onAdClosed() {
        mainHandler.removeCallbacks(launchTimeout)
        val resolved = visit.abandon()
        unregisterScreenOffReceiver()
        storeVisitPending = false
        if (resolved != null) recordAbandoned(resolved, endEvent = "ad_closed")
    }

    private fun recordAbandoned(resolved: ResolvedStoreVisit, endEvent: String?) {
        Telemetry.recordLifecycle(
            stage = "store_abandoned",
            adFormat = adFormat,
            adUnitId = adUnitId,
            adId = adId,
            serveId = adId.takeIf { adFormat == "interstitial" || adFormat == "rewarded" },
            durationMs = null,
            errorCode = null,
            trigger = resolved.trigger,
            endEvent = endEvent,
            opens = resolved.opens,
            contaminated = resolved.contaminated,
        )
    }

    private fun registerScreenOffReceiver() {
        val context = appContext ?: return
        if (screenReceiverRegistered) return
        screenReceiverRegistered = runCatching {
            ContextCompat.registerReceiver(
                context,
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            visit.observeContamination()
            true
        }.onFailure {
            Telemetry.recordError(
                signature = "store:screen_observer_unavailable",
                breadcrumb = "surface=fullscreen",
            )
        }.getOrDefault(false)
    }

    private fun unregisterScreenOffReceiver() {
        if (!screenReceiverRegistered) return
        val context = appContext
        screenReceiverRegistered = false
        if (context != null) runCatching { context.unregisterReceiver(screenOffReceiver) }
    }
}
