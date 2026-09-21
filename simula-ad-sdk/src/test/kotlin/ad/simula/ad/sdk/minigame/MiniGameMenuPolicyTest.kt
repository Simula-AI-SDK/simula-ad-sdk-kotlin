package ad.simula.ad.sdk.minigame

import ad.simula.ad.sdk.model.CreativeType
import ad.simula.ad.sdk.network.ClickInteractionGate
import ad.simula.ad.sdk.network.ClickPersistenceHandoff
import ad.simula.ad.sdk.network.ClickSources
import ad.simula.ad.sdk.network.DeclarativeClickRouteOwner
import ad.simula.ad.sdk.network.PresentationRouteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniGameMenuPolicyTest {
    private class Node(var parent: Node? = null, val host: Boolean = false)

    @Test
    fun `minigame video routes use deferred preparation while HTML stays synchronous`() {
        assertTrue(fallbackRouteRequiresDeferredPreparation(isVideo = true))
        assertFalse(fallbackRouteRequiresDeferredPreparation(isVideo = false))
    }

    @Test
    fun `v2 terminal auto advance waits for blockers resumed lifecycle and one frame`() {
        fun ready(
            clickPending: Boolean = false,
            storePending: Boolean = false,
            resumed: Boolean = true,
            framePresented: Boolean = true,
        ) = miniGameVideoAutoAdvanceReady(
            videoPlanV2 = true,
            currentType = CreativeType.VIDEO,
            terminal = true,
            clickHandoffPending = clickPending,
            storeVisitPending = storePending,
            lifecycleResumed = resumed,
            framePresented = framePresented,
        )

        assertFalse(ready(clickPending = true))
        assertFalse(ready(storePending = true))
        assertFalse(ready(resumed = false))
        assertFalse(ready(framePresented = false))
        assertTrue(ready())
        assertFalse(
            miniGameVideoAutoAdvanceReady(
                videoPlanV2 = true,
                currentType = CreativeType.PLAYABLE,
                terminal = true,
                clickHandoffPending = false,
                storeVisitPending = false,
                lifecycleResumed = true,
                framePresented = true,
            ),
        )
    }

    @Test
    fun `nested wrappers resolve the first host`() {
        val host = Node(host = true)
        val nested = Node(Node(Node(host)))

        assertSame(host, unwrapNestedHost(nested, Node::host, Node::parent))
    }

    @Test
    fun `null missing and cyclic wrappers reject without looping`() {
        val first = Node()
        val second = Node(first)
        first.parent = second

        assertNull(unwrapNestedHost<Node>(null, Node::host, Node::parent))
        assertNull(unwrapNestedHost(first, Node::host, Node::parent))
    }

    @Test
    fun `missing Activity owner rejects route before any click can defer`() {
        val owner = DeclarativeClickRouteOwner<Any>(null)

        assertEquals(
            PresentationRouteResult.REJECTED,
            owner.routes.request(route = { true }, completion = {}),
        )
        assertFalse(owner.hasPending())
    }

    @Test
    fun `owner tracks one exact handoff and final cancel terminates it`() {
        val owner = DeclarativeClickRouteOwner(Any())
        val gate = ClickInteractionGate(idFactory = { "click" })
        val handoff = ClickPersistenceHandoff(
            requireNotNull(gate.claim(ClickSources.FALLBACK_CTA)),
        ) {}
        val duplicate = ClickPersistenceHandoff(
            requireNotNull(ClickInteractionGate(idFactory = { "other" }).claim(ClickSources.FALLBACK_CTA)),
        ) {}

        assertTrue(owner.track(handoff))
        assertFalse(owner.track(duplicate))
        owner.cancel()

        assertTrue(handoff.isTerminal())
        assertTrue(duplicate.isTerminal())
        assertFalse(owner.hasPending())
        assertEquals(
            PresentationRouteResult.REJECTED,
            owner.routes.request(route = { true }, completion = {}),
        )
    }
}
