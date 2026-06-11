package ad.simula.ad.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the companions request URL and the tolerant character parsing the
 * Character Picker relies on. Pure — no network.
 */
class CharacterRequestTest {

    @Test
    fun `companions url appends session_id when present`() {
        val url = SimulaApiClient.companionsUrl("sess_9")
        assertTrue(url.endsWith("/minigames/companions?session_id=sess_9"))
    }

    @Test
    fun `companions url omits session_id when null or blank`() {
        assertTrue(SimulaApiClient.companionsUrl(null).endsWith("/minigames/companions"))
        assertFalse(SimulaApiClient.companionsUrl("  ").contains("session_id"))
    }

    @Test
    fun `parses characters under characters key with backend field names`() {
        val body = """
            {"characters":[
              {"character_id":"superman","character_name":"Superman","images_1_1":["https://x/c1.png"],"description":"hero"},
              {"character_id":"maya","character_name":"Maya","avatar_url":"https://x/c3.png"}
            ]}
        """.trimIndent()
        val chars = SimulaApiClient.parseCharacters(body)
        assertEquals(2, chars.size)
        assertEquals("superman", chars[0].id)
        assertEquals("Superman", chars[0].name)
        assertEquals("https://x/c1.png", chars[0].image) // images_1_1[0]
        assertEquals("hero", chars[0].description)
        assertEquals("https://x/c3.png", chars[1].image) // avatar_url fallback
    }

    @Test
    fun `parses a bare top-level array and data wrapper, dropping entries without id or name`() {
        val bare = """[{"id":"a","name":"A","image":"u"},{"name":"missing id"}]"""
        val arr = SimulaApiClient.parseCharacters(bare)
        assertEquals(1, arr.size)
        assertEquals("a", arr[0].id)
        assertEquals("u", arr[0].image)

        val wrapped = SimulaApiClient.parseCharacters("""{"data":{"data":[{"id":"b","name":"B"}]}}""")
        assertEquals(1, wrapped.size)
        assertEquals("", wrapped[0].image) // no image field → empty
    }

    @Test
    fun `returns empty for unexpected shapes instead of throwing`() {
        assertTrue(SimulaApiClient.parseCharacters("""{"unexpected":true}""").isEmpty())
        assertTrue(SimulaApiClient.parseCharacters("""123""").isEmpty())
    }
}
