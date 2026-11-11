package by.devlab

import com.intellij.cvsSupport2.config.CvsApplicationLevelConfiguration
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Guard: no test may read the developer's saved CVS credentials. Upstream resolves the
 * password file against `user.home`, which the build points into `build/` for the test JVM (design decision 19).
 */
@TestApplication
class TestIsolationTest {

    @Test
    @DisplayName("Tests never resolve the real password file")
    fun testsNeverResolveTheRealPasswordFile() {
        val realHome = System.getenv("USERPROFILE") ?: System.getenv("HOME")
        assertNotNull(realHome, "no USERPROFILE or HOME in the environment")
        val passFile = CvsApplicationLevelConfiguration.getInstance().passFile.toPath().toAbsolutePath().normalize()

        val realPassFile = Path.of(realHome!!, ".cvspass").toAbsolutePath().normalize()

        assertFalse(passFile == realPassFile, "$passFile")
    }
}
