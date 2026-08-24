package ad.simula.ad.sdk.privacy

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessPrivacyOwnerTest {
    @Test
    fun `pending or incompatible cleanup is harmless before a committed seed`() {
        val applied = mutableListOf<SimulaPrivacyConfig>()
        val owner = FirstPrivacyConfigOwner(applied::add)
        val token = PrivacyOwnerToken()
        val config = SimulaPrivacyConfig(hasPrivacyConsent = false)

        assertEquals(PrivacyReleaseResult.NotActive, owner.release(token))
        assertEquals(PrivacySeedResult.FirstApplied, owner.seed(token, config, explicit = false))
        assertEquals(PrivacyReleaseResult.Released, owner.release(token))
        assertEquals(PrivacyReleaseResult.NotActive, owner.release(token))

        assertEquals(listOf(config), applied)
    }

    @Test
    fun `owner unmount transfers ownership without applying latest active entry`() {
        val applied = mutableListOf<SimulaPrivacyConfig>()
        val owner = FirstPrivacyConfigOwner(applied::add)
        val outer = PrivacyOwnerToken()
        val inner = PrivacyOwnerToken()
        val outerInitial = SimulaPrivacyConfig(hasPrivacyConsent = true)
        val innerConfig = SimulaPrivacyConfig(hasPrivacyConsent = false)
        val outerUpdated = SimulaPrivacyConfig(hasPrivacyConsent = true, coppaApplies = true)

        owner.seed(outer, outerInitial, explicit = false)
        owner.seed(inner, innerConfig, explicit = false)
        owner.seed(outer, outerUpdated, explicit = false)

        assertEquals(PrivacyReleaseResult.Released, owner.release(outer))
        assertEquals(listOf(outerInitial, outerUpdated), applied)

        val innerUpdated = innerConfig.copy(coppaApplies = true)
        assertEquals(PrivacySeedResult.OwnerUpdated, owner.seed(inner, innerUpdated, explicit = false))
        assertEquals(listOf(outerInitial, outerUpdated, innerUpdated), applied)
    }

    @Test
    fun `last owner unmount lets next new default claim`() {
        val applied = mutableListOf<SimulaPrivacyConfig>()
        val owner = FirstPrivacyConfigOwner(applied::add)
        val first = PrivacyOwnerToken()
        val next = PrivacyOwnerToken()
        val nextConfig = SimulaPrivacyConfig(hasPrivacyConsent = false)

        owner.seed(first, SimulaPrivacyConfig(), explicit = false)
        assertEquals(PrivacyReleaseResult.Released, owner.release(first))
        assertEquals(PrivacySeedResult.FirstApplied, owner.seed(next, nextConfig, explicit = false))

        assertEquals(nextConfig, applied.last())
    }

    @Test
    fun `recreated same-key provider can revoke consent after prior owner disposal`() {
        val applied = mutableListOf<SimulaPrivacyConfig>()
        val owner = FirstPrivacyConfigOwner(applied::add)
        val disposedProvider = PrivacyOwnerToken()
        val recreatedProvider = PrivacyOwnerToken()
        val revoked = SimulaPrivacyConfig(hasPrivacyConsent = false)

        owner.seed(disposedProvider, SimulaPrivacyConfig(), explicit = false)
        owner.release(disposedProvider)
        owner.seed(recreatedProvider, revoked, explicit = false)

        assertEquals(revoked, applied.last())
    }

    @Test
    fun `explicit owner unmount does not restore prior active config`() {
        val applied = mutableListOf<SimulaPrivacyConfig>()
        val owner = FirstPrivacyConfigOwner(applied::add)
        val prior = PrivacyOwnerToken()
        val explicit = PrivacyOwnerToken()
        val priorConfig = SimulaPrivacyConfig(hasPrivacyConsent = false)
        val explicitConfig = SimulaPrivacyConfig(coppaApplies = true)

        owner.seed(prior, priorConfig, explicit = false)
        owner.seed(explicit, explicitConfig, explicit = true)
        assertEquals(PrivacyReleaseResult.Released, owner.release(explicit))

        assertEquals(listOf(priorConfig, explicitConfig), applied)

        val priorUpdated = priorConfig.copy(coppaApplies = true)
        assertEquals(PrivacySeedResult.OwnerUpdated, owner.seed(prior, priorUpdated, explicit = false))
        assertEquals(listOf(priorConfig, explicitConfig, priorUpdated), applied)
    }

    @Test
    fun `release never restores consent from ignored defaults or a previous explicit entry`() {
        val applied = mutableListOf<SimulaPrivacyConfig>()
        val owner = FirstPrivacyConfigOwner(applied::add)
        val previousExplicit = PrivacyOwnerToken()
        val deniedOwner = PrivacyOwnerToken()
        val imperativeDefault = PrivacyOwnerToken()
        val providerDefault = PrivacyOwnerToken()
        val allowed = SimulaPrivacyConfig(hasPrivacyConsent = true)
        val denied = SimulaPrivacyConfig(hasPrivacyConsent = false)

        owner.seed(previousExplicit, allowed, explicit = true)
        owner.seed(deniedOwner, denied, explicit = true)
        assertEquals(
            PrivacySeedResult.IgnoredDefault,
            owner.seed(imperativeDefault, SimulaPrivacyConfig(), explicit = false),
        )
        assertEquals(
            PrivacySeedResult.IgnoredDefault,
            owner.seed(providerDefault, SimulaPrivacyConfig(), explicit = false),
        )

        owner.release(deniedOwner)
        owner.release(providerDefault)
        owner.release(imperativeDefault)
        assertEquals(listOf(allowed, denied), applied)

        val updated = denied.copy(coppaApplies = true)
        assertEquals(
            PrivacySeedResult.OwnerUpdated,
            owner.seed(previousExplicit, updated, explicit = false),
        )
        assertEquals(listOf(allowed, denied, updated), applied)
    }

    @Test
    fun `provider-first denied consent is not overwritten by imperative defaults`() {
        val applied = mutableListOf<SimulaPrivacyConfig>()
        val owner = FirstPrivacyConfigOwner(applied::add)
        val provider = PrivacyOwnerToken()
        val imperative = PrivacyOwnerToken()
        val denied = SimulaPrivacyConfig(hasPrivacyConsent = false)

        assertEquals(PrivacySeedResult.FirstApplied, owner.seed(provider, denied, explicit = false))
        assertEquals(
            PrivacySeedResult.IgnoredDefault,
            owner.seed(imperative, SimulaPrivacyConfig(), explicit = false),
        )
        assertEquals(listOf(denied), applied)
    }

    @Test
    fun `imperative-first config is not overwritten by provider defaults`() {
        val applied = mutableListOf<SimulaPrivacyConfig>()
        val owner = FirstPrivacyConfigOwner(applied::add)
        val imperative = PrivacyOwnerToken()
        val provider = PrivacyOwnerToken()
        val first = SimulaPrivacyConfig(hasPrivacyConsent = false, coppaApplies = true)

        owner.seed(imperative, first, explicit = false)
        owner.seed(provider, SimulaPrivacyConfig(), explicit = false)

        assertEquals(listOf(first), applied)
    }

    @Test
    fun `same provider legacy flag runtime update applies`() {
        val applied = mutableListOf<SimulaPrivacyConfig>()
        val owner = FirstPrivacyConfigOwner(applied::add)
        val provider = PrivacyOwnerToken()
        val initial = SimulaPrivacyConfig()
        val updated = SimulaPrivacyConfig(hasPrivacyConsent = false)

        owner.seed(provider, initial, explicit = false)
        assertEquals(
            PrivacySeedResult.OwnerUpdated,
            owner.seed(provider, updated, explicit = false),
        )

        assertEquals(listOf(initial, updated), applied)
    }

    @Test
    fun `explicit takeover changes owner and former owner default is ignored`() {
        val applied = mutableListOf<SimulaPrivacyConfig>()
        val owner = FirstPrivacyConfigOwner(applied::add)
        val provider = PrivacyOwnerToken()
        val imperative = PrivacyOwnerToken()
        val initial = SimulaPrivacyConfig()
        val explicitImperative = SimulaPrivacyConfig(coppaApplies = true)

        owner.seed(provider, initial, explicit = false)
        assertEquals(
            PrivacySeedResult.ExplicitReplaced,
            owner.seed(imperative, explicitImperative, explicit = true),
        )
        assertEquals(
            PrivacySeedResult.IgnoredDefault,
            owner.seed(provider, SimulaPrivacyConfig(hasPrivacyConsent = false), explicit = false),
        )

        assertEquals(listOf(initial, explicitImperative), applied)
    }

    @Test
    fun `explicit first entry remains owned against later defaults`() {
        val applied = mutableListOf<SimulaPrivacyConfig>()
        val owner = FirstPrivacyConfigOwner(applied::add)
        val explicitOwner = PrivacyOwnerToken()
        val laterDefault = PrivacyOwnerToken()
        val explicitFirst = SimulaPrivacyConfig(
            hasPrivacyConsent = false,
            enableAdvertisingId = true,
        )

        assertEquals(
            PrivacySeedResult.FirstApplied,
            owner.seed(explicitOwner, explicitFirst, explicit = true),
        )
        assertEquals(
            PrivacySeedResult.IgnoredDefault,
            owner.seed(laterDefault, SimulaPrivacyConfig(), explicit = false),
        )

        assertEquals(listOf(explicitFirst), applied)
    }

    @Test
    fun `concurrent defaults apply exactly one first config`() {
        val applied = mutableListOf<SimulaPrivacyConfig>()
        val owner = FirstPrivacyConfigOwner(applied::add)
        val candidates = listOf(
            SimulaPrivacyConfig(hasPrivacyConsent = false),
            SimulaPrivacyConfig(hasPrivacyConsent = true, coppaApplies = true),
        )
        val ready = CountDownLatch(12)
        val start = CountDownLatch(1)
        val tokens = List(12) { PrivacyOwnerToken() }
        val workers = List(12) { index ->
            thread(start = true) {
                ready.countDown()
                start.await()
                owner.seed(tokens[index], candidates[index % candidates.size], explicit = false)
            }
        }

        ready.await()
        start.countDown()
        workers.forEach { it.join() }

        assertEquals(1, applied.size)
        assertTrue(applied.single() in candidates)
    }

    @Test
    fun `concurrent owner update and release never applies fallback and leaves coherent ownership`() {
        val applied = mutableListOf<SimulaPrivacyConfig>()
        val owner = FirstPrivacyConfigOwner(applied::add)
        val current = PrivacyOwnerToken()
        val fallback = PrivacyOwnerToken()
        val fallbackConfig = SimulaPrivacyConfig(hasPrivacyConsent = false)
        val update = SimulaPrivacyConfig(coppaApplies = true)
        owner.seed(current, SimulaPrivacyConfig(), explicit = false)
        owner.seed(fallback, fallbackConfig, explicit = false)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val workers = listOf(
            thread(start = true) {
                ready.countDown()
                start.await()
                owner.seed(current, update, explicit = false)
            },
            thread(start = true) {
                ready.countDown()
                start.await()
                owner.release(current)
            },
        )

        ready.await()
        start.countDown()
        workers.forEach { it.join() }

        assertTrue(fallbackConfig !in applied)
        val fallbackUpdated = fallbackConfig.copy(coppaApplies = true)
        assertEquals(
            PrivacySeedResult.OwnerUpdated,
            owner.seed(fallback, fallbackUpdated, explicit = false),
        )
        assertEquals(fallbackUpdated, applied.last())
    }
}
