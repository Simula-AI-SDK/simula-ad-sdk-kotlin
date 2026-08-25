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

/** Releases an external handoff once both durable writes complete, or once its caller times out. */
internal class ClickPersistenceBarrier(
    private val onReady: (timedOut: Boolean) -> Unit,
) {
    private val completed = HashSet<ClickPersistencePart>(2)
    private var released = false

    @Synchronized
    fun complete(part: ClickPersistencePart) {
        if (released) return
        completed += part
        if (completed.size == ClickPersistencePart.entries.size) release(timedOut = false)
    }

    @Synchronized
    fun timeout() {
        if (!released) release(timedOut = true)
    }

    private fun release(timedOut: Boolean) {
        released = true
        onReady(timedOut)
    }
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
}

private const val DUPLICATE_CLICK_WINDOW_MS = 500L
