package ad.simula.ad.sdk.ads

internal sealed interface RewardedCreativeSource {
    data class Html(val value: String) : RewardedCreativeSource
}

/** Playable rewarded creatives require server-rendered HTML. */
internal fun rewardedCreativeSource(renderedHtml: String?): RewardedCreativeSource? {
    renderedHtml?.takeIf { it.isNotBlank() }?.let {
        return RewardedCreativeSource.Html(it)
    }
    return null
}

internal fun isQualifiedRewardedCreativeCommit(
    source: RewardedCreativeSource?,
    loadArmed: Boolean,
    mainFrameFailed: Boolean,
    url: String?,
): Boolean {
    if (!loadArmed || mainFrameFailed || url.isNullOrBlank()) return false
    return when (source) {
        is RewardedCreativeSource.Html -> false
        null -> false
    }
}

internal class RewardedHtmlReadinessGate {
    private var armed = false
    private var generation = 0L
    private var nextRequest = 0L
    private var pendingRequest: Long? = null
    private var pageStarts = 0

    fun arm() {
        generation++
        armed = true
        pendingRequest = null
        pageStarts = 0
    }

    fun onPageStarted() {
        if (!armed) return
        pageStarts++
        generation++
        pendingRequest = null
        if (pageStarts > 1) armed = false
    }

    fun onPageReady(): Long? {
        if (!armed) return null
        val request = (++nextRequest shl 32) xor generation
        pendingRequest = request
        return request
    }

    fun acceptVisualState(request: Long): Boolean {
        if (!armed || pendingRequest != request) return false
        armed = false
        pendingRequest = null
        pageStarts = 0
        return true
    }

    fun terminate() {
        armed = false
        pendingRequest = null
        pageStarts = 0
    }
}

internal class ForegroundTimeoutBudget(private val totalMs: Long) {
    private var remainingMs = totalMs.coerceAtLeast(0L)
    private var resumedAtMs: Long? = null

    fun resume(nowMs: Long): Long {
        if (resumedAtMs == null) resumedAtMs = nowMs
        return remainingMs
    }

    fun pause(nowMs: Long) {
        val started = resumedAtMs ?: return
        remainingMs = (remainingMs - (nowMs - started).coerceAtLeast(0L)).coerceAtLeast(0L)
        resumedAtMs = null
    }

    fun complete() {
        remainingMs = 0L
        resumedAtMs = null
    }
}
