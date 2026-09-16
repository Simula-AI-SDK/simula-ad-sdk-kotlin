package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.Experiment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RewardedExperimentAssignmentTest {
    @Test
    fun `no-fill attempt cannot inherit previous assignment`() {
        val assignment = Assignment("stale_experiment", "stale_variant")

        clearLoadExperimentAssignment(assignment::apply)

        assertNull(assignment.experimentId)
        assertNull(assignment.variantId)
    }

    @Test
    fun `thrown load failure cannot inherit previous assignment`() {
        val assignment = Assignment("stale_experiment", "stale_variant")

        clearLoadExperimentAssignment(assignment::apply)
        runCatching { throw IllegalStateException("transport failed") }

        assertNull(assignment.experimentId)
        assertNull(assignment.variantId)
    }

    @Test
    fun `successful response applies new assignment after attempt clear`() {
        val assignment = Assignment("stale_experiment", "stale_variant")

        clearLoadExperimentAssignment(assignment::apply)
        applyLoadExperimentAssignment(
            Experiment(
                experimentId = "rewarded_video_q3",
                variantId = "video_b",
                layer = "creative_media",
            ),
            assignment::apply,
        )

        assertEquals("rewarded_video_q3", assignment.experimentId)
        assertEquals("video_b", assignment.variantId)
    }

    @Test
    fun `successful response without assignment remains clear`() {
        val assignment = Assignment("stale_experiment", "stale_variant")

        clearLoadExperimentAssignment(assignment::apply)
        applyLoadExperimentAssignment(null, assignment::apply)

        assertNull(assignment.experimentId)
        assertNull(assignment.variantId)
    }

    private class Assignment(
        var experimentId: String? = "stale_experiment",
        var variantId: String? = "stale_variant",
    ) {
        fun apply(nextExperimentId: String?, nextVariantId: String?) {
            experimentId = nextExperimentId
            variantId = nextVariantId
        }
    }
}
