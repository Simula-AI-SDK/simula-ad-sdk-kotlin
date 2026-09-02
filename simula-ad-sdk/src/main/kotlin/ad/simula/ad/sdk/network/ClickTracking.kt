package ad.simula.ad.sdk.network

import androidx.annotation.MainThread
import java.lang.ref.WeakReference
import java.util.UUID

internal object ClickSources {
    const val PRIMARY_CTA = "primary_cta"
    const val STORE_PROMPT = "store_prompt"
    const val INSTALL_BANNER = "install_banner"
    const val FALLBACK_CTA = "fallback_cta"
    const val AUTO_REDIRECT = "auto_redirect"

    fun normalize(source: String): String = when (source) {
        PRIMARY_CTA, "cta" -> PRIMARY_CTA
        STORE_PROMPT -> STORE_PROMPT
        INSTALL_BANNER -> INSTALL_BANNER
        FALLBACK_CTA -> FALLBACK_CTA
        AUTO_REDIRECT, "auto_store_redirect" -> AUTO_REDIRECT
        else -> PRIMARY_CTA
    }

    fun storeExitTrigger(source: String): String = when (val normalized = normalize(source)) {
        PRIMARY_CTA -> "cta"
        else -> normalized
    }
}

internal data class ClickInteraction(
    val id: String,
    val source: String,
)

internal enum class ClickPersistencePart { TELEMETRY, BEACON }

internal enum class ClickHandoffResult { ROUTED, ACCOUNTED, FAILED, CANCELLED }

internal enum class PresentationRouteResult { EXECUTED, DEFERRED, REJECTED }

internal enum class ClickRouteStart { STARTED, REJECTED }

/** One current weak host plus at most one route waiting for that host to resume. */
internal class ResumedPresentationRoute<T : Any> {
    private data class PendingRoute<T>(
        val route: (T) -> Boolean,
        val completion: (Boolean) -> Unit,
    )

    private var currentHost: WeakReference<T>? = null
    private var resumed = false
    private var cancelled = false
    private var pending: PendingRoute<T>? = null

    @Synchronized
    fun attach(host: T) {
        if (cancelled) return
        currentHost = WeakReference(host)
        resumed = false
    }

    fun resume(host: T) {
        val route = synchronized(this) {
            if (cancelled || currentHost?.get() !== host) return
            resumed = true
            pending.also { pending = null }
        }
        route?.let { execute(it, host) }
    }

    @Synchronized
    fun pause(host: T) {
        if (currentHost?.get() === host) resumed = false
    }

    @Synchronized
    fun detach(host: T) {
        if (currentHost?.get() === host) {
            currentHost = null
            resumed = false
        }
    }

    fun request(
        route: (T) -> Boolean,
        completion: (Boolean) -> Unit,
    ): PresentationRouteResult {
        val request = PendingRoute(route, completion)
        val host = synchronized(this) {
            if (cancelled) return PresentationRouteResult.REJECTED
            val active = currentHost?.get()
            if (active != null && resumed) return@synchronized active
            if (pending != null) return PresentationRouteResult.REJECTED
            pending = request
            null
        }
        if (host == null) return PresentationRouteResult.DEFERRED
        execute(request, host)
        return PresentationRouteResult.EXECUTED
    }

    private fun execute(request: PendingRoute<T>, host: T) {
        val opened = runCatching { request.route(host) }.getOrDefault(false)
        runCatching { request.completion(opened) }
    }

    @Synchronized
    fun cancel() {
        cancelled = true
        currentHost = null
        resumed = false
        pending = null
    }
}

/** Declarative overlay owner: one host coordinator and at most one exact pending handoff. */
internal class DeclarativeClickRouteOwner<T : Any>(host: T?) {
    val routes = ResumedPresentationRoute<T>()
    private var pending: ClickPersistenceHandoff? = null

    init {
        if (host == null) routes.cancel() else routes.attach(host)
    }

    @Synchronized
    fun track(handoff: ClickPersistenceHandoff): Boolean {
        if (pending != null) {
            handoff.cancel()
            return false
        }
        pending = handoff
        return true
    }

    @Synchronized
    fun finish(handoff: ClickPersistenceHandoff) {
        if (pending === handoff) pending = null
    }

    fun cancel() {
        val handoff = synchronized(this) { pending.also { pending = null } }
        routes.cancel()
        handoff?.cancel()
    }

    @Synchronized
    fun hasPending(): Boolean = pending != null
}

