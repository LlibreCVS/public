package by.devlab

import com.intellij.CvsBundle
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * The packaged plugin jar: OpenCVS's resources are in it, its sources are not, and the licenses travel with it
 * (design decision 16).
 */
@TestApplication
class PackagingTest {

    @Test
    @DisplayName("Resource bundles resolve at runtime")
    fun resourceBundlesResolveAtRuntime() {
        assertEquals("Commit Transaction", CvsBundle.message("operation.name.commit.changes"))
    }

    @Test
    @DisplayName("Bundles from the source trees are packaged")
    fun bundlesFromTheSourceTreesArePackaged() {
        val entries = jarEntries()

        assertTrue("messages/JavaCvsSrcBundle.properties" in entries)
        assertTrue("messages/SmartCvsSrcBundle.properties" in entries)
    }

    @Test
    @DisplayName("Jar contains no Java sources")
    fun jarContainsNoJavaSources() {
        assertEquals(emptyList<String>(), jarEntries().filter { it.endsWith(".java") })
    }

    @Test
    @DisplayName("Jar carries the notice and the licenses")
    fun jarCarriesTheNoticeAndTheLicenses() {
        val entries = jarEntries()

        for (name in listOf(
            "META-INF/NOTICE",
            "META-INF/LICENSE",
            "META-INF/sun-public-license.txt",
            "META-INF/trilead-ssh2-LICENSE.txt",
        )) {
            assertTrue(name in entries, "$name missing")
        }
    }

    /** Entry names of the plugin jar that the build passes in system property `intellijcvs.jar`. */
    private fun jarEntries(): Set<String> {
        val jar = Path.of(checkNotNull(System.getProperty("intellijcvs.jar")) { "intellijcvs.jar is not set" })
        check(Files.isRegularFile(jar)) { "no plugin jar at $jar" }
        return ZipFile(jar.toFile()).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
    }
}
