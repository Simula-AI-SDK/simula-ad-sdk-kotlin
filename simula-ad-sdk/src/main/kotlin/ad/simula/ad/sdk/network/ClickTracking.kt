package ad.simula.ad.sdk.network

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

/** Holds click admission until both durable writes complete or timeout explicitly fails open. */
internal class ClickPersistenceHandoff(
    private val claim: ClickInteractionClaim,
    onReady: (timedOut: Boolean) -> Unit,
) {
    private val completed = HashSet<ClickPersistencePart>(2)
    private var state = State.PENDING
    private var readyCallback: ((Boolean) -> Unit)? = onReady

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

    fun cancel(): Boolean {
        val shouldRelease = synchronized(this) {
            if (state == State.FINISHED || state == State.CANCELLED) return false
            state = State.CANCELLED
            readyCallback = null
            true
        }
        return shouldRelease && claim.release()
    }

    @Synchronized
    fun isTerminal(): Boolean = state == State.FINISHED || state == State.CANCELLED

    /** Commit immediately before the external route; a failed route rolls the gate back for retry. */
    fun handoff(route: (ClickInteraction) -> Boolean): Boolean {
        val shouldRoute = synchronized(this) {
            if (state != State.READY) return false
            state = State.FINISHED
            readyCallback = null
            true
        }
        if (!shouldRoute || !claim.commit()) return false
        val opened = runCatching { route(claim.interaction) }.getOrDefault(false)
        if (!opened) claim.rollbackCommit()
        return opened
    }

    private fun dispatchReady(timedOut: Boolean) {
        val callback = synchronized(this) { readyCallback.also { readyCallback = null } }
        runCatching { callback?.invoke(timedOut) }.onFailure { cancel() }
    }

    private enum class State { PENDING, READY, FINISHED, CANCELLED }
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
