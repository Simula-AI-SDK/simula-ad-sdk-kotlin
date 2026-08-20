package ad.simula.ad.sdk.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenPresentationRegistryTest {

    @Test
    fun `multiple presentation tokens independently block ready prewarm`() {
        val registry = PresentationTokenRegistry()

        registry.claim("interstitial-a")
        registry.claim("rewarded-b")
        assertTrue(registry.hasActivePresentation())

        registry.release("interstitial-a")
        assertTrue(registry.hasActivePresentation())

        registry.release("rewarded-b")
        assertFalse(registry.hasActivePresentation())
    }

    @Test
    fun `stale token release cannot clear another presentation`() {
        val registry = PresentationTokenRegistry()

        registry.claim("old")
        registry.claim("current")
        registry.release("old")
        registry.release("old")

        assertTrue(registry.hasActivePresentation())
        registry.release("current")
        assertFalse(registry.hasActivePresentation())
    }

    @Test
    fun `format namespaces keep identical handoff tokens independent`() {
        val registry = PresentationTokenRegistry()

        registry.claim("interstitial:same")
        registry.claim("rewarded:same")
        registry.release("interstitial:same")

        assertTrue(registry.hasActivePresentation())
        registry.release("rewarded:same")
        assertFalse(registry.hasActivePresentation())
    }
}
