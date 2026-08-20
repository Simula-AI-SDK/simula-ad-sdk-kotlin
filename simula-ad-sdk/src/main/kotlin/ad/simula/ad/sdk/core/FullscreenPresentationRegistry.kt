package ad.simula.ad.sdk.core

import java.util.concurrent.ConcurrentHashMap

/** Tokenized ownership for SDK fullscreen presentations across ad objects and formats. */
internal class PresentationTokenRegistry {
    private val activeTokens = ConcurrentHashMap.newKeySet<String>()

    fun claim(token: String) {
        if (token.isNotBlank()) activeTokens.add(token)
    }

    fun release(token: String) {
        activeTokens.remove(token)
    }

    fun hasActivePresentation(): Boolean = activeTokens.isNotEmpty()
}

/** Process-wide prewarm guard; it does not make fullscreen presentation exclusive. */
internal object FullscreenPresentationRegistry {
    private val tokens = PresentationTokenRegistry()

    fun claim(token: String) = tokens.claim(token)

    fun release(token: String) = tokens.release(token)

    fun hasActivePresentation(): Boolean = tokens.hasActivePresentation()
}
