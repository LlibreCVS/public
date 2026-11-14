package by.devlab.cvs

import com.intellij.cvsSupport2.CvsVcs2
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.extension.RegisterExtension
import org.netbeans.lib.cvsclient.connection.PServerPasswordScrambler
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Not a test: the throwaway CVS server for the manual `runIde` check of the branch popup (cvs-branch-popup task 5.1).
 * Runs only with `INTELLIJCVS_MANUAL_SERVER=true`: imports `module-a` (branches `BR_RELEASE`, `BR_FIX`, plain tag
 * `RELEASE_1`) and `module-b` (branch `BR_RELEASE`), checks both out as alice into `build/manual/popup`, writes the
 * project's CVS mappings and a password file there, prints the CVSROOT and keeps serving until
 * `build/manual/popup/STOP` exists (at most four hours). Never points at a real server.
 */
@TestApplication
@EnabledIfEnvironmentVariable(named = "INTELLIJCVS_MANUAL_SERVER", matches = "true")
class ManualPopupServer {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()
    }

    @Test
    fun serve() {
        val target = Path.of("build/manual/popup").toAbsolutePath().normalize()
        check(!Files.exists(target)) { "$target exists; delete it first" }
        Files.createDirectories(target)
        session.useEncoding(Charsets.UTF_8)
        for (module in listOf("module-a", "module-b")) {
            val seed = session.newDirectory("$module-seed")
            TestData.write(seed.resolve("Main.txt"), "main of $module\n".toByteArray())
            TestData.write(seed.resolve("src/App.txt"), "app of $module\n".toByteArray())
            session.importModule("alice", seed, module)
            session.branch(module, "BR_RELEASE")
        }
        session.branch("module-a", "BR_FIX")
        session.tag("module-a", "RELEASE_1", "1.1")
        listOf("module-a", "module-b").forEach { session.checkout("alice", it, target) }

        val root = session.server.cvsRoot("alice")
        val scrambled = PServerPasswordScrambler.getInstance().scramble(TestCvsServer.PASSWORD)
        Files.writeString(target.resolve("cvspass"), "$root $scrambled\n")
        val vcs = CvsVcs2.getKey().name
        Files.createDirectories(target.resolve(".idea"))
        Files.writeString(
            target.resolve(".idea/vcs.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="VcsDirectoryMappings">
                <mapping directory="${'$'}PROJECT_DIR${'$'}/module-a" vcs="$vcs" />
                <mapping directory="${'$'}PROJECT_DIR${'$'}/module-b" vcs="$vcs" />
              </component>
            </project>
            """.trimIndent() + "\n",
        )
        println("MANUAL CVSROOT $root")
        println("MANUAL PROJECT $target")
        println("MANUAL PASSWORD FILE ${target.resolve("cvspass")}")
        System.out.flush()

        val stop = target.resolve("STOP")
        val deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(4)
        while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(1000)
    }
}
