package ad.simula.ad.sdk.provider

import ad.simula.ad.sdk.core.ApiKeyOwnership
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderCompositionPlanTest {
    @Test
    fun `initial uncommitted entry withholds children`() {
        assertEquals(
            ProviderCompositionPlan(
                children = ProviderChildrenPlan.Withhold,
                seedPrivacyAfterCommit = false,
            ),
            providerCompositionPlan(apiKeyOwnership = null, privacyIsCommitted = false),
        )
    }

    @Test
    fun `incompatible entry composes inert children`() {
        assertEquals(
            ProviderCompositionPlan(
                children = ProviderChildrenPlan.Inert,
                seedPrivacyAfterCommit = false,
            ),
            providerCompositionPlan(
                apiKeyOwnership = ApiKeyOwnership.Incompatible,
                privacyIsCommitted = false,
            ),
        )
    }

    @Test
    fun `committed privacy update keeps active children while scheduling seed`() {
        assertEquals(
            ProviderCompositionPlan(
                children = ProviderChildrenPlan.Active,
                seedPrivacyAfterCommit = true,
            ),
            providerCompositionPlan(
                apiKeyOwnership = ApiKeyOwnership.Compatible,
                privacyIsCommitted = false,
            ),
        )
    }

    @Test
    fun `committed privacy does not schedule another seed`() {
        assertEquals(
            ProviderCompositionPlan(
                children = ProviderChildrenPlan.Active,
                seedPrivacyAfterCommit = false,
            ),
            providerCompositionPlan(
                apiKeyOwnership = ApiKeyOwnership.Owner,
                privacyIsCommitted = true,
            ),
        )
    }
}
