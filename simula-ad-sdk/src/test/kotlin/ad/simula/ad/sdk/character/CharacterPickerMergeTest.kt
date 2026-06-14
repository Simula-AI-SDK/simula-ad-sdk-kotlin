package ad.simula.ad.sdk.character

import ad.simula.ad.sdk.model.CharacterData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the host + backend-backfill merge that fills the 4-slot grid. Pure — no
 * Compose, no network.
 */
class CharacterSelectorMergeTest {

    private fun entry(id: String) = CharacterSelectorEntry(CharacterData(id, id, "", ""))
    private val fallback = listOf(entry("f1"), entry("f2"), entry("f3"), entry("f4"))

    @Test
    fun `host of 4 leaves no room for backend`() {
        val host = listOf(entry("h1"), entry("h2"), entry("h3"), entry("h4"))
        val merged = mergeRoster(host, fetched = listOf(entry("b1")), fallback = fallback)
        assertEquals(listOf("h1", "h2", "h3", "h4"), merged.map { it.data.id })
    }

    @Test
    fun `host of 1 plus 3 backend fills the grid`() {
        val merged = mergeRoster(
            listOf(entry("h1")),
            fetched = listOf(entry("b1"), entry("b2"), entry("b3")),
            fallback = fallback,
        )
        assertEquals(listOf("h1", "b1", "b2", "b3"), merged.map { it.data.id })
    }

    @Test
    fun `partial backend response pads remaining slots with placeholders`() {
        // host 0, fill 4, backend returns only 2 → 2 real + 2 placeholders.
        val merged = mergeRoster(emptyList(), listOf(entry("b1"), entry("b2")), fallback)
        assertEquals(listOf("b1", "b2", "f3", "f4"), merged.map { it.data.id })
    }

    @Test
    fun `empty backend keeps the full placeholder seed`() {
        val merged = mergeRoster(emptyList(), emptyList(), fallback)
        assertEquals(listOf("f1", "f2", "f3", "f4"), merged.map { it.data.id })
    }

    @Test
    fun `host over 4 is capped and nothing is appended`() {
        val host = (1..5).map { entry("h$it") }
        val merged = mergeRoster(host, listOf(entry("b1")), fallback)
        assertEquals(listOf("h1", "h2", "h3", "h4"), merged.map { it.data.id })
    }

    @Test
    fun `backend duplicate of a host character is dropped and topped up by a placeholder`() {
        val merged = mergeRoster(
            listOf(entry("h1")),
            fetched = listOf(entry("h1"), entry("b2"), entry("b3")),
            fallback = fallback,
        )
        assertEquals(listOf("h1", "b2", "b3", "f3"), merged.map { it.data.id })
    }

    @Test
    fun `duplicate backend ids are collapsed`() {
        val merged = mergeRoster(emptyList(), listOf(entry("b1"), entry("b1"), entry("b2")), fallback)
        assertEquals(listOf("b1", "b2", "f3", "f4"), merged.map { it.data.id })
    }

    @Test
    fun `loadingEntries produces distinct loading slots`() {
        val loading = loadingEntries(3)
        assertEquals(3, loading.size)
        assertTrue(loading.all { it.loading })
        assertEquals(3, loading.map { it.data.id }.toSet().size) // distinct ids
        assertTrue(loadingEntries(0).isEmpty())
    }
}
