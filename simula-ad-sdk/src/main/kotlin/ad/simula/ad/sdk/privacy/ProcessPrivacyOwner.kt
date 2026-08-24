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
    private var appliedConfig: SimulaPrivacyConfig? = null

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
        appliedConfig = config
        result
    }

    fun release(token: PrivacyOwnerToken): PrivacyReleaseResult = synchronized(lock) {
        val released = active.remove(token) ?: return@synchronized PrivacyReleaseResult.NotActive
        if (owner !== token) return@synchronized PrivacyReleaseResult.Released

        val fallback = active.entries.lastOrNull()
        owner = fallback?.key
        val current = appliedConfig ?: released.config
        if (fallback != null && doesNotBroadenPrivacy(fallback.value.config, current)) {
            if (runCatching { applyConfig(fallback.value.config) }.isSuccess) {
                appliedConfig = fallback.value.config
            }
        }
        PrivacyReleaseResult.Released
    }
}

/** A disposed owner may restore a remaining entry only when every privacy gate stays as strict. */
internal fun doesNotBroadenPrivacy(
    candidate: SimulaPrivacyConfig,
    current: SimulaPrivacyConfig,
): Boolean {
    val candidateAllowsPpid = candidate.hasPrivacyConsent && !candidate.coppaApplies
    val currentAllowsPpid = current.hasPrivacyConsent && !current.coppaApplies
    val candidateAllowsGaid = candidate.enableAdvertisingId && !candidate.coppaApplies
    val currentAllowsGaid = current.enableAdvertisingId && !current.coppaApplies
    val candidateAllowsStorage = candidate.gdprApplies != true || candidate.tcfPurpose1Consent == true
    val currentAllowsStorage = current.gdprApplies != true || current.tcfPurpose1Consent == true
    return (!candidateAllowsPpid || currentAllowsPpid) &&
        (!candidateAllowsGaid || currentAllowsGaid) &&
        (!candidateAllowsStorage || currentAllowsStorage) &&
        (!current.coppaApplies || candidate.coppaApplies) &&
        (current.gdprApplies != true || candidate.gdprApplies == true)
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
