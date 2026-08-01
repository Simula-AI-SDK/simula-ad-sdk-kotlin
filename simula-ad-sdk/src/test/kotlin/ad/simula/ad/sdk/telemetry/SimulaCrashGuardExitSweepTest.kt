package ad.simula.ad.sdk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure watermark policy for the ApplicationExitInfo sweep: a transient (RETRY) failure must
 * hold the watermark so the record is retried on the next launch — never silently lost.
 */
class SimulaCrashGuardExitSweepTest {

    private data class Item(val ts: Long, val outcome: ExitSweepOutcome)

    private fun sweep(lastTs: Long, items: List<Item>): Pair<Long, List<Long>> {
        val processed = mutableListOf<Long>()
        val newTs = sweepWithWatermark(
            lastTs = lastTs,
            itemsNewestFirst = items,
            tsOf = { it.ts },
            process = { processed += it.ts; it.outcome },
        )
        return newTs to processed
    }

    @Test
    fun `watermark advances to the newest resolved record`() {
        val (newTs, processed) = sweep(
            lastTs = 100,
            items = listOf(
                Item(300, ExitSweepOutcome.RECORDED),
                Item(200, ExitSweepOutcome.SKIPPED),
                Item(150, ExitSweepOutcome.RECORDED),
            ),
        )
        assertEquals(300, newTs)
        assertEquals(listOf(300L, 200L, 150L), processed)
    }

    @Test
    fun `a transient failure holds the watermark and stops the sweep`() {
        val (newTs, processed) = sweep(
            lastTs = 100,
            items = listOf(
                Item(300, ExitSweepOutcome.RECORDED),
                Item(200, ExitSweepOutcome.RETRY),
                Item(150, ExitSweepOutcome.RECORDED), // never reached: retried next launch
            ),
        )
        assertEquals(100, newTs)
        assertEquals(listOf(300L, 200L), processed)
    }

    @Test
    fun `a failure on the newest record retries everything next launch`() {
        val (newTs, processed) = sweep(
            lastTs = 100,
            items = listOf(
                Item(300, ExitSweepOutcome.RETRY),
                Item(200, ExitSweepOutcome.RECORDED),
            ),
        )
        assertEquals(100, newTs)
        assertEquals(listOf(300L), processed)
    }

    @Test
    fun `records at or before the watermark are not reprocessed`() {
        val (newTs, processed) = sweep(
            lastTs = 200,
            items = listOf(
                Item(300, ExitSweepOutcome.RECORDED),
                Item(200, ExitSweepOutcome.RECORDED),
                Item(150, ExitSweepOutcome.RECORDED),
            ),
        )
        assertEquals(300, newTs)
        assertEquals(listOf(300L), processed)
    }

    @Test
    fun `all-skipped still advances the watermark`() {
        // SKIPPED is a permanent, resolved outcome (untracked reason / no trace / not ours).
        val (newTs, processed) = sweep(
            lastTs = 100,
            items = listOf(
                Item(300, ExitSweepOutcome.SKIPPED),
                Item(200, ExitSweepOutcome.SKIPPED),
            ),
        )
        assertEquals(300, newTs)
        assertEquals(listOf(300L, 200L), processed)
    }

    @Test
    fun `empty list keeps the watermark`() {
        val (newTs, processed) = sweep(lastTs = 100, items = emptyList())
        assertEquals(100, newTs)
        assertEquals(emptyList<Long>(), processed)
    }
}
