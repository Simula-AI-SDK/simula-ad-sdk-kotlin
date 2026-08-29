package ad.simula.ad.sdk.minigame

import org.junit.Assert.assertEquals
import org.junit.Test

class GameGridPolicyTest {
    @Test
    fun `carousel gesture cannot skip catalog entries`() {
        assertEquals(5f, boundedCarouselSnapTarget(startPosition = 4f, projectedPosition = 10f))
        assertEquals(3f, boundedCarouselSnapTarget(startPosition = 4f, projectedPosition = -10f))
        assertEquals(4f, boundedCarouselSnapTarget(startPosition = 4f, projectedPosition = 4.4f))
        assertEquals(0f, boundedCarouselSnapTarget(startPosition = Float.NaN, projectedPosition = 10f))
    }

    @Test
    fun `carousel visits entire catalog before wrapping`() {
        assertEquals((0 until 19).toList(), (0 until 19).map { carouselGameIndex(it.toFloat(), 19) })
        assertEquals(0, carouselGameIndex(19f, 19))
        assertEquals(18, carouselGameIndex(-1f, 19))
    }
}
