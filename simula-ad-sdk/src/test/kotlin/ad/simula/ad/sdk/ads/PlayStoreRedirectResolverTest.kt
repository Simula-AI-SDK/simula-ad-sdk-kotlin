package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.network.SimulaHttp
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayStoreRedirectResolverTest {

    @Test
    fun `absolute Play location is returned verbatim without requesting Play`() = runTest {
        val calls = mutableListOf<Call>()
        val play = "https://play.google.com/store/apps/details?id=com.example.app&referrer=utm%3Dabc%2B123"
        val resolver = resolver(calls) { _, _, _ -> response(302, play) }

        val result = resolver.resolve("https://tracker.example/click?token=a%2Fb", "Browser UA", 0L)

        assertEquals(PlayStoreRedirectResolution.Resolved(play, 1), result)
        assertEquals(listOf(Call("https://tracker.example/click?token=a%2Fb", 1_500L, "Browser UA")), calls)
    }

    @Test
    fun `strict Adjust market location preserves referrer when converted to Play HTTPS`() = runTest {
        val calls = mutableListOf<Call>()
        val resolver = resolver(calls) { _, _, _ ->
            response(
                302,
                "market://details/?id=com.example.app&" +
                    "referrer=adjust_reftag%3DcZHZlf9YWb0ru%26utm_source%3DSimula",
            )
        }

        assertEquals(
            PlayStoreRedirectResolution.Resolved(
                "https://play.google.com/store/apps/details?id=com.example.app&" +
                    "referrer=adjust_reftag%3DcZHZlf9YWb0ru%26utm_source%3DSimula",
                1,
            ),
            resolver.resolve("https://app.adjust.com/226m4iih", "Browser UA", 0L),
        )
        assertEquals(1, calls.size)
    }

    @Test
    fun `relative locations resolve against each hop`() = runTest {
        val calls = mutableListOf<Call>()
        val responses = ArrayDeque(
            listOf(
                response(302, "../next?sig=a%2Bb"),
                response(307, "https://play.google.com/store/apps/details?id=app&referrer=x%2Fy"),
            ),
        )
        val resolver = resolver(calls) { _, _, _ -> responses.removeFirst() }

        val result = resolver.resolve("https://tracker.example/a/click", null, 0L)

        assertEquals(
            PlayStoreRedirectResolution.Resolved(
                "https://play.google.com/store/apps/details?id=app&referrer=x%2Fy",
                2,
            ),
            result,
        )
        assertEquals(
            listOf("https://tracker.example/a/click", "https://tracker.example/next?sig=a%2Bb"),
            calls.map(Call::url),
        )
    }

    @Test
    fun `non redirect response falls back to exact original tracker`() = runTest {
        val original = "https://tracker.example/click?token=A%2fb+Z"
        val resolver = resolver(mutableListOf()) { _, _, _ -> response(200) }

        assertEquals(
            PlayStoreRedirectResolution.BrowserFallback(original, RedirectFallbackReason.NON_REDIRECT, 0),
            resolver.resolve(original, null, 0L),
        )
    }

    @Test
    fun `transport failure falls back to browser`() = runTest {
        val original = "https://tracker.example/click"
        val resolver = resolver(mutableListOf()) { _, _, _ -> throw IOException("offline") }

        assertEquals(
            PlayStoreRedirectResolution.BrowserFallback(original, RedirectFallbackReason.TRANSPORT, 0),
            resolver.resolve(original, null, 0L),
        )
    }

    @Test
    fun `five non Play redirects stop at hop limit`() = runTest {
        val calls = mutableListOf<Call>()
        val resolver = resolver(calls) { url, _, _ ->
            val hop = url.substringAfterLast('/').toIntOrNull() ?: 0
            response(302, "https://tracker.example/${hop + 1}")
        }

        val result = resolver.resolve("https://tracker.example/0", null, 0L)

        assertEquals(
            PlayStoreRedirectResolution.BrowserFallback(
                "https://tracker.example/0",
                RedirectFallbackReason.HOP_LIMIT,
                5,
            ),
            result,
        )
        assertEquals(5, calls.size)
    }

    @Test
    fun `redirect loop falls back without another request`() = runTest {
        val calls = mutableListOf<Call>()
        val resolver = resolver(calls) { _, _, _ -> response(302, "https://tracker.example/click") }

        val result = resolver.resolve("https://tracker.example/click", null, 0L)

        assertEquals(
            PlayStoreRedirectResolution.BrowserFallback(
                "https://tracker.example/click",
                RedirectFallbackReason.LOOP,
                1,
            ),
            result,
        )
        assertEquals(1, calls.size)
    }

    @Test
    fun `ambiguous and downgraded locations are rejected`() = runTest {
        val original = "https://tracker.example/click"
        val ambiguous = PlayStoreRedirectResolver(
            client = RedirectHeadClient { _, _, _ ->
                SimulaHttp.RedirectHeadResponse(302, listOf("https://a.example", "https://b.example"))
            },
            clockNanos = { 0L },
        )
        val downgrade = resolver(mutableListOf()) { _, _, _ -> response(302, "http://tracker.example/next") }

        assertEquals(
            PlayStoreRedirectResolution.BrowserFallback(original, RedirectFallbackReason.AMBIGUOUS_LOCATION, 0),
            ambiguous.resolve(original, null, 0L),
        )
        assertEquals(
            PlayStoreRedirectResolution.BrowserFallback(original, RedirectFallbackReason.DOWNGRADE, 0),
            downgrade.resolve(original, null, 0L),
        )
    }

    @Test
    fun `deadline includes time elapsed before resolution starts`() = runTest {
        val calls = mutableListOf<Call>()
        val resolver = PlayStoreRedirectResolver(
            client = RedirectHeadClient { url, timeoutMs, userAgent ->
                calls += Call(url, timeoutMs, userAgent)
                response(302, "https://play.google.com/store/apps/details?id=app")
            },
            clockNanos = { testScheduler.currentTime * 1_000_000L },
        )
        testScheduler.advanceTimeBy(750L)

        resolver.resolve("https://tracker.example/click", null, startedAtNanos = 0L)

        assertEquals(750L, calls.single().timeoutMs)
    }

    @Test
    fun `total deadline cancels a suspended hop into browser fallback`() = runTest {
        val calls = mutableListOf<Call>()
        val resolver = PlayStoreRedirectResolver(
            client = RedirectHeadClient { url, timeoutMs, userAgent ->
                calls += Call(url, timeoutMs, userAgent)
                delay(timeoutMs + 1L)
                response(302, "https://play.google.com/store/apps/details?id=app")
            },
            clockNanos = { testScheduler.currentTime * 1_000_000L },
        )

        val result = resolver.resolve("https://tracker.example/click", null, 0L)

        assertEquals(
            PlayStoreRedirectResolution.BrowserFallback(
                "https://tracker.example/click",
                RedirectFallbackReason.TIMEOUT,
                0,
            ),
            result,
        )
    }

    @Test
    fun `caller cancellation propagates`() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resolver = resolver(mutableListOf()) { _, _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        }
        val result = async { resolver.resolve("https://tracker.example/click", null, 0L) }
        entered.await()

        result.cancel()

        assertTrue(result.isCancelled)
    }

    private fun resolver(
        calls: MutableList<Call>,
        response: suspend (String, Long, String?) -> SimulaHttp.RedirectHeadResponse,
    ) = PlayStoreRedirectResolver(
        client = RedirectHeadClient { url, timeoutMs, userAgent ->
            calls += Call(url, timeoutMs, userAgent)
            response(url, timeoutMs, userAgent)
        },
        clockNanos = { 0L },
    )

    private data class Call(val url: String, val timeoutMs: Long, val userAgent: String?)

    private fun response(code: Int, vararg locations: String) =
        SimulaHttp.RedirectHeadResponse(code, locations.toList())
}
