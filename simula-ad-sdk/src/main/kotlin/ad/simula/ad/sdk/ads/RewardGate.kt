package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.RewardCompletionReason

/**
 * Pure play-gate arithmetic for the rewarded minigame, isolated from Compose/lifecycle
 * so it can be unit-tested. The reward is earned once [accumulatedMs] of *foreground*
 * play (see [SimulaRewardedActivity]) reaches [durationSeconds].
 */
internal object RewardGate {
    /** Whole seconds left before the gate is satisfied (never negative). */
    fun secondsLeft(accumulatedMs: Long, durationSeconds: Int): Int =
        maxOf(0, durationSeconds - (accumulatedMs / 1000L).toInt())

    /**
     * True once enough play time has accrued. A non-positive [durationSeconds] is *not*
     * "earned" here — that case (no gate → instantly earned) is handled explicitly by
     * the caller, keeping this a single, unambiguous threshold check.
     */
    fun isEarned(accumulatedMs: Long, durationSeconds: Int): Boolean =
        durationSeconds > 0 && accumulatedMs >= durationSeconds.toLong() * 1000L

    /** Verification uses measured evidence only; malformed values safely normalize to zero. */
    fun verificationElapsedSeconds(
        earned: Boolean,
        actualElapsedSeconds: Double,
        completionReason: RewardCompletionReason?,
    ): Double? {
        if (!earned || completionReason == null) return null
        return actualElapsedSeconds.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    }
}

internal enum class RewardedEarlyCompleteStatus { OPEN, PENDING, CONSUMED, DISCARDED }

internal fun rewardedEarlyCompleteApplicable(isVideo: Boolean): Boolean = !isVideo

/** Presentation-owned bridge signal state. Only visual readiness may consume a pending signal. */
internal class RewardedEarlyCompleteState {
    var status: RewardedEarlyCompleteStatus = RewardedEarlyCompleteStatus.OPEN
        private set

    @Synchronized
    fun signal(creativeReady: Boolean): Boolean = when (status) {
        RewardedEarlyCompleteStatus.OPEN -> {
            status = if (creativeReady) RewardedEarlyCompleteStatus.CONSUMED else RewardedEarlyCompleteStatus.PENDING
            creativeReady
        }
        RewardedEarlyCompleteStatus.PENDING,
        RewardedEarlyCompleteStatus.CONSUMED,
        RewardedEarlyCompleteStatus.DISCARDED,
        -> false
    }

    @Synchronized
    fun consumePending(creativeReady: Boolean): Boolean {
        if (!creativeReady || status != RewardedEarlyCompleteStatus.PENDING) return false
        status = RewardedEarlyCompleteStatus.CONSUMED
        return true
    }

    @Synchronized
    fun discard() {
        if (status == RewardedEarlyCompleteStatus.OPEN || status == RewardedEarlyCompleteStatus.PENDING) {
            status = RewardedEarlyCompleteStatus.DISCARDED
        }
    }
}
