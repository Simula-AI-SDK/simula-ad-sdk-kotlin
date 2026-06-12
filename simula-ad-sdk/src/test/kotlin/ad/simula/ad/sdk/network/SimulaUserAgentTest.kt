package ad.simula.ad.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the User-Agent wire format (User-Agent for Apps SDK PRD) against the AdMob-aligned
 * template. Exercises the pure [SimulaUserAgent.compose] so no Android `Build` statics are needed.
 */
class SimulaUserAgentTest {

    @Test
    fun `compose matches the PRD format`() {
        val ua = SimulaUserAgent.compose(
            sdkVersion = "1.2.3",
            osVersion = "13",
            locale = "en_US",
            deviceModel = "SM-G990B",
            buildId = "TP1A.220624.014",
            bundleId = "com.publisher.app",
        )
        assertEquals(
            "Simula-SDK/1.2.3 (Android 13; en_US; SM-G990B; Build/TP1A.220624.014; com.publisher.app)",
            ua,
        )
    }
}
