package by.devlab.cvs.branches

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.TestData
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path
import kotlin.io.path.relativeTo

/**
 * Capability cvs-branches, requirement "Create a branch from a working copy" (design decisions 8, 15, 17, 20):
 * the branch is created through the Create Branch handler with the parameters its dialog sets on OK.
 */
@TestApplication
class CreateBranchTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("Branch starts at the working copy revisions")
    fun branchStartsAtTheWorkingCopyRevisions() {
        val module = "branch-root"
        val a = workingCopy(module)
        // A holds 1.2 of один.txt and the imported revision of Папка/два.txt.
        TestData.edit(a.resolve("один.txt"), "один, правка alice\n".toByteArray())
        session.commit(project.get(), listOf(a.resolve("один.txt")), "alice меняет один")
        // Another working copy moves the head of один.txt to 1.3; A is not updated (design decision 20).
        val b = session.checkout("bob", module, session.newDirectory("$module-b"))
        session.mapToCvs(project.get(), b)
        TestData.edit(b.resolve("один.txt"), "один, правка bob\n".toByteArray())
        session.commit(project.get(), listOf(b.resolve("один.txt")), "bob меняет один")
        assertEquals("1.3", session.revision(b.resolve("один.txt")))

        session.createBranch(project.get(), a, "BR_ROOT", switch = false)

        val files = session.workingFiles(a)
        assertEquals(2, files.size, "$files")
        for (file in files) {
            val path = "$module/${file.relativeTo(a).toString().replace('\\', '/')}"
            val branch = session.tagOf(path, "BR_ROOT")
            assertNotNull(branch, "BR_ROOT on $path")
            assertEquals(session.revision(file), branchRoot(branch!!), "root of BR_ROOT ($branch) on $path")
        }
        assertEquals("1.2", session.revision(a.resolve("один.txt")))
    }

    @Test
    @DisplayName("Create branch and switch to it")
    fun createBranchAndSwitchToIt() {
        val a = workingCopy("branch-switch")

        session.createBranch(project.get(), a, "BR_SWITCH", switch = true)

        val files = session.workingFiles(a)
        assertEquals(2, files.size, "$files")
        for (file in files) assertEquals("BR_SWITCH", session.stickyTag(file), "$file")
    }

    @Test
    @DisplayName("Create branch without switching")
    fun createBranchWithoutSwitching() {
        val module = "branch-stay"
        val a = workingCopy(module)

        session.createBranch(project.get(), a, "BR_STAY", switch = false)

        val files = session.workingFiles(a)
        assertEquals(2, files.size, "$files")
        for (file in files) assertEquals("", session.stickyTag(file), "$file")
        // The branch exists all the same.
        assertNotNull(session.tagOf("$module/один.txt", "BR_STAY"))
    }

    /** A branch number `<root>.0.<n>` without its last two parts (design decision 15). */
    private fun branchRoot(branch: String): String = branch.split('.').dropLast(2).joinToString(".")

    /** Imports a module with a file at the top and one in a Cyrillic subdirectory, checks it out as alice, maps it. */
    private fun workingCopy(module: String): Path {
        session.useEncoding(Charsets.UTF_8)
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve("один.txt"), "один\n".toByteArray())
        TestData.write(seed.resolve("Папка/два.txt"), "два\n".toByteArray())
        session.importModule("alice", seed, module)
        val a = session.checkout("alice", module, session.newDirectory("$module-a"))
        session.mapToCvs(project.get(), a)
        return a
    }
}
