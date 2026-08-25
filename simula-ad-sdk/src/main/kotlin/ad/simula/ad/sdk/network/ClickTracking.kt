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

/**
 * Presentation-scoped admission for click dispatch. WebView/navigation callbacks can fan one tap
 * out more than once; only the first callback inside the short duplicate window is accepted.
 */
internal class ClickInteractionGate(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val duplicateWindowMs: Long = DUPLICATE_CLICK_WINDOW_MS,
) {
    private var lastAcceptedAtMs = Long.MIN_VALUE

    @Synchronized
    fun admit(source: String): ClickInteraction? {
        val now = clockMs()
        if (lastAcceptedAtMs != Long.MIN_VALUE && now - lastAcceptedAtMs < duplicateWindowMs) return null
        lastAcceptedAtMs = now
        // UUIDs stay well within the backend's 64-character click-event-id bound.
        return ClickInteraction(id = idFactory().take(64), source = ClickSources.normalize(source))
    }
}

private const val DUPLICATE_CLICK_WINDOW_MS = 500L
