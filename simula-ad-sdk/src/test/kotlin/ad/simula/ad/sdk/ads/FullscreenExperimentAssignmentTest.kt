package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.model.Experiment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullscreenExperimentAssignmentTest {
    @Test
    fun `accepted load applies experiment before load success`() {
        val assignment = Assignment("stale", "stale")
        val calls = mutableListOf<String>()

        recordAcceptedLoadTelemetry(
            experiment = Experiment("experiment", "variant", "layer"),
            applyExperiment = { experimentId, variantId ->
                assignment.apply(experimentId, variantId)
                calls += "experiment"
            },
            recordLoadSuccess = { calls += "load_success" },
        )

        assertEquals(listOf("experiment", "load_success"), calls)
        assertEquals("experiment", assignment.experimentId)
        assertEquals("variant", assignment.variantId)
    }

    @Test
    fun `accepted load without experiment clears stale assignment before load success`() {
        val assignment = Assignment("stale_experiment", "stale_variant")
        val observedAtLoadSuccess = mutableListOf<Pair<String?, String?>>()

        recordAcceptedLoadTelemetry(
            experiment = null,
            applyExperiment = assignment::apply,
            recordLoadSuccess = {
                observedAtLoadSuccess += assignment.experimentId to assignment.variantId
            },
        )

        assertNull(assignment.experimentId)
        assertNull(assignment.variantId)
        assertEquals(listOf(null to null), observedAtLoadSuccess)
    }

    private class Assignment(
        var experimentId: String?,
        var variantId: String?,
    ) {
        fun apply(nextExperimentId: String?, nextVariantId: String?) {
            experimentId = nextExperimentId
            variantId = nextVariantId
        }
    }
}