internal class ClickHandoffSubscription internal constructor(
    private val cancelAction: () -> Unit,
) {
    private var active = true

    @Synchronized
    fun cancel() {
        if (!active) return
        active = false
        cancelAction()
    }
}

/** Holds click admission until both durable writes complete or timeout explicitly fails open. */
internal class ClickPersistenceHandoff(
    private val claim: ClickInteractionClaim,
    onReady: (timedOut: Boolean) -> Unit,
) {
    private val completed = HashSet<ClickPersistencePart>(2)
    private var state = State.PENDING
    private var readyCallback: ((Boolean) -> Unit)? = onReady
    private val resultListeners = LinkedHashMap<Long, (ClickHandoffResult) -> Unit>()
    private var nextListenerId = 0L
    private var terminalResult: ClickHandoffResult? = null

    fun complete(part: ClickPersistencePart) {
        val becameReady = synchronized(this) {
            if (state != State.PENDING) return
            completed += part
            if (completed.size != ClickPersistencePart.entries.size) false else {
                state = State.READY
                true
            }
        }
        if (becameReady) dispatchReady(timedOut = false)
    }

    fun timeout(): Boolean {
        val becameReady = synchronized(this) {
            if (state != State.PENDING) return false
            state = State.READY
            true
        }
        if (becameReady) dispatchReady(timedOut = true)
        return becameReady
    }

    @MainThread
    fun cancel(): Boolean {
        val shouldRelease = synchronized(this) {
            if (state == State.FINISHED || state == State.CANCELLED) return false
            state = State.CANCELLED
            readyCallback = null
            true
        }
        val released = shouldRelease && claim.release()
        if (shouldRelease) finish(ClickHandoffResult.CANCELLED)
        return released
    }

    @Synchronized
    fun isTerminal(): Boolean = state == State.FINISHED || state == State.CANCELLED

    /** Persistence makes the interaction billable, so commit before the best-effort route. */
    @MainThread
    fun handoff(route: (ClickInteraction) -> Boolean): Boolean {
        val shouldRoute = synchronized(this) {
            if (state != State.READY) return false
            state = State.FINISHED
            readyCallback = null
            true
        }
        if (!shouldRoute) return false
        if (!claim.commit()) {
            finish(ClickHandoffResult.FAILED)
            return false
        }
        val opened = runCatching { route(claim.interaction) }.getOrDefault(false)
        finish(if (opened) ClickHandoffResult.ROUTED else ClickHandoffResult.ACCOUNTED)
        return opened
    }

    /** Commit the durable interaction, then remain pending until async routing reports its outcome. */
    @MainThread
    fun handoffAsync(
        route: (ClickInteraction, (Boolean) -> Unit) -> ClickRouteStart,
    ): Boolean {
        val shouldRoute = synchronized(this) {
            if (state != State.READY) return false
            state = State.ROUTING
            readyCallback = null
            true
        }
        if (!shouldRoute) return false
        if (!claim.commit()) {
            synchronized(this) { state = State.FINISHED }
            finish(ClickHandoffResult.FAILED)
            return false
        }
        val start = runCatching {
            route(claim.interaction, ::completeAsyncRoute)
        }.getOrDefault(ClickRouteStart.REJECTED)
        if (start == ClickRouteStart.REJECTED) completeAsyncRoute(false)
        return start == ClickRouteStart.STARTED
    }

    private fun completeAsyncRoute(opened: Boolean) {
        val shouldFinish = synchronized(this) {
            if (state != State.ROUTING) return
            state = State.FINISHED
            true
        }
        if (shouldFinish) {
            finish(if (opened) ClickHandoffResult.ROUTED else ClickHandoffResult.ACCOUNTED)
        }
    }

    /**
     * Observe one terminal result. Registrations are bounded and cleared when the handoff ends.
     * Delivery is synchronous on the thread performing [handoff] or [cancel]; production performs
     * those terminal transitions on main so listeners may safely coordinate UI-owned routing.
     */
    fun addResultListener(listener: (ClickHandoffResult) -> Unit): ClickHandoffSubscription {
        var immediate: ClickHandoffResult? = null
        var listenerId: Long? = null
        synchronized(this) {
            immediate = terminalResult
            if (immediate == null && resultListeners.size < MAX_RESULT_LISTENERS) {
                listenerId = ++nextListenerId
                resultListeners[listenerId] = listener
            }
        }
        val subscription = ClickHandoffSubscription {
            val id = listenerId ?: return@ClickHandoffSubscription
            synchronized(this) { resultListeners.remove(id) }
        }
        immediate?.let { result -> runCatching { listener(result) } }
        return subscription
    }

    private fun dispatchReady(timedOut: Boolean) {
        val callback = synchronized(this) { readyCallback.also { readyCallback = null } }
        runCatching { callback?.invoke(timedOut) }.onFailure { cancel() }
    }

    private fun finish(result: ClickHandoffResult) {
        val listeners = synchronized(this) {
            if (terminalResult != null) return
            terminalResult = result
            resultListeners.values.toList().also { resultListeners.clear() }
        }
        listeners.forEach { listener -> runCatching { listener(result) } }
    }

    private enum class State { PENDING, READY, ROUTING, FINISHED, CANCELLED }

    private companion object {
        const val MAX_RESULT_LISTENERS = 4
    }
}

