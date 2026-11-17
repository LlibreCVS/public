package by.devlab.cvs.branches

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.TestData
import by.devlab.cvs.branches.BranchFixtures.refreshed
import by.devlab.vcs.FakeWorkingCopy
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.nio.file.Path

/**
 * Capability cvs-branch-delete (design decisions 4-15): Delete Branch… by [BranchDeleter] against the throwaway
 * server, the questions answered by [TestPrompts], the repository read with the fixture's own `cvs rlog -h`.
 */
@TestApplication
class DeleteBranchTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        const val ONE = "one.txt"
        const val TWO = "dir/two.txt"
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)
    private val prompts = TestPrompts()

    // 2.1 Skipped roots

    @Test
    @DisplayName("Root on the branch is skipped")
    fun rootOnTheBranchIsSkipped() {
        val (a, b) = roots("onbranch", listOf("BR_FIX"))
        session.updateWith(project.get(), a, CvsTestSession.UpdateOptions(branch = "BR_FIX"))
        refreshed(project.get())

        val report = deleter().delete("BR_FIX", listOf(a, b))

        println(report?.text())
        assertNull(tag(b, ONE, "BR_FIX"))
        assertNotNull(tag(a, ONE, "BR_FIX"))
        assertEquals(listOf(name(b)), report!!.switched)
        assertEquals(listOf(name(a) to "on BR_FIX"), report.skipped)
    }

    @Test
    @DisplayName("Root with files on another branch is skipped")
    fun rootWithFilesOnAnotherBranchIsSkipped() {
        val (a, _) = roots("stray", listOf("BR_FIX"))
        FakeWorkingCopy.fileEntry(a, "stray.txt", "TBR_FIX")

        val report = deleter().delete("BR_FIX", listOf(a))

        println(report?.text())
        assertEquals(emptyList<String>(), prompts.asked)
        assertNotNull(tag(a, ONE, "BR_FIX"))
        assertEquals(listOf(name(a) to "has files on another branch"), report!!.skipped)
        assertEquals(emptyList<String>(), report.switched)
    }

    @Test
    @DisplayName("Root with an unfinished merge of the branch is skipped")
    fun rootWithAnUnfinishedMergeOfTheBranchIsSkipped() {
        val (a, _) = roots("unfinished", listOf("BR_FIX"))
        MergeService.getInstance(project.get()).record(
            a, MergeService.UnfinishedMerge("BR_FIX", MergeService.TRUNK, mapOf(ONE to "1.1"), emptyMap()),
        )

        val report = deleter().delete("BR_FIX", listOf(a))

        println(report?.text())
        assertEquals(emptyList<String>(), prompts.asked)
        assertNotNull(tag(a, ONE, "BR_FIX"))
        assertEquals(listOf(name(a) to "unfinished merge of BR_FIX into TRUNK"), report!!.skipped)
    }

    // Decision 15: the other checkout of the module is on the branch, so the module must keep it.
    @Test
    @DisplayName("Root sharing a module with a protected root is skipped")
    fun rootSharingAModuleWithAProtectedRootIsSkipped() {
        val (a, b) = roots("shared", listOf("BR_FIX"))
        val copy = copyOf(a)
        session.updateWith(project.get(), copy, CvsTestSession.UpdateOptions(branch = "BR_FIX"))
        session.mapToCvs(project.get(), a, b, copy)
        refreshed(project.get())
        BranchListService.getInstance(project.get()).set(copy, listOf("BR_FIX"), System.currentTimeMillis())

        val report = deleter().delete("BR_FIX", listOf(a))

        println(report?.text())
        assertEquals(emptyList<String>(), prompts.asked)
        assertNotNull(tag(a, ONE, "BR_FIX"))
        assertEquals(listOf(name(a) to "shares its module with ${name(copy)}, which is on BR_FIX"), report!!.skipped)
    }

    private fun deleter() = BranchDeleter(project.get(), prompts)

    private fun tag(root: Path, file: String, tag: String): String? = session.tagOf("${module(root)}/$file", tag)

    /** The repository module of [root], read from its `CVS/Repository`. */
    private fun module(root: Path): String = Files.readString(root.resolve("CVS/Repository")).trim().substringAfterLast('/')

    private fun name(root: Path): String = root.fileName.toString()

    /** A second checkout of [root]'s module as alice, in a directory named `<root>-copy`. */
    private fun copyOf(root: Path): Path {
        val checkedOut = session.checkout("alice", module(root), session.newDirectory("${name(root)}-copy-wc"))
        val copy = checkedOut.resolveSibling("${name(root)}-copy")
        Files.move(checkedOut, copy)
        return copy
    }

    /** Two modules `<prefix>-a` and `<prefix>-b` with [ONE] and [TWO], [branches] on both, checked out as alice. */
    private fun roots(prefix: String, branches: List<String>): Pair<Path, Path> {
        session.useEncoding(Charsets.UTF_8)
        val parent = session.newDirectory("$prefix-wc")
        val roots = listOf("$prefix-a", "$prefix-b").map { module ->
            val seed = session.newDirectory("$module-seed")
            TestData.write(seed.resolve(ONE), "one\n".toByteArray())
            TestData.write(seed.resolve(TWO), "two\n".toByteArray())
            session.importModule("alice", seed, module)
            branches.forEach { session.branch(module, it) }
            session.checkout("alice", module, parent)
        }
        session.mapToCvs(project.get(), *roots.toTypedArray())
        refreshed(project.get())
        roots.forEach { BranchListService.getInstance(project.get()).set(it, branches, System.currentTimeMillis()) }
        return roots[0] to roots[1]
    }
}
