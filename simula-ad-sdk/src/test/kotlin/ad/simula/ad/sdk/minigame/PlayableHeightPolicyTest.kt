package ad.simula.ad.sdk.minigame

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayableHeightPolicyTest {

    @Test
    fun `null and malformed heights resolve fullscreen`() {
        assertFalse(isBottomSheetPlayableHeight(null, screenHeightDp = 1_000))
        assertFalse(isBottomSheetPlayableHeight("not-a-height", screenHeightDp = 1_000))
        assertFalse(isBottomSheetPlayableHeight("95", screenHeightDp = 1_000))
    }

    @Test
    fun `percent heights use the fullscreen threshold`() {
        assertTrue(isBottomSheetPlayableHeight("94%", screenHeightDp = 1_000))
        assertFalse(isBottomSheetPlayableHeight("95%", screenHeightDp = 1_000))
        assertFalse(isBottomSheetPlayableHeight("99%", screenHeightDp = 1_000))
        assertFalse(isBottomSheetPlayableHeight("100%", screenHeightDp = 1_000))
    }

    @Test
    fun `pixel heights use the resolved height and fullscreen threshold`() {
        assertTrue(isBottomSheetPlayableHeight(949, screenHeightDp = 1_000))
        assertFalse(isBottomSheetPlayableHeight(950, screenHeightDp = 1_000))
        assertFalse(isBottomSheetPlayableHeight(1_200, screenHeightDp = 1_000))
    }

    @Test
    fun `minimum height resolves short screens fullscreen`() {
        assertFalse(isBottomSheetPlayableHeight("50%", screenHeightDp = 480))
        assertFalse(isBottomSheetPlayableHeight(100, screenHeightDp = 480))
    }
}