internal enum class AutoRedirectResult { DEFERRED, OPENED, FAILED, SUPPRESSED, STALE }

private typealias AsyncAutoRoute = (canOpen: () -> Boolean, completion: (Boolean) -> Unit) -> Unit

/**
 * Presentation-scoped auto-redirect policy. One route may wait on a user click handoff for the
 * active playable/end-screen scope; changing scope or disposing cancels the deferred closure.
 *
 * Production callers are main-thread confined. [requestAsync] keeps a route in progress until its
 * eventual launch result and gives it a generation-bound admission check, so stale work cannot
 * launch after a user click or scope replacement. A failed direct request remains eligible for a
 * later explicit trigger. A deferred user handoff failure/cancellation retries its retained route.
 */
@MainThread
internal class AutoRedirectCoordinator {
    private var activeScope: Any? = null
    private var disposed = false
    private var redirectOpened = false
    private var userRouteOpened = false
    private var routeInProgress = false
    private var waitingScope: Any? = null
    private var waitingRoute: AsyncAutoRoute? = null
    private var activeRouteScope: Any? = null
    private var activeRoute: AsyncAutoRoute? = null
    private var queuedRouteScope: Any? = null
    private var queuedRoute: AsyncAutoRoute? = null
    private var observedHandoff: ClickPersistenceHandoff? = null
    private var handoffSubscription: ClickHandoffSubscription? = null
    private var routeGeneration = 0L

    fun activate(scope: Any) {
        synchronized(this) {
            if (disposed || activeScope === scope) return
            clearWaitingLocked()
            clearQueuedRouteLocked()
            routeInProgress = false
            routeGeneration++
            clearActiveRouteLocked()
            activeScope = scope
        }
    }

    fun isActive(scope: Any): Boolean = synchronized(this) {
        !disposed && activeScope === scope && !redirectOpened && !userRouteOpened && observedHandoff == null
    }

    fun deactivate(scope: Any) {
        synchronized(this) {
            if (activeScope !== scope) return
            clearWaitingLocked()
            clearQueuedRouteLocked()
            routeInProgress = false
            routeGeneration++
            clearActiveRouteLocked()
            activeScope = null
        }
    }

    fun dispose() {
        synchronized(this) {
            if (disposed) return
            disposed = true
            clearWaitingLocked()
            clearQueuedRouteLocked()
            clearObservedHandoffLocked()
            routeInProgress = false
            routeGeneration++
            clearActiveRouteLocked()
            activeScope = null
        }
    }

    /** A committed direct user CTA opened externally; all later auto redirects are redundant. */
    fun recordUserRouteOpened() {
        synchronized(this) {
            if (disposed) return
            userRouteOpened = true
            routeInProgress = false
            routeGeneration++
            clearActiveRouteLocked()
            clearWaitingLocked()
            clearQueuedRouteLocked()
        }
    }

    /** Observe user-route success even when no auto-redirect trigger is active yet. */
    fun observeUserHandoff(handoff: ClickPersistenceHandoff) {
        synchronized(this) {
            if (disposed || observedHandoff === handoff) return
            clearObservedHandoffLocked()
            observedHandoff = handoff
            val inFlightRoute = activeRoute
            val inFlightScope = activeRouteScope
            if (routeInProgress && inFlightRoute != null && inFlightScope != null && activeScope === inFlightScope) {
                routeInProgress = false
                routeGeneration++
                clearActiveRouteLocked()
                clearQueuedRouteLocked()
                waitingScope = inFlightScope
                waitingRoute = inFlightRoute
            }
        }
        val newSubscription = handoff.addResultListener { result ->
            onUserHandoffResult(handoff, result)
        }
        synchronized(this) {
            if (!disposed && observedHandoff === handoff) {
                handoffSubscription = newSubscription
            } else {
                newSubscription.cancel()
            }
        }
    }

