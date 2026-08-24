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

        // Releasing an entry must not restore a stale or previously ignored config. The process
        // snapshot remains unchanged until the next explicit takeover or logical-owner update.
        owner = active.keys.lastOrNull()
        PrivacyReleaseResult.Released
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
