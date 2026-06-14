package ad.simula.ad.sdk.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the `/character-selector` request body and the tolerant character
 * parsing the Character Picker relies on. Pure — no network.
 */
class CharacterRequestTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `request body serializes session_id and fill with backend names`() {
        val body = json.encodeToString(
            CharacterSelectorRequestBody(sessionId = "sess_9", fill = 4),
        )
        assertTrue(body.contains("\"session_id\":\"sess_9\""))
        assertTrue(body.contains("\"fill\":4"))
    }

    @Test
    fun `parses the character-selector bare array with imageUrl`() {
        // Exactly what POST /character-selector returns: a bare array of
        // {id, name, imageUrl, description}.
        val body = """
            [
              {"id":"superman","name":"Superman","imageUrl":"https://x/c1.png","description":"hero"},
              {"id":"maya","name":"Maya","imageUrl":"https://x/c3.png","description":"mage"}
            ]
        """.trimIndent()
        val chars = SimulaApiClient.parseCharacters(body)
        assertEquals(2, chars.size)
        assertEquals("superman", chars[0].id)
        assertEquals("Superman", chars[0].name)
        assertEquals("https://x/c1.png", chars[0].imageUrl) // imageUrl
        assertEquals("hero", chars[0].description)
        assertEquals("https://x/c3.png", chars[1].imageUrl)
    }

    @Test
    fun `drops items missing name, description, or image but tolerates a blank id`() {
        val body = """
            [
              {"id":"ok","name":"OK","imageUrl":"u","description":"d"},
              {"name":"no id","imageUrl":"u","description":"d"},
              {"id":"x","imageUrl":"u","description":"d"},
              {"id":"y","name":"Y","description":"d"},
              {"id":"z","name":"Z","imageUrl":"u"}
            ]
        """.trimIndent()
        val chars = SimulaApiClient.parseCharacters(body)
        // Kept: the fully-populated entry and the one missing only an id.
        assertEquals(listOf("ok", ""), chars.map { it.id })
        assertEquals(listOf("OK", "no id"), chars.map { it.name })
    }

    @Test
    fun `still tolerates legacy field names and wrappers`() {
        val legacy = """
            {"characters":[
              {"character_id":"a","character_name":"A","images_1_1":["u1"],"description":"da"},
              {"character_id":"b","character_name":"B","avatar_url":"u2","description":"db"}
            ]}
        """.trimIndent()
        val chars = SimulaApiClient.parseCharacters(legacy)
        assertEquals(2, chars.size)
        assertEquals("u1", chars[0].imageUrl) // images_1_1[0]
        assertEquals("u2", chars[1].imageUrl) // avatar_url fallback

        val wrapped = SimulaApiClient.parseCharacters(
            """{"data":{"data":[{"id":"c","name":"C","image":"u3","description":"dc"}]}}""",
        )
        assertEquals(1, wrapped.size)
        assertEquals("u3", wrapped[0].imageUrl)
    }

    @Test
    fun `returns empty for unexpected shapes instead of throwing`() {
        assertTrue(SimulaApiClient.parseCharacters("""{"unexpected":true}""").isEmpty())
        assertTrue(SimulaApiClient.parseCharacters("""123""").isEmpty())
    }
}
