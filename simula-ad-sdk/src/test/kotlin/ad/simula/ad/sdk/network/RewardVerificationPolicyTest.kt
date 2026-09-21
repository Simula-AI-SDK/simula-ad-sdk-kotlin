package ad.simula.ad.sdk.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier-0 pure-logic tests for the reward-verification retry policy: exponential backoff
 * and the permanent-vs-retryable error classification the queue engine keys off.
 */
class RewardVerificationPolicyTest {

    @Test
    fun `legacy durable verification decodes without completion reason and new row preserves it`() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString<PendingVerification>(
            """{"serveId":"s","sessionId":"session","elapsedPlayTime":4.0,"retryCount":0,"lastAttemptTimestamp":0}""",
        )
        assertEquals(null, legacy.completionReason)

        val current = legacy.copy(completionReason = "video_completed")
        val encoded = json.encodeToString(current)
        val roundTrip = json.decodeFromString<PendingVerification>(encoded)
        assertTrue(encoded.contains("\"completion_reason\":\"video_completed\""))
        assertEquals("video_completed", roundTrip.completionReason)
    }

    @Test
    fun `future completion reason survives restart roundtrip and remains unsupported`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = PendingVerification(
            serveId = "future",
            sessionId = "session",
            elapsedPlayTime = 7.0,
            retryCount = 0,
            lastAttemptTimestamp = 0L,
            completionReason = "future_reward_reason_v2",
        )

        val restarted = json.decodeFromString<PendingVerification>(json.encodeToString(original))

        assertEquals("future_reward_reason_v2", restarted.completionReason)
        assertTrue(hasUnsupportedRewardCompletionReason(restarted))
        assertFalse(
            hasUnsupportedRewardCompletionReason(
                restarted.copy(completionReason = "duration_elapsed"),
            ),
        )
        assertFalse(hasUnsupportedRewardCompletionReason(restarted.copy(completionReason = null)))
    }

    @Test
    fun `backoff is immediate for the first attempt`() {
        assertEquals(0L, rewardVerificationBackoffMs(0))
    }

    @Test
    fun `backoff grows exponentially from 5s and caps at 60s`() {
        assertEquals(5_000L, rewardVerificationBackoffMs(1))
        assertEquals(10_000L, rewardVerificationBackoffMs(2))
        assertEquals(20_000L, rewardVerificationBackoffMs(3))
        assertEquals(40_000L, rewardVerificationBackoffMs(4))
        assertEquals(60_000L, rewardVerificationBackoffMs(5)) // 80s clamped to 60s
        assertEquals(60_000L, rewardVerificationBackoffMs(10))
    }

    @Test
    fun `4xx is a permanent error except 408 and 429`() {
        assertTrue(isPermanentVerificationError(Exception("HTTP error! status: 400")))
        assertTrue(isPermanentVerificationError(Exception("HTTP error! status: 401")))
        assertTrue(isPermanentVerificationError(Exception("HTTP error! status: 403")))
        assertTrue(isPermanentVerificationError(Exception("HTTP error! status: 404")))
        assertFalse(isPermanentVerificationError(Exception("HTTP error! status: 408")))
        assertFalse(isPermanentVerificationError(Exception("HTTP error! status: 429")))
    }

    @Test
    fun `5xx and transport errors are retryable`() {
        assertFalse(isPermanentVerificationError(Exception("HTTP error! status: 500")))
        assertFalse(isPermanentVerificationError(Exception("HTTP error! status: 503")))
        assertFalse(isPermanentVerificationError(Exception("Unable to resolve host \"example.com\"")))
        assertFalse(isPermanentVerificationError(Exception())) // null message
    }
}