    fun requestAsync(
        scope: Any,
        pendingHandoff: ClickPersistenceHandoff?,
        route: AsyncAutoRoute,
    ): AutoRedirectResult {
        pendingHandoff?.let(::observeUserHandoff)
        val deferred = synchronized(this) {
            if (disposed || activeScope !== scope) return AutoRedirectResult.STALE
            if (redirectOpened || userRouteOpened) return AutoRedirectResult.SUPPRESSED
            if (routeInProgress) {
                if (queuedRoute == null) {
                    queuedRouteScope = scope
                    queuedRoute = route
                }
                return AutoRedirectResult.DEFERRED
            }
            if (waitingScope === scope) return AutoRedirectResult.DEFERRED
            if (pendingHandoff != null && observedHandoff === pendingHandoff) {
                waitingScope = scope
                waitingRoute = route
                true
            } else {
                routeInProgress = true
                false
            }
        }
        return if (deferred) AutoRedirectResult.DEFERRED else attemptRoute(scope, route)
    }

    internal fun request(
        scope: Any,
        pendingHandoff: ClickPersistenceHandoff?,
        route: () -> Boolean,
    ): AutoRedirectResult = requestAsync(scope, pendingHandoff) { _, completion ->
        completion(runCatching(route).getOrDefault(false))
    }

    private fun onUserHandoffResult(handoff: ClickPersistenceHandoff, result: ClickHandoffResult) {
        var retry: AsyncAutoRoute? = null
        var retryScope: Any? = null
        synchronized(this) {
            if (disposed || observedHandoff !== handoff) return
            observedHandoff = null
            handoffSubscription = null
            val scope = waitingScope
            val deferred = waitingRoute.takeIf { scope != null && activeScope === scope }
            clearWaitingLocked()
            when (result) {
                ClickHandoffResult.ROUTED, ClickHandoffResult.ACCOUNTED -> userRouteOpened = true
                ClickHandoffResult.FAILED, ClickHandoffResult.CANCELLED -> if (deferred != null) {
                    routeInProgress = true
                    retry = deferred
                    retryScope = scope
                }
            }
        }
        val route = retry
        val scope = retryScope
        if (route != null && scope != null) attemptRoute(scope, route)
    }

    private fun attemptRoute(scope: Any, route: AsyncAutoRoute): AutoRedirectResult {
        val generation = synchronized(this) {
            activeRouteScope = scope
            activeRoute = route
            ++routeGeneration
        }
        var synchronousResult: Boolean? = null
        var starting = true
        val completion: (Boolean) -> Unit = { opened ->
            var nextScope: Any? = null
            var nextRoute: AsyncAutoRoute? = null
            synchronized(this) {
                if (disposed || generation != routeGeneration || activeScope !== scope) return@synchronized
                routeInProgress = false
                clearActiveRouteLocked()
                if (opened) {
                    redirectOpened = true
                    clearQueuedRouteLocked()
                } else {
                    val queuedScope = queuedRouteScope
                    val queued = queuedRoute
                    clearQueuedRouteLocked()
                    if (queuedScope != null && queued != null && activeScope === queuedScope &&
                        !userRouteOpened && observedHandoff == null
                    ) {
                        routeInProgress = true
                        nextScope = queuedScope
                        nextRoute = queued
                    }
                }
                if (starting) synchronousResult = opened
            }
            val retryScope = nextScope
            val retryRoute = nextRoute
            if (retryScope != null && retryRoute != null) attemptRoute(retryScope, retryRoute)
        }
        val canOpen = {
            synchronized(this) {
                !disposed && generation == routeGeneration && activeScope === scope &&
                    !redirectOpened && !userRouteOpened && observedHandoff == null
            }
        }
        runCatching { route(canOpen, completion) }.onFailure { completion(false) }
        starting = false
        return when (synchronousResult) {
            true -> AutoRedirectResult.OPENED
            false -> AutoRedirectResult.FAILED
            null -> AutoRedirectResult.DEFERRED
        }
    }

    private fun clearWaitingLocked() {
        waitingScope = null
        waitingRoute = null
    }

    private fun clearActiveRouteLocked() {
        activeRouteScope = null
        activeRoute = null
    }

    private fun clearQueuedRouteLocked() {
        queuedRouteScope = null
        queuedRoute = null
    }

