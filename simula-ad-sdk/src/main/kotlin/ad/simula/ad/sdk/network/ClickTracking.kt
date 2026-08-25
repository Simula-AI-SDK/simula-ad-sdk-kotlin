package ad.simula.ad.sdk.network

import androidx.annotation.MainThread
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
}

internal data class ClickInteraction(
    val id: String,
    val source: String,
)

internal enum class ClickPersistencePart { TELEMETRY, BEACON }

internal enum class ClickHandoffResult { ROUTED, FAILED, CANCELLED }

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

    /** Commit immediately before the external route; a failed route rolls the gate back for retry. */
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
        if (!opened) claim.rollbackCommit()
        finish(if (opened) ClickHandoffResult.ROUTED else ClickHandoffResult.FAILED)
        return opened
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

    private enum class State { PENDING, READY, FINISHED, CANCELLED }

    private companion object {
        const val MAX_RESULT_LISTENERS = 4
    }
}

internal enum class AutoRedirectResult { DEFERRED, OPENED, FAILED, SUPPRESSED, STALE }

/**
 * Presentation-scoped auto-redirect policy. One route may wait on a user click handoff for the
 * active playable/end-screen scope; changing scope or disposing cancels the deferred closure.
 *
 * Production callers are main-thread confined. [request] executes its route synchronously on the
 * calling thread; the synchronization only protects terminal-listener delivery and keeps the pure
 * JVM state-machine tests deterministic. A failed direct request is one best-effort attempt: it
 * remains eligible for a later explicit trigger but does not schedule an automatic retry. Only a
 * deferred user handoff failure/cancellation invokes its already-requested route automatically.
 */
@MainThread
internal class AutoRedirectCoordinator {
    private var activeScope: Any? = null
    private var disposed = false
    private var redirectOpened = false
    private var userRouteOpened = false
    private var routeInProgress = false
    private var waitingScope: Any? = null
    private var waitingRoute: (() -> Boolean)? = null
    private var observedHandoff: ClickPersistenceHandoff? = null
    private var handoffSubscription: ClickHandoffSubscription? = null

    fun activate(scope: Any) {
        synchronized(this) {
            if (disposed || activeScope === scope) return
            clearWaitingLocked()
            activeScope = scope
        }
    }

    fun deactivate(scope: Any) {
        synchronized(this) {
            if (activeScope !== scope) return
            clearWaitingLocked()
            activeScope = null
        }
    }

    fun dispose() {
        synchronized(this) {
            if (disposed) return
            disposed = true
            clearWaitingLocked()
            clearObservedHandoffLocked()
            activeScope = null
        }
    }

    /** A committed direct user CTA opened externally; all later auto redirects are redundant. */
    fun recordUserRouteOpened() {
        synchronized(this) {
            if (disposed) return
            userRouteOpened = true
            clearWaitingLocked()
        }
    }

    /** Observe user-route success even when no auto-redirect trigger is active yet. */
    fun observeUserHandoff(handoff: ClickPersistenceHandoff) {
        synchronized(this) {
            if (disposed || observedHandoff === handoff) return
            clearObservedHandoffLocked()
            observedHandoff = handoff
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

    fun request(
        scope: Any,
        pendingHandoff: ClickPersistenceHandoff?,
        route: () -> Boolean,
    ): AutoRedirectResult {
        pendingHandoff?.let(::observeUserHandoff)
        val deferred = synchronized(this) {
            if (disposed || activeScope !== scope) return AutoRedirectResult.STALE
            if (redirectOpened || userRouteOpened) return AutoRedirectResult.SUPPRESSED
            if (routeInProgress || waitingScope === scope) return AutoRedirectResult.DEFERRED
            if (pendingHandoff != null && observedHandoff === pendingHandoff) {
                waitingScope = scope
                waitingRoute = route
                true
            } else {
                routeInProgress = true
                false
            }
        }
        return if (deferred) AutoRedirectResult.DEFERRED else attemptRoute(route)
    }

    private fun onUserHandoffResult(handoff: ClickPersistenceHandoff, result: ClickHandoffResult) {
        var retry: (() -> Boolean)? = null
        synchronized(this) {
            if (disposed || observedHandoff !== handoff) return
            observedHandoff = null
            handoffSubscription = null
            val scope = waitingScope
            val deferred = waitingRoute.takeIf { scope != null && activeScope === scope }
            clearWaitingLocked()
            when (result) {
                ClickHandoffResult.ROUTED -> userRouteOpened = true
                ClickHandoffResult.FAILED, ClickHandoffResult.CANCELLED -> if (deferred != null) {
                    routeInProgress = true
                    retry = deferred
                }
            }
        }
        retry?.let(::attemptRoute)
    }

    private fun attemptRoute(route: () -> Boolean): AutoRedirectResult {
        val opened = runCatching(route).getOrDefault(false)
        synchronized(this) {
            routeInProgress = false
            if (opened) redirectOpened = true
        }
        return if (opened) AutoRedirectResult.OPENED else AutoRedirectResult.FAILED
    }

    private fun clearWaitingLocked() {
        waitingScope = null
        waitingRoute = null
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

/** A provisional click admission that only starts the duplicate window after [commit]. */
internal class ClickInteractionClaim internal constructor(
    val interaction: ClickInteraction,
    private val gate: ClickInteractionGate,
    private val token: Long,
) {
    fun commit(): Boolean = gate.commit(token)

    fun release(): Boolean = gate.release(token)

    internal fun rollbackCommit(): Boolean = gate.rollbackCommit(token)
}

/**
 * Presentation-scoped admission for click dispatch. WebView/navigation callbacks can fan one tap
 * out more than once; a claim blocks concurrent duplicates, but only a committed successful action
 * starts the short duplicate window. Failed actions release their claim so fallback navigation and
 * later callbacks remain eligible.
 */
internal class ClickInteractionGate(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val duplicateWindowMs: Long = DUPLICATE_CLICK_WINDOW_MS,
) {
    private var lastCommittedAtMs = Long.MIN_VALUE
    private var lastCommittedToken: Long? = null
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
        lastCommittedToken = token
        return true
    }

    @Synchronized
    internal fun release(token: Long): Boolean {
        if (pendingToken != token) return false
        pendingToken = null
        return true
    }

    @Synchronized
    internal fun rollbackCommit(token: Long): Boolean {
        if (lastCommittedToken != token) return false
        lastCommittedToken = null
        lastCommittedAtMs = Long.MIN_VALUE
        return true
    }

    @Synchronized
    fun hasPendingClaim(): Boolean = pendingToken != null
}

private const val DUPLICATE_CLICK_WINDOW_MS = 500L
