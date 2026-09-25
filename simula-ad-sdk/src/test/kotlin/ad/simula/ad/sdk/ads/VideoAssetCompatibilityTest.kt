package ad.simula.ad.sdk.ads

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAssetCompatibilityTest {
    @Test
    fun `video cache source has no API 26 NIO linkage`() {
        val sourceFile = listOf(
            File("src/main/kotlin/ad/simula/ad/sdk/ads/VideoAssetCache.kt"),
            File("simula-ad-sdk/src/main/kotlin/ad/simula/ad/sdk/ads/VideoAssetCache.kt"),
        ).firstOrNull(File::isFile) ?: error("VideoAssetCache.kt not found")
        val source = sourceFile.readText()

        assertFalse(source.contains("java.nio.file"))
        assertFalse(source.contains("Files.move"))
        assertFalse(source.contains("Files.newDirectoryStream"))
        assertFalse(source.contains("DirectoryStream"))
        assertFalse(source.contains("StandardCopyOption"))
        assertFalse(source.contains(".listFiles("))
        assertFalse(source.contains("directory.list("))
        assertFalse(source.contains("System.loadLibrary"))
        assertFalse(source.contains(" external fun "))
        assertTrue(source.contains("MAX_MANIFEST_BYTES"))
        assertTrue(source.contains("MAX_CACHE_ENTRIES = 256"))
        assertTrue(source.contains("renameTo"))
        assertTrue(source.contains("output.fd.sync()"))

        val buildFile = listOf(File("build.gradle.kts"), File("simula-ad-sdk/build.gradle.kts"))
            .firstOrNull(File::isFile) ?: error("build.gradle.kts not found")
        assertFalse(buildFile.readText().contains("externalNativeBuild"))
        assertFalse(File(buildFile.parentFile, "src/main/cpp").exists())
    }
}