    private fun clearObservedHandoffLocked() {
        handoffSubscription?.cancel()
        handoffSubscription = null
        observedHandoff = null
    }
}

internal enum class ClickRouteOutcome { BLOCKED, OPEN_FAILED, OPENED }

/** Route a synchronous CTA without consuming admission when the external open fails. */
internal fun routeClaimedClick(
    claim: ClickInteractionClaim?,
    open: () -> Boolean,
    onOpened: (ClickInteraction) -> Unit,
): ClickRouteOutcome {
    claim ?: return ClickRouteOutcome.BLOCKED
    if (!runCatching(open).getOrDefault(false)) {
        claim.release()
        return ClickRouteOutcome.OPEN_FAILED
    }
    if (!claim.commit()) return ClickRouteOutcome.BLOCKED
    onOpened(claim.interaction)
    return ClickRouteOutcome.OPENED
}

internal const val CLICK_PERSISTENCE_WAIT_MS = 750L

internal data class PrimaryCtaRoute(
    val tappedUrl: String?,
    val externalTarget: String?,
    val externalTargetIsTracker: Boolean = false,
)

/**
 * Presentation-owned primary CTA state. The presentation outlives Activity recreation, while the
 * bound Activity and WebView navigation callback must not. A failed external route is retained until
 * the handoff is terminal and a WebView owned by the current Activity has bound.
 */
internal class RetainedPrimaryCtaNavigationState<T : Any> {
    private var currentActivity: WeakReference<T>? = null
    private var navigationOwner: Any? = null
    private var navigationActivity: WeakReference<T>? = null
    private var navigateInWebView: ((String) -> Boolean)? = null
    private var pendingHandoff: ClickPersistenceHandoff? = null
    private var pendingFallbackUrl: String? = null
    private var activeDelivery: NavigationDelivery? = null
    private var deliveryRevision = 0L
    private var creativeNavigationLocked = false
    private var cleared = false

    fun attachActivity(activity: T) {
        synchronized(this) {
            if (cleared) return
            currentActivity = WeakReference(activity)
            if (navigationActivity?.get() !== activity) clearBindingLocked()
        }
        dispatchReadyFallback()
    }

    @Synchronized
    fun detachActivity(activity: T) {
        if (currentActivity?.get() === activity) currentActivity = null
        if (navigationActivity?.get() === activity) clearBindingLocked()
    }

    fun bindNavigation(owner: Any, activity: T, navigate: (String) -> Boolean): Boolean {
        synchronized(this) {
            if (cleared || currentActivity?.get() !== activity) return false
            if (navigationOwner !== owner) clearBindingLocked()
            navigationOwner = owner
            navigationActivity = WeakReference(activity)
            navigateInWebView = navigate
        }
        dispatchReadyFallback()
        return true
    }

    @Synchronized
    fun unbindNavigation(owner: Any) {
        if (navigationOwner === owner) clearBindingLocked()
    }

    @Synchronized
    fun navigationOverride(
        targetUrl: String? = null,
        isMainFrame: Boolean = true,
        hasGesture: Boolean = false,
        owner: Any? = navigationOwner,
    ): Boolean? {
        if (cleared) return true
        if (pendingHandoff != null) return true
        activeDelivery?.let { delivery ->
            if (delivery.owner === owner && isMainFrame && !hasGesture &&
                targetUrl == delivery.url && !delivery.permitConsumed
            ) {
                delivery.permitConsumed = true
                if (pendingFallbackUrl == null) activeDelivery = null
                return false
            }
            return true
        }
        return if (pendingFallbackUrl != null || creativeNavigationLocked) true else null
    }

    /** Keep a creative that already opened externally from replacing itself with a delayed fallback. */
    @Synchronized
    fun lockAfterExternalOpen() {
        if (cleared) return
        creativeNavigationLocked = true
        pendingFallbackUrl = null
        activeDelivery = null
        deliveryRevision++
    }

    @Synchronized
    fun onNavigationStarted(url: String?, owner: Any? = navigationOwner) {
        val delivery = activeDelivery ?: return
        if (delivery.owner !== owner || url != delivery.url) return
        delivery.permitConsumed = true
        if (pendingFallbackUrl == null) activeDelivery = null
    }

    @Synchronized
    fun onHandoffCreated(handoff: ClickPersistenceHandoff) {
        if (!cleared && pendingHandoff == null) pendingHandoff = handoff
    }

