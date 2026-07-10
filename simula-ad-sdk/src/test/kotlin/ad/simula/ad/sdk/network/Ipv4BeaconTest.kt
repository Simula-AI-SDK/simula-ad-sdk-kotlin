package ad.simula.ad.sdk.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier-1 tests for [Ipv4Beacon] — URL contract (sid-first identity), per-identity dedup,
 * in-flight coalescing, failure retryability, and logout reset. Deterministic virtual time +
 * an injected fake sender, so nothing touches Android or the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Ipv4BeaconTest {

    private val sent = mutableListOf<String>()

    @Before
    fun setUp() {
        Ipv4Beacon.resetForTests()
        sent.clear()
        Ipv4Beacon.deviceIdProvider = { "device-123" }
        Ipv4Beacon.clock = { 1_111L }
        Ipv4Beacon.send = { url ->
            sent.add(url)
            true
        }
    }

    @After
    fun tearDown() {
        Ipv4Beacon.resetForTests()
    }

    // ── URL contract ─────────────────────────────────────────────────────────

    @Test
    fun `buildUrl carries the full contract - k sid ppid did p r t`() {
        val url = Ipv4Beacon.buildUrl(
            base = "https://ip4.example/px",
            apiKey = "key-1",
            sessionId = "sess-1",
            ppid = "user-1",
            deviceId = "dev-1",
            reason = Ipv4Beacon.REASON_INIT,
            timestamp = 42L,
        )
        assertEquals(
            "https://ip4.example/px?k=key-1&sid=sess-1&ppid=user-1&did=dev-1&p=android&r=init&t=42",
            url,
        )
    }

    @Test
    fun `buildUrl omits absent sid ppid did`() {
        val url = Ipv4Beacon.buildUrl(
            base = "https://ip4.example/px",
            apiKey = "key-1",
            sessionId = null,
            ppid = "  ",
            deviceId = null,
            reason = Ipv4Beacon.REASON_PPID_UPDATE,
            timestamp = 42L,
        )
        assertEquals("https://ip4.example/px?k=key-1&p=android&r=ppid_update&t=42", url)
    }

    @Test
    fun `buildUrl url-encodes param values`() {
        val url = Ipv4Beacon.buildUrl(
            base = "https://ip4.example/px",
            apiKey = "k&=?",
            sessionId = "s id",
            ppid = "p+p",
            deviceId = null,
            reason = Ipv4Beacon.REASON_INIT,
            timestamp = 1L,
        )
        assertTrue(url.contains("k=k%26%3D%3F"))
        assertTrue(url.contains("sid=s+id"))
        assertTrue(url.contains("ppid=p%2Bp"))
    }

    @Test
    fun `buildUrl appends with ampersand when the base already has a query`() {
        val url = Ipv4Beacon.buildUrl(
            base = "https://ip4.example/px?v=2",
            apiKey = "k",
            sessionId = null,
            ppid = null,
            deviceId = null,
            reason = Ipv4Beacon.REASON_INIT,
            timestamp = 1L,
        )
        assertTrue(url.startsWith("https://ip4.example/px?v=2&k=k"))
    }

    // ── firing + dedup ───────────────────────────────────────────────────────

    @Test
    fun `fire sends one beacon carrying sid ppid did`() = runTest {
        Ipv4Beacon.scope = this
        Ipv4Beacon.fire("key-1", sessionId = "sess-1", ppid = "user-1", reason = Ipv4Beacon.REASON_INIT)
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertTrue(sent[0].contains("k=key-1"))
        assertTrue(sent[0].contains("sid=sess-1"))
        assertTrue(sent[0].contains("ppid=user-1"))
        assertTrue(sent[0].contains("did=device-123"))
        assertTrue(sent[0].contains("r=init"))
    }

    @Test
    fun `a successful capture is deduped for the same identity`() = runTest {
        Ipv4Beacon.scope = this
        Ipv4Beacon.fire("k", "sess", "u", Ipv4Beacon.REASON_INIT)
        advanceUntilIdle()
        Ipv4Beacon.fire("k", "sess", "u", Ipv4Beacon.REASON_PPID_UPDATE)
        advanceUntilIdle()

        assertEquals(1, sent.size)
    }

    @Test
    fun `a new session id is a new identity and re-fires`() = runTest {
        Ipv4Beacon.scope = this
        Ipv4Beacon.fire("k", "sess-1", "u", Ipv4Beacon.REASON_INIT)
        advanceUntilIdle()
        Ipv4Beacon.fire("k", "sess-2", "u", Ipv4Beacon.REASON_INIT)
        advanceUntilIdle()

        assertEquals(2, sent.size)
    }

    @Test
    fun `a new ppid is a new identity and re-fires`() = runTest {
        Ipv4Beacon.scope = this
        Ipv4Beacon.fire("k", "sess", null, Ipv4Beacon.REASON_INIT)
        advanceUntilIdle()
        Ipv4Beacon.fire("k", "sess", "user-1", Ipv4Beacon.REASON_PPID_UPDATE)
        advanceUntilIdle()

        assertEquals(2, sent.size)
    }

    @Test
    fun `overlapping fires for the same identity coalesce to one request`() = runTest {
        Ipv4Beacon.scope = this
        val gate = CompletableDeferred<Unit>()
        Ipv4Beacon.send = { url ->
            sent.add(url)
            gate.await()
            true
        }

        Ipv4Beacon.fire("k", "sess", "u", Ipv4Beacon.REASON_INIT)
        launch { gate.complete(Unit) }
        Ipv4Beacon.fire("k", "sess", "u", Ipv4Beacon.REASON_INIT) // while first is in flight
        advanceUntilIdle()

        assertEquals(1, sent.size)
    }

    // ── failure handling ─────────────────────────────────────────────────────

    @Test
    fun `a failed send stays retryable`() = runTest {
        Ipv4Beacon.scope = this
        var succeed = false
        Ipv4Beacon.send = { url ->
            sent.add(url)
            succeed
        }

        Ipv4Beacon.fire("k", "sess", "u", Ipv4Beacon.REASON_INIT)
        advanceUntilIdle()
        succeed = true
        Ipv4Beacon.fire("k", "sess", "u", Ipv4Beacon.REASON_INIT)
        advanceUntilIdle()
        // Now captured — a third fire is deduped.
        Ipv4Beacon.fire("k", "sess", "u", Ipv4Beacon.REASON_INIT)
        advanceUntilIdle()

        assertEquals(2, sent.size)
    }

    @Test
    fun `a throwing send is swallowed and stays retryable`() = runTest {
        Ipv4Beacon.scope = this
        var shouldThrow = true
        Ipv4Beacon.send = { url ->
            sent.add(url)
            if (shouldThrow) throw RuntimeException("offline")
            true
        }

        Ipv4Beacon.fire("k", "sess", "u", Ipv4Beacon.REASON_INIT)
        advanceUntilIdle()
        shouldThrow = false
        Ipv4Beacon.fire("k", "sess", "u", Ipv4Beacon.REASON_INIT)
        advanceUntilIdle()

        assertEquals(2, sent.size)
    }

    // ── logout reset ─────────────────────────────────────────────────────────

    @Test
    fun `logout resets dedup so a re-login with the same ppid recaptures`() = runTest {
        Ipv4Beacon.scope = this
        Ipv4Beacon.fire("k", "sess", "user-1", Ipv4Beacon.REASON_INIT)
        advanceUntilIdle()
        Ipv4Beacon.onLogout()
        Ipv4Beacon.fire("k", "sess", "user-1", Ipv4Beacon.REASON_PPID_UPDATE)
        advanceUntilIdle()

        assertEquals(2, sent.size)
    }

    @Test
    fun `a completion that lands after a logout does not resurrect stale dedup state`() = runTest {
        Ipv4Beacon.scope = this
        val gate = CompletableDeferred<Unit>()
        var gated = true
        Ipv4Beacon.send = { url ->
            sent.add(url)
            if (gated) gate.await()
            true
        }

        Ipv4Beacon.fire("k", "sess", "u", Ipv4Beacon.REASON_INIT) // in flight, gated
        launch {
            Ipv4Beacon.onLogout() // clears bookkeeping while the fire is mid-flight
            gated = false
            gate.complete(Unit) // stale fire now completes successfully
        }
        advanceUntilIdle()

        // The stale success must NOT mark the identity captured — post-logout it fires again.
        Ipv4Beacon.fire("k", "sess", "u", Ipv4Beacon.REASON_PPID_UPDATE)
        advanceUntilIdle()
        assertEquals(2, sent.size)
    }

    // ── disabled / invalid input ─────────────────────────────────────────────

    @Test
    fun `a blank url disables the beacon`() = runTest {
        Ipv4Beacon.scope = this
        Ipv4Beacon.url = "   "
        Ipv4Beacon.fire("k", "sess", "u", Ipv4Beacon.REASON_INIT)
        advanceUntilIdle()

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a blank apiKey never fires`() = runTest {
        Ipv4Beacon.scope = this
        Ipv4Beacon.fire("  ", "sess", "u", Ipv4Beacon.REASON_INIT)
        advanceUntilIdle()

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `fire itself never throws even when the scope is live and send explodes`() = runTest {
        Ipv4Beacon.scope = this
        Ipv4Beacon.send = { throw IllegalStateException("boom") }
        Ipv4Beacon.fire("k", "sess", "u", Ipv4Beacon.REASON_INIT)
        advanceUntilIdle()

        assertFalse("nothing recorded", sent.isNotEmpty())
    }
}
