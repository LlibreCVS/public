package by.devlab.cvs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Collections

/**
 * Capability cvs-operations, requirement "Operations do not request services while their classes are initialized".
 *
 * Other tests of the same JVM have long initialized `CvsCommandOperation`, so its class is initialized again from the
 * plugin jar in a fresh class loader that loads the plugin's own packages itself (child first) and everything else
 * from the test's loader. The platform reports a service request from a class initializer as a warning in unit-test
 * mode (an error in the IDE); the test records both.
 */
@TestApplication
class OperationClassInitTest {

    @Test
    @DisplayName("Command operation class initializes without services")
    fun commandOperationClassInitializesWithoutServices() {
        val reports = Collections.synchronizedList(mutableListOf<String>())
        val recorder = object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                if (message.contains("Class initialization must not depend on services")) reports += message
                return super.processWarn(category, message, t)
            }

            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                if (message.contains("Class initialization must not depend on services")) {
                    reports += message
                    return Action.NONE
                }
                return super.processError(category, message, details, t)
            }
        }

        PluginFirstClassLoader(pluginJar(), javaClass.classLoader).use { loader ->
            LoggedErrorProcessor.executeWith<Throwable>(recorder) {
                try {
                    Class.forName("com.intellij.cvsSupport2.cvsoperations.common.CvsCommandOperation", true, loader)
                } catch (e: LinkageError) {
                    // A service found by name has the test loader's class, not the fresh one; only the report counts.
                    Logger.getInstance(OperationClassInitTest::class.java).info(e)
                }
            }
        }

        assertTrue(reports.isEmpty(), reports.joinToString("\n"))
    }

    /** The plugin jar that the build passes in system property `librecvs.jar`. */
    private fun pluginJar(): Path = Path.of(checkNotNull(System.getProperty("librecvs.jar")) { "librecvs.jar is not set" })

    /** Loads the plugin's CVS classes from [jar] itself and delegates everything else to [parent]. */
    private class PluginFirstClassLoader(jar: Path, parent: ClassLoader) : URLClassLoader(arrayOf(jar.toUri().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            if (!name.startsWith("com.intellij.cvsSupport2.") && !name.startsWith("org.netbeans.lib.cvsclient.")) {
                return super.loadClass(name, resolve)
            }
            val loaded = findLoadedClass(name) ?: findClass(name)
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
}
