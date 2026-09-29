package ad.simula.ad.sdk.network

import org.junit.Assert.*
import org.junit.Test

class ArtifactOptionSelectionTest {
    @Test fun defaultsOffAndFreezes() {
        val selection = ArtifactOptionSelection()
        assertEquals(emptyMap<String, String>(), selection.headers())
        assertFalse(selection.configure(true))
        assertTrue(selection.configure(false))
    }

    @Test fun explicitSettingIsIdempotentAndCannotChange() {
        val selection = ArtifactOptionSelection()
        assertTrue(selection.configure(true))
        assertTrue(selection.configure(true))
        assertEquals(mapOf("X-Simula-Dev-Hide-Companion" to "true"), selection.headers())
        assertFalse(selection.configure(false))
        assertTrue(selection.freeze())
    }
}
