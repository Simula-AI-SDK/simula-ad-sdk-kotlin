package ad.simula.ad.sdk.core

import ad.simula.ad.sdk.privacy.SimulaPrivacyConfig
import ad.simula.ad.sdk.privacy.PrivacyOwnerToken
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessApiKeyOwnerTest {
    @Test
    fun `sequential duplicate imperative explicit config cannot mutate privacy`() {
        val gate = ImperativeInitializationGate()
        val applied = mutableListOf<SimulaPrivacyConfig>()
        val initial = SimulaPrivacyConfig(hasPrivacyConsent = false)
        val duplicateExplicit = SimulaPrivacyConfig(coppaApplies = true)

        val first = gate.initialize(
            claimAndSeed = { applied += initial; ApiKeyOwnership.Owner },
            onWinner = { "initialized" },
        )
        val duplicate = gate.initialize(
            claimAndSeed = { applied += duplicateExplicit; ApiKeyOwnership.Compatible },
            onWinner = { "must-not-run" },
        )

        assertTrue(first is ImperativeInitializationAttempt.Winner)
        assertEquals(ImperativeInitializationAttempt.Duplicate, duplicate)
        assertEquals(listOf(initial), applied)
    }

    @Test
    fun `concurrent duplicate imperative explicit config cannot mutate privacy`() {
        val gate = ImperativeInitializationGate()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val winnerEntered = CountDownLatch(1)
        val releaseWinner = CountDownLatch(1)
        val applied = ConcurrentLinkedQueue<SimulaPrivacyConfig>()
        val results = ConcurrentLinkedQueue<ImperativeInitializationAttempt<Int>>()
        val workers = List(2) { index ->
            thread(start = true) {
                ready.countDown()
                start.await()
                results += gate.initialize(
                    claimAndSeed = {
                        applied += SimulaPrivacyConfig(hasPrivacyConsent = index == 0)
                        ApiKeyOwnership.Compatible
                    },
                    onWinner = {
                        winnerEntered.countDown()
                        releaseWinner.await()
                        index
                    },
                )
            }
        }

        ready.await()
        start.countDown()
        winnerEntered.await()
        releaseWinner.countDown()
        workers.forEach { it.join() }

        assertEquals(1, applied.size)
        assertEquals(1, results.count { it is ImperativeInitializationAttempt.Winner })
        assertEquals(1, results.count { it === ImperativeInitializationAttempt.Duplicate })
    }

    @Test
    fun `initialized imperative check prevents seed callback`() {
        val gate = ImperativeInitializationGate()
        gate.initialize(
            claimAndSeed = { ApiKeyOwnership.Owner },
            onWinner = {},
        )
        var seedCalled = false

        val duplicate = gate.initialize(
            claimAndSeed = {
                seedCalled = true
                ApiKeyOwnership.Compatible
            },
            onWinner = {},
        )

        assertEquals(ImperativeInitializationAttempt.Duplicate, duplicate)
        assertEquals(false, seedCalled)
    }

    @Test
    fun `abandoned precommit claim leaves owner empty and committed composition wins`() {
        val owner = FirstApiKeyOwner()
        val abandoned = PostCommitApiKeyClaim { owner.claim("abandoned") }
        val committed = PostCommitApiKeyClaim { owner.claim("committed") }

        assertEquals(null, abandoned.result())
        assertEquals(null, committed.result())
        assertEquals(ApiKeyOwnership.Owner, committed.commit())
        assertEquals(ApiKeyOwnership.Incompatible, abandoned.commit())
    }

    @Test
    fun `entry transaction claims key before privacy and mismatch cannot seed`() {
        val owner = FirstApiKeyOwner()
        val seeded = mutableListOf<SimulaPrivacyConfig>()
        val entry = ApiKeyPrivacyEntryOwner(
            apiKeyOwner = owner,
            seedPrivacy = { _, config, _ ->
                assertEquals(ApiKeyOwnership.Compatible, owner.claim("owner"))
                seeded += config
            },
        )
        val privacyOwner = PrivacyOwnerToken()
        val privacy = SimulaPrivacyConfig(hasPrivacyConsent = false)

        assertEquals(
            ApiKeyOwnership.Owner,
            entry.claimAndSeedPrivacy("owner", privacyOwner, privacy, false),
        )
        assertEquals(
            ApiKeyOwnership.Incompatible,
            entry.claimAndSeedPrivacy(
                "mismatch",
                PrivacyOwnerToken(),
                SimulaPrivacyConfig(),
                true,
            ),
        )
        assertEquals(listOf(privacy), seeded)
    }

    @Test
    fun `incompatible provider transition releases old privacy entry and cannot reseed`() {
        val keyOwner = FirstApiKeyOwner()
        val applied = mutableListOf<SimulaPrivacyConfig>()
        val privacyOwner = ad.simula.ad.sdk.privacy.FirstPrivacyConfigOwner(applied::add)
        val activeToken = PrivacyOwnerToken()
        val entry = ApiKeyPrivacyEntryOwner(
            apiKeyOwner = keyOwner,
            seedPrivacy = { token, config, explicit -> privacyOwner.seed(token, config, explicit) },
            releasePrivacy = privacyOwner::release,
        )
        val activeConfig = SimulaPrivacyConfig(hasPrivacyConsent = false)

        assertEquals(
            ApiKeyOwnership.Owner,
            entry.claimAndSeedPrivacy("active", activeToken, activeConfig, false),
        )
        assertEquals(
            ApiKeyOwnership.Incompatible,
            entry.claimAndSeedPrivacy("incompatible", activeToken, SimulaPrivacyConfig(), true),
        )

        assertEquals(listOf(activeConfig), applied)
    }

    @Test
    fun `provider-first and imperative-first reject a later different key`() {
        val providerFirst = FirstApiKeyOwner()
        assertEquals(ApiKeyOwnership.Owner, providerFirst.claim("provider"))
        assertEquals(ApiKeyOwnership.Incompatible, providerFirst.claim("imperative"))

        val imperativeFirst = FirstApiKeyOwner()
        assertEquals(ApiKeyOwnership.Owner, imperativeFirst.claim("imperative"))
        assertEquals(ApiKeyOwnership.Incompatible, imperativeFirst.claim("provider"))
    }

    @Test
    fun `same key remains compatible across entry points`() {
        val owner = FirstApiKeyOwner()

        assertEquals(ApiKeyOwnership.Owner, owner.claim("shared"))
        assertEquals(ApiKeyOwnership.Compatible, owner.claim("shared"))
    }

    @Test
    fun `concurrent claims select exactly one key owner`() {
        val owner = FirstApiKeyOwner()
        val ready = CountDownLatch(12)
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<Pair<String, ApiKeyOwnership>>()
        val workers = List(12) { index ->
            thread(start = true) {
                val key = if (index % 2 == 0) "a" else "b"
                ready.countDown()
                start.await()
                results += key to owner.claim(key)
            }
        }

        ready.await()
        start.countDown()
        workers.forEach { it.join() }

        val winningKey = results.single { it.second == ApiKeyOwnership.Owner }.first
        assertTrue(results.filter { it.first == winningKey }.all { it.second.isCompatible })
        assertTrue(results.filter { it.first != winningKey }.all { it.second == ApiKeyOwnership.Incompatible })
    }

    @Test
    fun `incompatible entry cannot reach session or beacon work`() {
        val owner = FirstApiKeyOwner()
        var sessionRequests = 0
        var beaconEnqueues = 0
        owner.claim("a")

        if (owner.claim("b").isCompatible) {
            sessionRequests++
            beaconEnqueues++
        }

        assertEquals(0, sessionRequests)
        assertEquals(0, beaconEnqueues)
    }
}
