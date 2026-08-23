package ad.simula.ad.sdk.telemetry

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryIdentityRouterTest {

    @Test
    fun `provider-first identity is used until imperative identity takes over`() {
        val router = TelemetryIdentityRouter()
        val providerToken = router.createProviderToken()
        var providerSession = "provider-session"
        var providerUser = "provider-user"
        router.bindProvider(providerToken, { providerSession }, { providerUser })

        assertEquals(TelemetryIdentity("provider-session", "provider-user"), router.identity())

        providerSession = "provider-session-2"
        providerUser = "provider-user-2"
        assertEquals(TelemetryIdentity("provider-session-2", "provider-user-2"), router.identity())

        router.bindImperative({ "imperative-session" }, { "imperative-user" })

        assertEquals(TelemetryIdentity("imperative-session", "imperative-user"), router.identity())

        router.bindProvider(providerToken, { "replacement-session" }, { "replacement-user" })
        assertEquals(TelemetryIdentity("imperative-session", "imperative-user"), router.identity())
    }

    @Test
    fun `nested provider disposal restores the prior active identity`() {
        val router = TelemetryIdentityRouter()
        val outer = router.createProviderToken()
        val inner = router.createProviderToken()
        router.bindProvider(outer, { "outer-session" }, { "outer-user" })
        router.bindProvider(inner, { "inner-session" }, { "inner-user" })

        assertEquals(TelemetryIdentity("inner-session", "inner-user"), router.identity())

        router.unbindProvider(inner)

        assertEquals(TelemetryIdentity("outer-session", "outer-user"), router.identity())
    }

    @Test
    fun `disposing the last provider yields empty identity`() {
        val router = TelemetryIdentityRouter()
        val token = router.createProviderToken()
        router.bindProvider(token, { "session" }, { "user" })

        router.unbindProvider(token)

        assertEquals(TelemetryIdentity(null, null), router.identity())
    }

    @Test
    fun `rebinding a provider token updates and reorders it as latest`() {
        val router = TelemetryIdentityRouter()
        val first = router.createProviderToken()
        val second = router.createProviderToken()
        router.bindProvider(first, { "session-a" }, { "user-a" })
        router.bindProvider(second, { "session-b" }, { "user-b" })

        router.bindProvider(first, { "session-a2" }, { "user-a2" })

        assertEquals(TelemetryIdentity("session-a2", "user-a2"), router.identity())
        router.unbindProvider(first)

        assertEquals(TelemetryIdentity("session-b", "user-b"), router.identity())
    }

    @Test
    fun `same-token replacement never exposes prior provider or empty identity`() {
        val router = TelemetryIdentityRouter()
        val outer = router.createProviderToken()
        val replacing = router.createProviderToken()
        val outerIdentity = TelemetryIdentity("outer-session", "outer-user")
        val oldIdentity = TelemetryIdentity("old-session", "old-user")
        val newIdentity = TelemetryIdentity("new-session", "new-user")
        router.bindProvider(outer, { outerIdentity.sessionId }, { outerIdentity.primaryUserId })
        router.bindProvider(replacing, { oldIdentity.sessionId }, { oldIdentity.primaryUserId })
        val ready = CountDownLatch(5)
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<TelemetryIdentity>()

        val writer = thread(start = true) {
            ready.countDown()
            start.await()
            repeat(5_000) { iteration ->
                val next = if (iteration % 2 == 0) newIdentity else oldIdentity
                router.bindProvider(replacing, { next.sessionId }, { next.primaryUserId })
            }
        }
        val readers = List(4) {
            thread(start = true) {
                ready.countDown()
                start.await()
                repeat(10_000) {
                    val identity = router.identity()
                    if (identity != oldIdentity && identity != newIdentity) failures.add(identity)
                }
            }
        }

        ready.await()
        start.countDown()
        (readers + writer).forEach { it.join() }

        assertTrue("replacement exposed a transient identity: $failures", failures.isEmpty())
        router.unbindProvider(replacing)
        assertEquals(outerIdentity, router.identity())
    }

    @Test
    fun `concurrent provider binds and reads keep each source pair coherent`() {
        val router = TelemetryIdentityRouter()
        val seed = router.createProviderToken()
        router.bindProvider(seed, { "session-0" }, { "user-0" })
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
                    router.bindProvider(token, { "session-$suffix" }, { "user-$suffix" })
                }
            }
        }
        val readers = List(4) {
            thread(start = true) {
                ready.countDown()
                start.await()
                repeat(4_000) {
                    val identity = router.identity()
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
}
