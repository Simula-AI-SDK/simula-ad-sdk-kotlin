package ad.simula.ad.sdk.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedSingleFlightCacheTest {

    @Test
    fun `concurrent callers share one producer`() = runTest {
        val cache = BoundedSingleFlightCache<String, String>(
            maxCost = 10,
            scope = this,
            costOf = String::length,
        )
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        val first = async {
            cache.load("icon") {
                calls++
                gate.await()
                "bitmap"
            }
        }
        val second = async {
            cache.load("icon") {
                calls++
                "duplicate"
            }
        }
        runCurrent()

        assertEquals(1, calls)
        gate.complete(Unit)
        assertEquals("bitmap", first.await())
        assertEquals("bitmap", second.await())
        assertEquals(1, calls)
    }

    @Test
    fun `cost bound evicts least recently used successful value`() = runTest {
        val cache = BoundedSingleFlightCache<String, String>(
            maxCost = 4,
            scope = this,
            costOf = String::length,
        )

        cache.load("a") { "aa" }
        cache.load("b") { "bb" }
        cache.load("a") { "unused" } // make a most-recent
        cache.load("c") { "cc" }

        assertEquals(listOf("a", "c"), cache.cachedKeys())
    }

    @Test
    fun `oversized and failed values are not retained`() = runTest {
        val cache = BoundedSingleFlightCache<String, String>(
            maxCost = 3,
            scope = this,
            costOf = String::length,
        )
        var oversizedCalls = 0
        var failedCalls = 0

        repeat(2) {
            assertEquals("large", cache.load("large") { oversizedCalls++; "large" })
            assertEquals(null, cache.load("failed") { failedCalls++; null })
        }

        assertEquals(2, oversizedCalls)
        assertEquals(2, failedCalls)
        assertEquals(emptyList<String>(), cache.cachedKeys())
    }

    @Test
    fun `bundled mode negatively caches failure until clear`() = runTest {
        val cache = BoundedSingleFlightCache<String, String>(
            maxCost = 10,
            scope = this,
            costOf = String::length,
            cacheFailures = true,
        )
        var calls = 0

        repeat(2) {
            assertEquals(null, cache.load("failed") { calls++; null })
        }

        assertEquals(1, calls)
        assertTrue(cache.peek("failed") is CachePeek.Failed)
        cache.clear()
        assertTrue(cache.peek("failed") is CachePeek.Miss)
        assertEquals("bitmap", cache.load("failed") { calls++; "bitmap" })
        assertEquals(2, calls)
        assertTrue(cache.peek("failed") is CachePeek.Value)
    }

    @Test
    fun `clear prevents older in-flight producer from repopulating cache`() = runTest {
        val cache = BoundedSingleFlightCache<String, String>(
            maxCost = 10,
            scope = this,
            costOf = String::length,
            cacheFailures = true,
        )
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val oldLoad = async {
            cache.load("icon") {
                calls++
                gate.await()
                "old"
            }
        }
        runCurrent()

        cache.clear()
        val joinedAfterClear = async {
            cache.load("icon") {
                calls++
                "duplicate"
            }
        }
        runCurrent()
        assertEquals(1, calls)
        gate.complete(Unit)
        assertEquals("old", oldLoad.await())
        assertEquals("old", joinedAfterClear.await())
        assertTrue(cache.peek("icon") is CachePeek.Miss)

        assertEquals("new", cache.load("icon") { calls++; "new" })
        assertEquals(2, calls)
        val cached = cache.peek("icon")
        assertTrue(cached is CachePeek.Value && cached.value == "new")
    }
}
