package ad.simula.ad.sdk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExtraParametersTest {

    @Test
    fun `validator accepts length boundaries and unicode`() {
        var warnings = 0
        val result = normalizeExtraParameters(
            mapOf(
                "k".repeat(64) to "v".repeat(256),
                "ключ_日本語" to "café 🚀",
            ),
        ) { warnings++ }

        assertEquals(2, result?.size)
        assertEquals("café 🚀", result?.get("ключ_日本語"))
        assertEquals(0, warnings)
    }

    @Test
    fun `validator measures unicode code points like the backend`() {
        val allowed = "🚀".repeat(64)
        val rejected = "🚀".repeat(65)
        var warnings = 0

        assertEquals(allowed, normalizeExtraParameters(mapOf(allowed to allowed)) { warnings++ }?.get(allowed))
        assertNull(normalizeExtraParameters(mapOf(rejected to "value")) { warnings++ })
        assertEquals(1, warnings)
    }

    @Test
    fun `validator drops invalid entries and warns once without partial failures`() {
        var warnings = 0
        val result = normalizeExtraParameters(
            mapOf(
                "valid" to "ok",
                "" to "empty key",
                "k".repeat(65) to "too long",
                "long-value" to "v".repeat(257),
                "${'$'}reserved" to "x",
                "has.dot" to "x",
            ),
        ) { warnings++ }

        assertEquals(mapOf("valid" to "ok"), result)
        assertEquals(1, warnings)
    }

    @Test
    fun `bulk cap sorts valid keys before taking ten`() {
        var warnings = 0
        val input = (10 downTo 0).associate { index -> "k%02d".format(index) to index.toString() }

        val result = normalizeExtraParameters(input) { warnings++ }

        assertEquals((0..9).map { "k%02d".format(it) }, result?.keys?.toList())
        assertEquals(1, warnings)
    }

    @Test
    fun `bulk cap uses UTF-16 order for cross-platform parity`() {
        val supplementary = "\uD800\uDC00"
        val privateUse = "\uE000"
        val input = (0 until 9).associate { "a$it" to "value" } +
            mapOf(supplementary to "kept", privateUse to "dropped")

        val result = normalizeExtraParameters(input) {}

        assertTrue(supplementary in result.orEmpty())
        assertTrue(privateUse !in result.orEmpty())
    }

    @Test
    fun `normalization defensively copies input and exposes an immutable result`() {
        val input = mutableMapOf("placement" to "feed")
        val result = normalizeExtraParameters(input) { fail("unexpected warning") }

        input["placement"] = "changed"
        assertEquals("feed", result?.get("placement"))
        try {
            @Suppress("UNCHECKED_CAST")
            (result as MutableMap<String, String>)["new"] = "value"
            fail("normalized metadata must be immutable")
        } catch (_: UnsupportedOperationException) {
            // Expected from Collections.unmodifiableMap.
        }
    }

    @Test
    fun `normalization absorbs null entries from raw Java maps`() {
        var warnings = 0
        val raw = java.util.HashMap<Any?, Any?>().apply { put(null, "value") }
        @Suppress("UNCHECKED_CAST")
        val hostile = raw as Map<String, String>

        assertNull(normalizeExtraParameters(hostile) { warnings++ })
        assertEquals(1, warnings)
    }

    @Test
    fun `store single upserts bulk replaces and empty clears`() {
        var warnings = 0
        val store = ExtraParametersStore { warnings++ }

        store.set("placement", "feed")
        val loadSnapshot = store.snapshot()
        store.set("placement", "detail")
        assertEquals(
            "load-time snapshots are not rewritten by later setters",
            mapOf("placement" to "feed"),
            loadSnapshot,
        )
        assertEquals(mapOf("placement" to "detail"), store.snapshot())

        val replacement = mutableMapOf("screen" to "reward")
        store.replace(replacement)
        replacement["screen"] = "changed"
        assertEquals(mapOf("screen" to "reward"), store.snapshot())

        store.replace(emptyMap())
        assertNull(store.snapshot())
        assertEquals(0, warnings)
    }

    @Test
    fun `single insert over cap is ignored without changing existing state`() {
        var warnings = 0
        val store = ExtraParametersStore { warnings++ }
        store.replace((0 until 10).associate { "k$it" to "$it" })

        store.set("overflow", "value")

        assertEquals(10, store.snapshot()?.size)
        assertTrue("overflow" !in store.snapshot().orEmpty())
        assertEquals(1, warnings)
    }

    @Test
    fun `single inserts preserve deterministic UTF-16 key order`() {
        val store = ExtraParametersStore {}

        store.set("z", "last")
        store.set("a", "first")

        assertEquals(listOf("a", "z"), store.snapshot()?.keys?.toList())
    }

    @Test
    fun `single insert uses the same sorted cap as bulk replacement`() {
        var warnings = 0
        val store = ExtraParametersStore { warnings++ }
        store.replace((1..10).associate { "k%02d".format(it) to "$it" })

        store.set("k00", "earliest")

        assertEquals((0..9).map { "k%02d".format(it) }, store.snapshot()?.keys?.toList())
        assertTrue("k10" !in store.snapshot().orEmpty())
        assertEquals(1, warnings)
    }
}
