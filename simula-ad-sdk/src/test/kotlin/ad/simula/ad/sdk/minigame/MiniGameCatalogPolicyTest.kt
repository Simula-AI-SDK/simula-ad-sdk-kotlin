package ad.simula.ad.sdk.minigame

import ad.simula.ad.sdk.model.GameData
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniGameCatalogPolicyTest {
    private val games = (1..10).map { index ->
        GameData(
            id = "game-$index",
            name = "Game $index",
            iconUrl = "",
            description = "",
        )
    }

    @Test
    fun `catalog is capped while preserving server order`() {
        assertEquals(
            listOf("game-1", "game-2", "game-3"),
            limitGamesForMenu(games, maxGamesToShow = 3).map(GameData::id),
        )
        assertEquals(6, limitGamesForMenu(games, maxGamesToShow = 6).size)
        assertEquals(9, limitGamesForMenu(games, maxGamesToShow = 9).size)
    }

    @Test
    fun `catalog smaller than limit is unchanged`() {
        val catalog = games.take(2)

        assertEquals(catalog, limitGamesForMenu(catalog, maxGamesToShow = 6))
    }

    @Test
    fun `non-positive limit safely uses default`() {
        assertEquals(6, limitGamesForMenu(games, maxGamesToShow = 0).size)
        assertEquals(6, limitGamesForMenu(games, maxGamesToShow = -1).size)
    }
}
