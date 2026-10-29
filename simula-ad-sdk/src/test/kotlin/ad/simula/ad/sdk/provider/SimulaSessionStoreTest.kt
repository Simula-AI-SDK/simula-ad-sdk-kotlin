package ad.simula.ad.sdk.provider

import ad.simula.ad.sdk.ads.SimulaAds
import ad.simula.ad.sdk.network.CreatedSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulaSessionStoreTest {
    @Test
    fun `omitted creation identity stays anonymous until a consented patch completes`() = runTest {
        var consent = false
        val enteredPatch = CompletableDeferred<Unit>()
        val releasePatch = CompletableDeferred<Boolean>()
        val patches = mutableListOf<String>()
        val store = testStore(
            initialUserID = "user-a",
            allowsPrimaryUserID = { consent },
            returnedUserID = { null },
            patchPpid = { _, _, user ->
                patches += user
                enteredPatch.complete(Unit)
                releasePatch.await()
            },
        ) { _, _, _ -> "anonymous-session" }

        assertEquals("anonymous-session", store.ensureSession())
        runCurrent()
        assertNull(store.sessionUserID)
        assertNull(SimulaAds.consistentSessionId(store.sessionId, store.sessionUserID, "user-a"))
        assertTrue(patches.isEmpty())

        consent = true
        assertEquals("anonymous-session", store.ensureSession())
        enteredPatch.await()
        assertNull(store.sessionUserID)
        assertNull(SimulaAds.consistentSessionId(store.sessionId, store.sessionUserID, "user-a"))
        releasePatch.complete(true)
        runCurrent()
        assertEquals(listOf("user-a"), patches)
        assertEquals("user-a", store.sessionUserID)
        assertEquals("anonymous-session", SimulaAds.consistentSessionId(store.sessionId, store.sessionUserID, "user-a"))
    }

    @Test
    fun `failed identity patch cannot claim that an anonymous session represents a user`() = runTest {
        var consent = false
        var patches = 0
        val store = testStore(
            initialUserID = "user-a", allowsPrimaryUserID = { consent }, returnedUserID = { null },
            patchPpid = { _, _, _ -> ++patches > 1 },
        ) { _, _, _ -> "session" }
        store.ensureSession()
        runCurrent()
        consent = true
        store.ensureSession()
        runCurrent()
        assertEquals(1, patches)
        assertNull(store.sessionUserID)
        store.ensureSession()
        runCurrent()
        assertEquals(2, patches)
        assertEquals("user-a", store.sessionUserID)
    }

    @Test
    fun `generation change is lazy and next ensure replaces the cached session`() = runTest {
        var generation = 0L
        var calls = 0
        val store = testStore(sessionGeneration = { generation }) { _, _, _ ->
            calls++
            "session-$calls"
        }

        assertEquals("session-1", store.ensureSession())
        generation++
        runCurrent()

        assertEquals(1, calls)
        assertEquals("session-1", store.sessionId)
        assertEquals("session-2", store.ensureSession())
        assertEquals(2, calls)
    }

    @Test
    fun `generation bump after cached sample cannot return expired session`() = runTest {
        var generation = 0L
        var generationReads = 0
        var calls = 0
        val store = testStore(
            sessionGeneration = {
                generationReads++
                val sampled = generation
                if (generationReads == 4) generation++
                sampled
            },
        ) { _, _, _ ->
            calls++
            "session-$calls"
        }
        assertEquals("session-1", store.ensureSession())

        assertEquals("session-2", store.ensureSession())

        assertEquals(2, calls)
        assertEquals(1L, generation)
    }

    @Test
    fun `expired refresh replaces the cached id and session user together`() = runTest {
        var generation = 0L
        val seenUsers = mutableListOf<String?>()
        val store = testStore(
            initialUserID = "user-a",
            sessionGeneration = { generation },
        ) { _, _, userID ->
            seenUsers += userID
            if (seenUsers.size == 1) "session-a" else "session-b"
        }

        assertEquals("session-a", store.ensureSession())
        store.updatePpid("user-b")
        generation++
        assertEquals("session-b", store.ensureSession())

        assertEquals(listOf("user-a", "user-b"), seenUsers)
        assertEquals("session-b", store.sessionId)
        assertEquals("user-b", store.sessionUserID)
    }

    @Test
    fun `failed expired refresh fails open but next external ensure retries`() = runTest {
        var generation = 0L
        var calls = 0
        val store = testStore(
            initialUserID = "user-a",
            sessionGeneration = { generation },
        ) { _, _, _ ->
            calls++
            when (calls) {
                1 -> "session-a"
                2 -> null
                else -> "session-b"
            }
        }

        assertEquals("session-a", store.ensureSession())
        store.updatePpid("user-b")
        generation++

        assertEquals("session-a", store.ensureSession())
        assertEquals("session-a", store.sessionId)
        assertEquals("user-a", store.sessionUserID)
        assertEquals("session-b", store.ensureSession())
        assertEquals(3, calls)
        assertEquals("user-b", store.sessionUserID)
    }

    @Test
    fun `same id expired refresh still publishes refreshed session user`() = runTest {
        var generation = 0L
        val store = testStore(
            initialUserID = "user-a",
            sessionGeneration = { generation },
        ) { _, _, _ -> "session" }

        assertEquals("session", store.ensureSession())
        store.updatePpid("user-b")
        generation++
        assertEquals("session", store.ensureSession())

        assertEquals("user-b", store.sessionUserID)
    }

    @Test
    fun `patch completion cannot rewind same id refreshed session user`() = runTest {
        var generation = 0L
        val patchEntered = CompletableDeferred<Unit>()
        val releasePatch = CompletableDeferred<Unit>()
        val patchedUsers = mutableListOf<String>()
        val store = testStore(
            initialUserID = "user-a",
            sessionGeneration = { generation },
            patchPpid = { _, _, userID ->
                patchedUsers += userID
                if (patchedUsers.size == 1) {
                    patchEntered.complete(Unit)
                    releasePatch.await()
                    true
                } else {
                    false
                }
            },
        ) { _, _, _ -> "same-session" }
        assertEquals("same-session", store.ensureSession())

        store.updatePpid("user-b")
        store.reconcileServerPpid()
        patchEntered.await()
        store.updatePpid("user-c")
        generation++
        assertEquals("same-session", store.ensureSession())
        releasePatch.complete(Unit)
        runCurrent()

        assertEquals("user-c", store.sessionUserID)
        assertEquals(listOf("user-b"), patchedUsers)
    }

    @Test
    fun `expired refresh coalesces concurrent ensure callers`() = runTest {
        var generation = 0L
        var calls = 0
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val store = testStore(sessionGeneration = { generation }) { _, _, _ ->
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

        generation++
        val callers = List(5) { async { store.ensureSession() } }
        refreshEntered.await()
        runCurrent()

        assertEquals(2, calls)
        releaseRefresh.complete(Unit)
        assertEquals(List(5) { "session-b" }, callers.awaitAll())
        assertEquals(2, calls)
    }

    @Test
    fun `expired refresh awaits advertising id work before session creation`() = runTest {
        var generation = 0L
        var calls = 0
        val advertisingIdReady = CompletableDeferred<Unit>()
        var refreshStarted = false
        val store = testStore(
            sessionGeneration = { generation },
            beforeExpiredSessionCreate = {
                refreshStarted = true
                advertisingIdReady.await()
            },
        ) { _, _, _ ->
            calls++
            "session-$calls"
        }
        assertEquals("session-1", store.ensureSession())

        generation++
        val callers = List(2) { async { store.ensureSession() } }
        runCurrent()

        assertTrue(refreshStarted)
        assertEquals(1, calls)
        assertTrue(callers.none { it.isCompleted })
        advertisingIdReady.complete(Unit)
        assertEquals(List(2) { "session-2" }, callers.awaitAll())
        assertEquals(2, calls)
    }

    @Test
    fun `store created at an existing generation does not prepare ordinary first creation`() = runTest {
        var generation = 7L
        var prepared = false
        val store = testStore(
            sessionGeneration = { generation },
            beforeExpiredSessionCreate = { prepared = true },
        ) { _, _, _ -> "session" }

        assertEquals("session", store.ensureSession())

        assertFalse(prepared)
    }

    @Test
    fun `generation advancing during an older flight triggers one current refresh`() = runTest {
        var generation = 0L
        var calls = 0
        val initialEntered = CompletableDeferred<Unit>()
        val releaseInitial = CompletableDeferred<Unit>()
        val store = testStore(sessionGeneration = { generation }) { _, _, _ ->
            calls++
            if (calls == 1) {
                initialEntered.complete(Unit)
                releaseInitial.await()
                "session-a"
            } else {
                "session-b"
            }
        }

        val initialCaller = async { store.ensureSession() }
        initialEntered.await()
        generation++
        val foregroundCaller = async { store.ensureSession() }
        releaseInitial.complete(Unit)

        assertEquals("session-b", initialCaller.await())
        assertEquals("session-b", foregroundCaller.await())
        assertEquals(2, calls)
    }

    @Test
    fun `generation advancing during failed initial flight prepares the next attempt`() = runTest {
        var generation = 3L
        var calls = 0
        var preparations = 0
        val initialEntered = CompletableDeferred<Unit>()
        val releaseInitial = CompletableDeferred<Unit>()
        val store = testStore(
            sessionGeneration = { generation },
            beforeExpiredSessionCreate = { preparations++ },
        ) { _, _, _ ->
            calls++
            if (calls == 1) {
                initialEntered.complete(Unit)
                releaseInitial.await()
                null
            } else {
                "session-current"
            }
        }

        val caller = async { store.ensureSession() }
        initialEntered.await()
        generation++
        releaseInitial.complete(Unit)

        assertEquals("session-current", caller.await())
        assertEquals(2, calls)
        assertEquals(1, preparations)
    }

    @Test
    fun `cancelling one waiter does not clear the shared expired refresh`() = runTest {
        var generation = 0L
        var calls = 0
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val store = testStore(sessionGeneration = { generation }) { _, _, _ ->
            calls++
            if (calls == 1) "session-a" else {
                refreshEntered.complete(Unit)
                releaseRefresh.await()
                "session-b"
            }
        }
        store.ensureSession()
        generation++

        val cancelledWaiter = async { store.ensureSession() }
        refreshEntered.await()
        val survivingWaiter = async { store.ensureSession() }
        runCurrent()
        cancelledWaiter.cancel()
        runCurrent()

        assertTrue(cancelledWaiter.isCancelled)
        assertEquals(2, calls)
        releaseRefresh.complete(Unit)
        assertEquals("session-b", survivingWaiter.await())
        assertEquals(2, calls)
    }

    private fun kotlinx.coroutines.test.TestScope.testStore(
        initialUserID: String? = null,
        sessionGeneration: () -> Long = { 0L },
        beforeExpiredSessionCreate: suspend () -> Unit = {},
        patchPpid: suspend (String, String, String) -> Boolean = { _, _, _ -> true },
        allowsPrimaryUserID: () -> Boolean = { true },
        returnedUserID: (String?) -> String? = { it },
        createSession: suspend (String, Boolean, String?) -> String?,
    ) = SimulaSessionStore(
        apiKey = "api-key",
        devMode = false,
        initialUserID = initialUserID,
        workScope = backgroundScope,
        publicationDispatcher = UnconfinedTestDispatcher(testScheduler),
        createSession = { key, dev, user -> createSession(key, dev, user)?.let { CreatedSession(it, returnedUserID(user)) } },
        recordSessionOperation = { _, _, _, _ -> },
        fireIpv4 = { _, _, _, _ -> },
        patchPpid = patchPpid,
        sessionGeneration = sessionGeneration,
        beforeExpiredSessionCreate = beforeExpiredSessionCreate,
        allowsPrimaryUserID = allowsPrimaryUserID,
    )
}
