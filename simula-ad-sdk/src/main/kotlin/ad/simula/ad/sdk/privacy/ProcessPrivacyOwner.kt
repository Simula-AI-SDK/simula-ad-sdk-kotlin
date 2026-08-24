package ad.simula.ad.sdk.privacy

internal enum class PrivacySeedResult {
    FirstApplied,
    OwnerUpdated,
    ExplicitReplaced,
    IgnoredDefault,
    Failed,
}

internal enum class PrivacyReleaseResult {
    NotActive,
    Released,
    FallbackApplied,
    FallbackFailed,
}

internal class PrivacyOwnerToken internal constructor()

/**
 * Serializes process privacy ownership. The first compatible SDK entry seeds privacy; that same
 * entry may update it, other defaults are ignored, and an explicit object transfers ownership.
 */
internal class FirstPrivacyConfigOwner(
    private val applyConfig: (SimulaPrivacyConfig) -> Unit,
) {
    private data class ActiveEntry(
        val config: SimulaPrivacyConfig,
        val explicit: Boolean,
    )

    private val lock = Any()
    private val active = LinkedHashMap<PrivacyOwnerToken, ActiveEntry>()
    private var owner: PrivacyOwnerToken? = null

    fun seed(
        token: PrivacyOwnerToken,
        config: SimulaPrivacyConfig,
        explicit: Boolean,
    ): PrivacySeedResult = synchronized(lock) {
        // LinkedHashMap replacement preserves the token's original mount/lifetime order.
        active[token] = ActiveEntry(config, explicit)
        val currentOwner = owner
        if (currentOwner != null && currentOwner !== token && !explicit) {
            return@synchronized PrivacySeedResult.IgnoredDefault
        }
        val applied = runCatching { applyConfig(config) }.isSuccess
        if (!applied) return@synchronized PrivacySeedResult.Failed
        val result = when {
            currentOwner == null -> PrivacySeedResult.FirstApplied
            currentOwner === token -> PrivacySeedResult.OwnerUpdated
            else -> PrivacySeedResult.ExplicitReplaced
        }
        owner = token
        result
    }

    fun release(token: PrivacyOwnerToken): PrivacyReleaseResult = synchronized(lock) {
        if (active.remove(token) == null) return@synchronized PrivacyReleaseResult.NotActive
        if (owner !== token) return@synchronized PrivacyReleaseResult.Released

        owner = null
        val fallback = active.entries.lastOrNull() ?: return@synchronized PrivacyReleaseResult.Released
        val applied = runCatching { applyConfig(fallback.value.config) }.isSuccess
        if (!applied) return@synchronized PrivacyReleaseResult.FallbackFailed
        owner = fallback.key
        PrivacyReleaseResult.FallbackApplied
    }
}

internal object ProcessPrivacyOwner {
    private val owner = FirstPrivacyConfigOwner(SimulaPrivacy::apply)

    fun createToken(): PrivacyOwnerToken = PrivacyOwnerToken()

    fun seed(
        token: PrivacyOwnerToken,
        config: SimulaPrivacyConfig,
        explicit: Boolean,
    ): PrivacySeedResult = owner.seed(token, config, explicit)

    fun release(token: PrivacyOwnerToken): PrivacyReleaseResult = owner.release(token)
}
