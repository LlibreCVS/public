package by.devlab.cvs.branches

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.TestData
import by.devlab.cvs.branches.BranchFixtures.refreshed
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path

/**
 * Capability cvs-branch-popup, requirements "Creating a branch over the roots" and "An existing branch name is decided
 * by the user" (design decisions 5, 10, 11, 14): [BranchCreator] over two modules checked out from the throwaway server.
 */
@TestApplication
class NewBranchTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        const val ONE = "one.txt"
        const val TWO = "dir/two.txt"
        const val LOADED_AT = 1_700_000_000_000L
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)
    private val prompts = TestPrompts()

    @Test
    @DisplayName("New branch in all roots")
    fun newBranchInAllRoots() {
        val (a, b) = roots("all")
        prompts.newBranch = NewBranchRequest("BR_NEW", switch = true)

        val report = creator().create(listOf(a, b))

        println(report?.text())
        assertBranchExists(a, "BR_NEW")
        assertBranchExists(b, "BR_NEW")
        assertSticky(a, "BR_NEW")
        assertSticky(b, "BR_NEW")
        assertRemembered(a, b, "BR_NEW")
        assertEquals(listOf(a.fileName.toString(), b.fileName.toString()), report!!.switched)
    }

    @Test
    @DisplayName("New branch without switching")
    fun newBranchWithoutSwitching() {
        val (a, b) = roots("noswitch")
        prompts.newBranch = NewBranchRequest("BR_NEW", switch = false)

        val report = creator().create(listOf(a, b))

        println(report?.text())
        assertBranchExists(a, "BR_NEW")
        assertBranchExists(b, "BR_NEW")
        assertSticky(a, "")
        assertSticky(b, "")
        assertRemembered(a, b, "BR_NEW")
    }

    private fun creator() = BranchCreator(project.get(), prompts)

    /**
     * Two modules `<prefix>-a` and `<prefix>-b` with [ONE] and [TWO], checked out side by side as alice, mapped to CVS,
     * with both lists remembered as `[BR_RELEASE]` loaded at [LOADED_AT].
     */
    private fun roots(prefix: String): Pair<Path, Path> {
        val parent = session.newDirectory("$prefix-wc")
        val roots = listOf("$prefix-a", "$prefix-b").map { module ->
            val seed = session.newDirectory("$module-seed")
            TestData.write(seed.resolve(ONE), "one\n".toByteArray())
            TestData.write(seed.resolve(TWO), "two\n".toByteArray())
            session.importModule("alice", seed, module)
            session.checkout("alice", module, parent)
        }
        session.mapToCvs(project.get(), *roots.toTypedArray())
        refreshed(project.get())
        roots.forEach { BranchListService.getInstance(project.get()).set(it, listOf("BR_RELEASE"), LOADED_AT) }
        return roots[0] to roots[1]
    }

    private fun assertBranchExists(root: Path, branch: String) {
        for (file in listOf(ONE, TWO)) {
            val revision = session.tagOf("${root.fileName}/$file", branch)
            assertNotNull(revision, "$branch on ${root.fileName}/$file")
            assertEquals("0", revision!!.split('.').dropLast(1).last(), "$branch on ${root.fileName}/$file is $revision")
        }
    }

    /** Both lists hold [branch] next to `BR_RELEASE`, with the load time untouched: no `log` was needed. */
    private fun assertRemembered(a: Path, b: Path, branch: String) {
        for (root in listOf(a, b)) {
            val list = checkNotNull(BranchListService.getInstance(project.get()).list(root))
            assertEquals(listOf(branch, "BR_RELEASE").sorted(), list.branches)
            assertEquals(LOADED_AT, list.loadedAt)
        }
    }

    private fun assertSticky(root: Path, tag: String) {
        val files = session.workingFiles(root)
        assertFalse(files.isEmpty())
        for (file in files) assertEquals(tag, session.stickyTag(file), "sticky tag of $file")
    }
}
