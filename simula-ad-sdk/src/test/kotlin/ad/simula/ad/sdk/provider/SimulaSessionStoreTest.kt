package ad.simula.ad.sdk.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulaSessionStoreTest {
    @Test
    fun `forced refresh replaces a cached id and its session user together`() = runTest {
        var calls = 0
        val seenUsers = mutableListOf<String?>()
        val store = testStore(initialUserID = "user-a") { _, _, userID ->
            calls++
            seenUsers += userID
            if (calls == 1) "session-a" else "session-b"
        }

        assertEquals("session-a", store.ensureSession())
        store.updatePpid("user-b")
        assertEquals("session-b", store.requestForcedRefresh().await())

        assertEquals(2, calls)
        assertEquals(listOf("user-a", "user-b"), seenUsers)
        assertEquals("session-b", store.sessionId)
        assertEquals("user-b", store.sessionUserID)
    }

    @Test
    fun `failed forced refresh keeps the cached id and session user`() = runTest {
        var calls = 0
        val store = testStore(initialUserID = "user-a") { _, _, _ ->
            calls++
            if (calls == 1) "session-a" else null
        }

        assertEquals("session-a", store.ensureSession())
        store.updatePpid("user-b")
        assertEquals("session-a", store.requestForcedRefresh().await())

        assertEquals(2, calls)
        assertEquals("session-a", store.sessionId)
        assertEquals("user-a", store.sessionUserID)
    }

    @Test
    fun `same id refresh still publishes the refreshed session user`() = runTest {
        val store = testStore(initialUserID = "user-a") { _, _, _ -> "session" }

        assertEquals("session", store.ensureSession())
        store.updatePpid("user-b")
        assertEquals("session", store.requestForcedRefresh().await())

        assertEquals("session", store.sessionId)
        assertEquals("user-b", store.sessionUserID)
    }

    @Test
    fun `forced refresh coalesces lifecycle and immediate session callers`() = runTest {
        var calls = 0
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val store = testStore { _, _, _ ->
            calls++
            if (calls == 1) {
                "session-a"
            } else {
                refreshEntered.complete(Unit)
                releaseRefresh.await()
                "session-b"
            }
        }
        assertEquals("session-a", store.ensureSession())

        val lifecycleRefresh = store.requestForcedRefresh()
        refreshEntered.await()
        val duplicateRefresh = store.requestForcedRefresh()
        val immediateCallers = List(4) { async { store.ensureSession() } }
        runCurrent()

        assertSame(lifecycleRefresh, duplicateRefresh)
        assertEquals(2, calls)
        releaseRefresh.complete(Unit)
        assertEquals(List(4) { "session-b" }, immediateCallers.awaitAll())
        assertEquals("session-b", lifecycleRefresh.await())
        assertEquals(2, calls)
    }

    @Test
    fun `forced refresh claims the flight before waiting for foreground privacy`() = runTest {
        var calls = 0
        val privacyReady = CompletableDeferred<Unit>()
        val store = testStore { _, _, _ ->
            calls++
            if (calls == 1) "session-a" else "session-b"
        }
        assertEquals("session-a", store.ensureSession())

        store.requestForcedRefresh { privacyReady.await() }
        val immediateCaller = async { store.ensureSession() }
        runCurrent()

        assertEquals(1, calls)
        assertTrue(!immediateCaller.isCompleted)
        privacyReady.complete(Unit)
        assertEquals("session-b", immediateCaller.await())
        assertEquals(2, calls)
    }

    @Test
    fun `cancelling one waiter does not clear the shared refresh`() = runTest {
        var calls = 0
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val store = testStore { _, _, _ ->
            calls++
            if (calls == 1) "session-a" else {
                refreshEntered.complete(Unit)
                releaseRefresh.await()
                "session-b"
            }
        }
        store.ensureSession()
        store.requestForcedRefresh()
        refreshEntered.await()

        val cancelledWaiter = async { store.ensureSession() }
        runCurrent()
        cancelledWaiter.cancel()
        runCurrent()
        val survivingWaiter = async { store.ensureSession() }
        runCurrent()

        assertTrue(cancelledWaiter.isCancelled)
        assertEquals(2, calls)
        releaseRefresh.complete(Unit)
        assertEquals("session-b", survivingWaiter.await())
        assertEquals(2, calls)
    }

    private fun kotlinx.coroutines.test.TestScope.testStore(
        initialUserID: String? = null,
        createSession: suspend (String, Boolean, String?) -> String?,
    ) = SimulaSessionStore(
        apiKey = "api-key",
        devMode = false,
        initialUserID = initialUserID,
        workScope = backgroundScope,
        publicationDispatcher = UnconfinedTestDispatcher(testScheduler),
        createSession = createSession,
        recordSessionOperation = { _, _, _, _ -> },
        fireIpv4 = { _, _, _, _ -> },
    )
}