    fun onHandoffFinished(handoff: ClickPersistenceHandoff) {
        synchronized(this) {
            if (pendingHandoff === handoff) {
                pendingHandoff = null
            }
        }
        dispatchReadyFallback()
    }

    fun retainFallback(url: String, activity: T): Boolean {
        synchronized(this) {
            if (cleared || creativeNavigationLocked || url.isBlank() || currentActivity?.get() !== activity) {
                return false
            }
            if (pendingFallbackUrl == null) pendingFallbackUrl = url
        }
        dispatchReadyFallback()
        return true
    }

    @Synchronized
    fun hasRetainedFallback(): Boolean = pendingFallbackUrl != null

    @Synchronized
    fun clear() {
        if (cleared) return
        cleared = true
        currentActivity = null
        pendingHandoff = null
        pendingFallbackUrl = null
        activeDelivery = null
        deliveryRevision++
        clearBindingLocked()
    }

    private fun dispatchReadyFallback() {
        val delivery = synchronized(this) {
            if (cleared || pendingHandoff != null || activeDelivery != null) return
            val current = currentActivity?.get() ?: return
            if (navigationActivity?.get() !== current) return
            val navigate = navigateInWebView ?: return
            val url = pendingFallbackUrl ?: return
            val owner = navigationOwner ?: return
            NavigationDelivery(++deliveryRevision, owner, url).also { activeDelivery = it } to navigate
        }
        val delivered = runCatching { delivery.second(delivery.first.url) }.getOrDefault(false)
        synchronized(this) {
            if (activeDelivery !== delivery.first) return@synchronized
            if (delivered && pendingFallbackUrl == delivery.first.url) {
                pendingFallbackUrl = null
            }
            if (!delivered || delivery.first.permitConsumed) activeDelivery = null
        }
    }

    private fun clearBindingLocked() {
        activeDelivery?.takeIf { it.owner === navigationOwner }?.let { delivery ->
            if (pendingFallbackUrl == null) pendingFallbackUrl = delivery.url
            activeDelivery = null
            deliveryRevision++
        }
        navigationOwner = null
        navigationActivity = null
        navigateInWebView = null
    }

    private data class NavigationDelivery(
        val revision: Long,
        val owner: Any,
        val url: String,
        var permitConsumed: Boolean = false,
    )
}

/** A provisional click admission that only starts the duplicate window after [commit]. */
internal class ClickInteractionClaim internal constructor(
    val interaction: ClickInteraction,
    private val gate: ClickInteractionGate,
    private val token: Long,
) {
    fun commit(): Boolean = gate.commit(token)

    fun release(): Boolean = gate.release(token)
}

/**
 * Presentation-scoped admission for click dispatch. WebView/navigation callbacks can fan one tap
 * out more than once; a claim blocks concurrent duplicates. Synchronous actions commit only after
 * opening succeeds. Persistence handoffs commit before routing because their durable event cannot be
 * undone; a failed destination then remains accounted under the same interaction id.
 */
internal class ClickInteractionGate(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val duplicateWindowMs: Long = DUPLICATE_CLICK_WINDOW_MS,
) {
    private var lastCommittedAtMs = Long.MIN_VALUE
    private var pendingToken: Long? = null
    private var nextToken = 0L

    @Synchronized
    fun claim(source: String): ClickInteractionClaim? {
        val now = clockMs()
        if (pendingToken != null) return null
        if (lastCommittedAtMs != Long.MIN_VALUE && now - lastCommittedAtMs < duplicateWindowMs) return null
        val token = ++nextToken
        pendingToken = token
        // UUIDs stay well within the backend's 64-character click-event-id bound.
        return ClickInteractionClaim(
            interaction = ClickInteraction(id = idFactory().take(64), source = ClickSources.normalize(source)),
            gate = this,
            token = token,
        )
    }

    /** Immediate admission for taps whose action cannot fail after admission. */
    fun admit(source: String): ClickInteraction? {
        val claim = claim(source) ?: return null
        return claim.interaction.takeIf { claim.commit() }
    }

    @Synchronized
    internal fun commit(token: Long): Boolean {
        if (pendingToken != token) return false
        pendingToken = null
        lastCommittedAtMs = clockMs()
        return true
    }

    @Synchronized
    internal fun release(token: Long): Boolean {
        if (pendingToken != token) return false
        pendingToken = null
        return true
    }

    @Synchronized
    fun hasPendingClaim(): Boolean = pendingToken != null
}

private const val DUPLICATE_CLICK_WINDOW_MS = 500L
