package ad.simula.ad.sdk.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SqliteMigrationDecisionsTest {
    @Test
    fun `beacon persistence and schema identity include api key`() {
        val first = PendingBeacon("imp", "seen", apiKey = "key-a").persistenceKey()
        val second = PendingBeacon("imp", "seen", apiKey = "key-b").persistenceKey()

        assertFalse(first == second)
        assertEquals("api_key, impression_id, action", BEACON_PRIMARY_KEY_SQL)
    }

    @Test
    fun `successful insert does not require an existence query`() {
        var queried = false
        assertTrue(migrationRowPersisted(42L) { queried = true; false })
        assertFalse(queried)
    }

    @Test
    fun `ignored insert is accepted only when the row already exists`() {
        assertTrue(migrationRowPersisted(-1L) { true })
        assertFalse(migrationRowPersisted(-1L) { false })
    }

    @Test
    fun `beacon migration serializes a stable ttl baseline and api key`() {
        val attempted = PendingBeacon(
            impressionId = "imp",
            action = "seen",
            lastAttemptTimestamp = 4_000L,
        )
        val neverAttempted = PendingBeacon(impressionId = "new", action = "shown")

        assertEquals(4_000L, normalizeMigratedBeacon(attempted, now = 9_000L, fallbackApiKey = "key").createdAt)
        val normalized = normalizeMigratedBeacon(neverAttempted, now = 9_000L, fallbackApiKey = "key")
        assertEquals(9_000L, normalized.createdAt)
        assertEquals("key", normalized.apiKey)
        assertEquals(normalized, Json.decodeFromString<PendingBeacon>(Json.encodeToString(normalized)))
        assertEquals(
            normalized,
            normalizeMigratedBeacon(normalized, now = 20_000L, fallbackApiKey = "other"),
        )
    }

    @Test
    fun `verification migration keeps and serializes createdAt ahead of other baselines`() {
        val verification = PendingVerification(
            serveId = "serve",
            sessionId = "session",
            elapsedPlayTime = 1.0,
            retryCount = 1,
            lastAttemptTimestamp = 4_000L,
            createdAt = 2_000L,
        )

        val normalized = normalizeMigratedVerification(verification, now = 9_000L)
        assertEquals(2_000L, normalized.createdAt)
        assertEquals(normalized, Json.decodeFromString<PendingVerification>(Json.encodeToString(normalized)))
    }
}
