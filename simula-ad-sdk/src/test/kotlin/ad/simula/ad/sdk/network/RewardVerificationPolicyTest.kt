package ad.simula.ad.sdk.network

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
