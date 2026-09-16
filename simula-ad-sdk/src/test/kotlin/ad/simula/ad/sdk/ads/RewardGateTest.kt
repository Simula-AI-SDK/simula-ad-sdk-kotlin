package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.RewardCompletionReason
import ad.simula.ad.sdk.model.monotonicRewardCompletionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tier-0 pure-logic tests for the rewarded play-gate arithmetic (foreground-only
 * accumulation → countdown + earn boundary), independent of Compose/lifecycle.
 */
class RewardGateTest {

    @Test
    fun `secondsLeft counts down by whole elapsed seconds`() {
        assertEquals(30, RewardGate.secondsLeft(accumulatedMs = 0L, durationSeconds = 30))
        assertEquals(29, RewardGate.secondsLeft(accumulatedMs = 1_500L, durationSeconds = 30)) // 1.5s → floor 1
        assertEquals(20, RewardGate.secondsLeft(accumulatedMs = 10_000L, durationSeconds = 30))
    }

    @Test
    fun `secondsLeft never goes negative`() {
        assertEquals(0, RewardGate.secondsLeft(accumulatedMs = 30_000L, durationSeconds = 30))
        assertEquals(0, RewardGate.secondsLeft(accumulatedMs = 45_000L, durationSeconds = 30))
    }

    @Test
    fun `isEarned flips exactly at the duration boundary`() {
        assertFalse(RewardGate.isEarned(accumulatedMs = 29_999L, durationSeconds = 30))
        assertTrue(RewardGate.isEarned(accumulatedMs = 30_000L, durationSeconds = 30))
        assertTrue(RewardGate.isEarned(accumulatedMs = 31_000L, durationSeconds = 30))
    }

    @Test
    fun `isEarned is false for a non-positive duration (handled as no-gate by the caller)`() {
        assertFalse(RewardGate.isEarned(accumulatedMs = 0L, durationSeconds = 0))
        assertFalse(RewardGate.isEarned(accumulatedMs = 5_000L, durationSeconds = 0))
        assertFalse(RewardGate.isEarned(accumulatedMs = 5_000L, durationSeconds = -3))
    }

    @Test
    fun `short video completion sends actual duration`() {
        assertEquals(
            4.0,
            RewardGate.verificationElapsedSeconds(
                earned = true,
                actualElapsedSeconds = 4.0,
                completionReason = RewardCompletionReason.VIDEO_COMPLETED,
            ) ?: -1.0,
            0.0,
        )
    }

    @Test
    fun `early complete sends actual measured playback`() {
        assertEquals(
            1.25,
            RewardGate.verificationElapsedSeconds(
                earned = true,
                actualElapsedSeconds = 1.25,
                completionReason = RewardCompletionReason.CREATIVE_COMPLETED,
            ) ?: -1.0,
            0.0,
        )
    }

    @Test
    fun `normal full duration preserves actual verification evidence`() {
        assertEquals(
            31.75,
            RewardGate.verificationElapsedSeconds(
                earned = true,
                actualElapsedSeconds = 31.75,
                completionReason = RewardCompletionReason.DURATION_ELAPSED,
            ) ?: -1.0,
            0.0,
        )
    }

    @Test
    fun `reward cannot verify before it is earned`() {
        assertNull(
            RewardGate.verificationElapsedSeconds(
                earned = false,
                actualElapsedSeconds = 120.0,
                completionReason = RewardCompletionReason.DURATION_ELAPSED,
            ),
        )
    }

    @Test
    fun `verification evidence is always finite and nonnegative`() {
        assertEquals(
            0.0,
            RewardGate.verificationElapsedSeconds(
                earned = true,
                actualElapsedSeconds = Double.NaN,
                completionReason = RewardCompletionReason.CREATIVE_COMPLETED,
            ) ?: -1.0,
            0.0,
        )
        assertEquals(
            0.0,
            RewardGate.verificationElapsedSeconds(
                earned = true,
                actualElapsedSeconds = -2.0,
                completionReason = RewardCompletionReason.CREATIVE_COMPLETED,
            ) ?: -1.0,
            0.0,
        )
    }

    @Test
    fun `completion reason uses exact wire values and first earning cause wins`() {
        assertEquals("duration_elapsed", RewardCompletionReason.DURATION_ELAPSED.wire)
        assertEquals("video_completed", RewardCompletionReason.VIDEO_COMPLETED.wire)
        assertEquals("creative_completed", RewardCompletionReason.CREATIVE_COMPLETED.wire)

        val gateFirst = monotonicRewardCompletionReason(null, RewardCompletionReason.DURATION_ELAPSED)
        assertEquals(
            RewardCompletionReason.DURATION_ELAPSED,
            monotonicRewardCompletionReason(gateFirst, RewardCompletionReason.VIDEO_COMPLETED),
        )
        val videoFirst = monotonicRewardCompletionReason(null, RewardCompletionReason.VIDEO_COMPLETED)
        assertEquals(
            RewardCompletionReason.VIDEO_COMPLETED,
            monotonicRewardCompletionReason(videoFirst, RewardCompletionReason.DURATION_ELAPSED),
        )
    }

    @Test
    fun `earned flag without completion reason cannot verify`() {
        assertNull(
            RewardGate.verificationElapsedSeconds(
                earned = true,
                actualElapsedSeconds = 30.0,
                completionReason = null,
            ),
        )
    }
}
