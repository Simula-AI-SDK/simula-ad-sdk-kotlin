package ad.simula.ad.sdk.provider

import ad.simula.ad.sdk.core.ApiKeyOwnership

internal enum class ProviderChildrenPlan {
    Withhold,
    Inert,
    Active,
}

internal data class ProviderCompositionPlan(
    val children: ProviderChildrenPlan,
    val seedPrivacyAfterCommit: Boolean,
)

internal fun providerCompositionPlan(
    apiKeyOwnership: ApiKeyOwnership?,
    privacyIsCommitted: Boolean,
): ProviderCompositionPlan = when (apiKeyOwnership) {
    null -> ProviderCompositionPlan(ProviderChildrenPlan.Withhold, seedPrivacyAfterCommit = false)
    ApiKeyOwnership.Incompatible ->
        ProviderCompositionPlan(ProviderChildrenPlan.Inert, seedPrivacyAfterCommit = false)
    else -> ProviderCompositionPlan(
        ProviderChildrenPlan.Active,
        seedPrivacyAfterCommit = !privacyIsCommitted,
    )
}
