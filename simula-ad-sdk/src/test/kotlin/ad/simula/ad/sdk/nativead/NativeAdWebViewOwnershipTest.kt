package ad.simula.ad.sdk.nativead

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAdWebViewOwnershipTest {

    @Test
    fun `native bridge authenticates only top document messages and height`() {
        val source = nativeBridgeScript("native-capability")

        assertTrue(source.contains("window.top !== window.self"))
        assertTrue(source.contains("'use strict';"))
        assertTrue(source.contains("var bridgeCapability = \"native-capability\""))
        assertEquals(1, source.split("native-capability").size - 1)
        assertTrue(source.contains("e.isTrusted !== true"))
        assertTrue(source.contains("e.source !== window"))
        assertTrue(source.contains("JSON.stringify.bind(JSON)"))
        assertTrue(source.contains("JSON.parse.bind(JSON)"))
        assertTrue(source.contains("nativeStringify(bridgeCapability)"))
        assertFalse(source.contains("Object.keys(envelope)"))
    }

    @Test
    fun `native bridge falls back at page start when document start is unavailable or fails`() {
        assertEquals(
            NativeBridgeInjectionMode.PAGE_START_FALLBACK,
            nativeBridgeInjectionMode(true, true, documentStartSupported = false, documentStartInstalled = false),
        )
        assertEquals(
            NativeBridgeInjectionMode.PAGE_START_FALLBACK,
            nativeBridgeInjectionMode(true, true, documentStartSupported = true, documentStartInstalled = false),
        )
        assertEquals(
            NativeBridgeInjectionMode.DOCUMENT_START,
            nativeBridgeInjectionMode(true, true, documentStartSupported = true, documentStartInstalled = true),
        )
    }

    @Test
    fun `native bridge refuses install only when cleanup or interface setup is unsafe`() {
        assertEquals(
            NativeBridgeInjectionMode.UNAVAILABLE,
            nativeBridgeInjectionMode(false, true, documentStartSupported = true, documentStartInstalled = true),
        )
        assertEquals(
            NativeBridgeInjectionMode.UNAVAILABLE,
            nativeBridgeInjectionMode(true, false, documentStartSupported = true, documentStartInstalled = true),
        )
    }

    @Test
    fun `rendered native HTML does not inherit unused iframe origin`() {
        assertEquals(
            "https://creative.example/game",
            nativeCreativeInitialPageUrl("https://creative.example/game", null),
        )
        assertEquals(
            null,
            nativeCreativeInitialPageUrl(
                "https://creative.example/game",
                "<html><body>rendered</body></html>",
            ),
        )
    }

    @Test
    fun `claim reuses idle creative and never steals an attached matching session`() {
        assertEquals(
            NativeSessionClaim.REUSE,
            nativeSessionClaim(true, "key", "key", existingAttached = false, existingIsRequested = false),
        )
        assertEquals(
            NativeSessionClaim.EPHEMERAL,
            nativeSessionClaim(true, "key", "key", existingAttached = true, existingIsRequested = false),
        )
        assertEquals(
            NativeSessionClaim.REUSE,
            nativeSessionClaim(true, "key", "key", existingAttached = true, existingIsRequested = true),
        )
    }

    @Test
    fun `claim replaces stale api ownership without destroying an attached predecessor`() {
        assertEquals(
            NativeSessionClaim.REPLACE_IDLE,
            nativeSessionClaim(true, "old", "new", existingAttached = false, existingIsRequested = false),
        )
        assertEquals(
            NativeSessionClaim.REPLACE_ATTACHED,
            nativeSessionClaim(true, "old", "new", existingAttached = true, existingIsRequested = false),
        )
        assertEquals(
            NativeSessionClaim.REGISTER,
            nativeSessionClaim(true, null, "new", existingAttached = false, existingIsRequested = false),
        )
        assertEquals(
            NativeSessionClaim.EPHEMERAL,
            nativeSessionClaim(false, null, "new", existingAttached = false, existingIsRequested = false),
        )
    }

    @Test
    fun `release retains only registered current healthy view when eligible`() {
        assertEquals(
            NativeReleaseDisposition.RETAIN,
            nativeReleaseDisposition(true, true, true, loadFailed = false, renderGone = false, retentionEligible = true),
        )
        assertEquals(
            NativeReleaseDisposition.RECYCLE,
            nativeReleaseDisposition(false, true, true, loadFailed = false, renderGone = false, retentionEligible = true),
        )
        assertEquals(
            NativeReleaseDisposition.RECYCLE,
            nativeReleaseDisposition(true, false, true, loadFailed = false, renderGone = false, retentionEligible = true),
        )
        assertEquals(
            NativeReleaseDisposition.RECYCLE,
            nativeReleaseDisposition(true, true, true, loadFailed = false, renderGone = false, retentionEligible = false),
        )
        assertEquals(
            NativeReleaseDisposition.RECYCLE,
            nativeReleaseDisposition(true, true, true, loadFailed = true, renderGone = false, retentionEligible = true),
        )
        assertEquals(
            NativeReleaseDisposition.DESTROY,
            nativeReleaseDisposition(true, true, true, loadFailed = false, renderGone = true, retentionEligible = true),
        )
        assertEquals(
            NativeReleaseDisposition.IGNORE,
            nativeReleaseDisposition(true, true, false, loadFailed = false, renderGone = false, retentionEligible = true),
        )
        assertEquals(
            NativeReleaseDisposition.IGNORE,
            nativeReleaseDisposition(true, false, false, loadFailed = false, renderGone = true, retentionEligible = true),
        )
    }


    @Test
    fun `native automatic route requires current focused and viewable owner`() {
        assertTrue(
            nativeAutomaticNavigationEligible(
                lifecycleActive = true,
                currentOwner = true,
                logicallyAttached = true,
                attachedToWindow = true,
                shown = true,
                windowFocused = true,
                globallyVisibleFraction = 0.5f,
                visibleFraction = 0.5f,
            ),
        )
        repeat(6) { index ->
            val flags = MutableList(6) { true }.also { it[index] = false }
            assertFalse(
                nativeAutomaticNavigationEligible(
                    lifecycleActive = flags[0],
                    currentOwner = flags[1],
                    logicallyAttached = flags[2],
                    attachedToWindow = flags[3],
                    shown = flags[4],
                    windowFocused = flags[5],
                    globallyVisibleFraction = 1f,
                    visibleFraction = 1f,
                ),
            )
        }
        assertFalse(
            nativeAutomaticNavigationEligible(
                lifecycleActive = true,
                currentOwner = true,
                logicallyAttached = true,
                attachedToWindow = true,
                shown = true,
                windowFocused = true,
                globallyVisibleFraction = 1f,
                visibleFraction = 0.49f,
            ),
        )
        assertFalse(
            nativeAutomaticNavigationEligible(
                lifecycleActive = true,
                currentOwner = true,
                logicallyAttached = true,
                attachedToWindow = true,
                shown = true,
                windowFocused = true,
                globallyVisibleFraction = 0.49f,
                visibleFraction = 1f,
            ),
        )
    }
}
