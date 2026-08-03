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
    fun `store single upserts bulk replaces and empty clears`() {
        var warnings = 0
        val store = ExtraParametersStore { warnings++ }

        store.set("placement", "feed")
        store.set("placement", "detail")
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
}
