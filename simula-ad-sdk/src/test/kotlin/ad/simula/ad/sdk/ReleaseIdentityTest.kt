package ad.simula.ad.sdk

import ad.simula.ad.sdk.telemetry.SIMULA_SDK_VERSION
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseIdentityTest {
    @Test
    fun `release identity matches PR version`() {
        assertEquals("1.2.1-dev.4", SimulaAdSdkInfo.VERSION)
        assertEquals(SimulaAdSdkInfo.VERSION, SIMULA_SDK_VERSION)
        val buildFile = listOf(
            File("build.gradle.kts"),
            File("simula-ad-sdk/build.gradle.kts"),
        ).firstOrNull { it.isFile && it.readText().contains("val sdkVersion") }
            ?: error("SDK build.gradle.kts not found")
        val gradleVersion = Regex("sdkVersion\\s*=\\s*\"([^\"]+)\"")
            .find(buildFile.readText())?.groupValues?.get(1)
        assertEquals(SimulaAdSdkInfo.VERSION, gradleVersion)
    }
}
