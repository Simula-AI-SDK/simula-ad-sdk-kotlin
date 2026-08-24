package ad.simula.ad.sdk.telemetry

import ad.simula.ad.sdk.privacy.ConsentSnapshot
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryIdentityRouterTest {

    @Test
    fun `envelope identity reads one privacy generation for PPID and GAID`() {
        var privacyReads = 0

        val identity = resolveTelemetryEnvelopeIdentity(
            apiKey = "key",
            identityProvider = { TelemetryIdentity("session", "ppid") },
            privacyProvider = {
                privacyReads++
                check(privacyReads == 1)
                ConsentSnapshot(
                    hasPrivacyConsent = true,
                    coppaApplies = false,
                    advertisingId = "gaid-generation-1",
                )
            },
        )

        assertEquals(1, privacyReads)
        assertEquals(
            TelemetryEnvelopeIdentity("session", "ppid", "gaid-generation-1"),
            identity,
        )
    }

    @Test
    fun `provider-first identity is used until imperative identity takes over`() {
        val router = TelemetryIdentityRouter()
        val providerToken = router.createProviderToken()
        var providerSession = "provider-session"
        var providerUser = "provider-user"
        router.bindProvider(providerToken, "key-a", { providerSession }, { providerUser })

        assertEquals(TelemetryIdentity("provider-session", "provider-user"), router.identity("key-a"))

        providerSession = "provider-session-2"
        providerUser = "provider-user-2"
        assertEquals(TelemetryIdentity("provider-session-2", "provider-user-2"), router.identity("key-a"))

        router.bindImperative("key-a", { "imperative-session" }, { "imperative-user" })

        assertEquals(TelemetryIdentity("imperative-session", "imperative-user"), router.identity("key-a"))

        router.bindProvider(providerToken, "key-a", { "replacement-session" }, { "replacement-user" })
        assertEquals(TelemetryIdentity("imperative-session", "imperative-user"), router.identity("key-a"))
    }

    @Test
    fun `nested provider disposal restores the prior active identity`() {
        val router = TelemetryIdentityRouter()
        val outer = router.createProviderToken()
        val inner = router.createProviderToken()
        router.bindProvider(outer, "key", { "outer-session" }, { "outer-user" })
        router.bindProvider(inner, "key", { "inner-session" }, { "inner-user" })

        assertEquals(TelemetryIdentity("inner-session", "inner-user"), router.identity("key"))

        router.unbindProvider(inner)

        assertEquals(TelemetryIdentity("outer-session", "outer-user"), router.identity("key"))
    }

    @Test
    fun `disposing the last provider yields empty identity`() {
        val router = TelemetryIdentityRouter()
        val token = router.createProviderToken()
        router.bindProvider(token, "key", { "session" }, { "user" })

        router.unbindProvider(token)

        assertEquals(TelemetryIdentity(null, null), router.identity("key"))
    }

    @Test
    fun `rebinding an outer provider updates source without stealing from mounted inner`() {
        val router = TelemetryIdentityRouter()
        val first = router.createProviderToken()
        val second = router.createProviderToken()
        router.bindProvider(first, "key", { "session-a" }, { "user-a" })
        router.bindProvider(second, "key", { "session-b" }, { "user-b" })

        router.bindProvider(first, "key", { "session-a2" }, { "user-a2" })

        assertEquals(TelemetryIdentity("session-b", "user-b"), router.identity("key"))
        router.unbindProvider(second)

        assertEquals(TelemetryIdentity("session-a2", "user-a2"), router.identity("key"))
    }

    @Test
    fun `same-token replacement never exposes prior provider or empty identity`() {
        val router = TelemetryIdentityRouter()
        val outer = router.createProviderToken()
        val replacing = router.createProviderToken()
        val outerIdentity = TelemetryIdentity("outer-session", "outer-user")
        val oldIdentity = TelemetryIdentity("old-session", "old-user")
        val newIdentity = TelemetryIdentity("new-session", "new-user")
        router.bindProvider(outer, "key", { outerIdentity.sessionId }, { outerIdentity.primaryUserId })
        router.bindProvider(replacing, "key", { oldIdentity.sessionId }, { oldIdentity.primaryUserId })
        val ready = CountDownLatch(5)
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<TelemetryIdentity>()

        val writer = thread(start = true) {
            ready.countDown()
            start.await()
            repeat(5_000) { iteration ->
                val next = if (iteration % 2 == 0) newIdentity else oldIdentity
                router.bindProvider(replacing, "key", { next.sessionId }, { next.primaryUserId })
            }
        }
        val readers = List(4) {
            thread(start = true) {
                ready.countDown()
                start.await()
                repeat(10_000) {
                    val identity = router.identity("key")
                    if (identity != oldIdentity && identity != newIdentity) failures.add(identity)
                }
            }
        }

        ready.await()
        start.countDown()
        (readers + writer).forEach { it.join() }

        assertTrue("replacement exposed a transient identity: $failures", failures.isEmpty())
        router.unbindProvider(replacing)
        assertEquals(outerIdentity, router.identity("key"))
    }

    @Test
    fun `concurrent provider binds and reads keep each source pair coherent`() {
        val router = TelemetryIdentityRouter()
        val seed = router.createProviderToken()
        router.bindProvider(seed, "key", { "session-0" }, { "user-0" })
        val ready = CountDownLatch(6)
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<TelemetryIdentity>()

        val writers = List(2) { worker ->
            val token = router.createProviderToken()
            thread(start = true) {
                ready.countDown()
                start.await()
                repeat(2_000) { iteration ->
                    val suffix = worker * 2_000 + iteration + 1
                    router.bindProvider(token, "key", { "session-$suffix" }, { "user-$suffix" })
                }
            }
        }
        val readers = List(4) {
            thread(start = true) {
                ready.countDown()
                start.await()
                repeat(4_000) {
                    val identity = router.identity("key")
                    if (identity.sessionId?.removePrefix("session-") != identity.primaryUserId?.removePrefix("user-")) {
                        failures.add(identity)
                    }
                }
            }
        }

        ready.await()
        start.countDown()
        (writers + readers).forEach { it.join() }

        assertTrue("identity source must be atomically published: $failures", failures.isEmpty())
    }

    @Test
    fun `different-key imperative cannot label first-key provider telemetry`() {
        val router = TelemetryIdentityRouter()
        val firstKeyProvider = router.createProviderToken()
        val secondKeyProvider = router.createProviderToken()
        router.bindProvider(firstKeyProvider, "first", { "first-session" }, { "first-user" })
        router.bindProvider(secondKeyProvider, "second", { "second-session" }, { "second-user" })
        router.bindImperative("second", { "imperative-session" }, { "imperative-user" })

        assertEquals(TelemetryIdentity("first-session", "first-user"), router.identity("first"))
        assertEquals(TelemetryIdentity("imperative-session", "imperative-user"), router.identity("second"))
        assertEquals(TelemetryIdentity(null, null), router.identity("missing"))
    }

    @Test
    fun `rebinding active inner provider updates its active source`() {
        val router = TelemetryIdentityRouter()
        val outer = router.createProviderToken()
        val inner = router.createProviderToken()
        router.bindProvider(outer, "key", { "outer" }, { "outer-user" })
        router.bindProvider(inner, "key", { "inner" }, { "inner-user" })

        router.bindProvider(inner, "key", { "inner-2" }, { "inner-user-2" })

        assertEquals(TelemetryIdentity("inner-2", "inner-user-2"), router.identity("key"))
    }
}
