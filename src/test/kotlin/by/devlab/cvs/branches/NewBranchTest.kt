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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    @DisplayName("Existing branch used")
    fun existingBranchUsed() {
        val (a, b) = rootsWithFix("used")
        val fixBefore = session.tagOf("${a.fileName}/$ONE", "BR_FIX")
        prompts.newBranch = NewBranchRequest("BR_FIX", switch = false)
        prompts.existing = ExistingBranchChoice.USE

        val report = creator().create(listOf(a, b))

        println("${prompts.asked}\n${report?.text()}")
        assertEquals(listOf("new branch in [${a.fileName}, ${b.fileName}]", "existing BR_FIX in [${a.fileName}]"), prompts.asked)
        assertBranchExists(b, "BR_FIX")
        assertEquals(fixBefore, session.tagOf("${a.fileName}/$ONE", "BR_FIX"))
        assertSticky(a, "")
        assertSticky(b, "")
        assertEquals(listOf(b.fileName.toString()), report!!.switched)
        assertEquals(listOf(a.fileName.toString() to "already has BR_FIX"), report.skipped)
    }

    @Test
    @DisplayName("Existing branch used, with the switch option on")
    fun existingBranchUsedSwitching() {
        val (a, b) = rootsWithFix("usedswitch")
        prompts.newBranch = NewBranchRequest("BR_FIX", switch = true)
        prompts.existing = ExistingBranchChoice.USE

        val report = creator().create(listOf(a, b))

        println("${prompts.asked}\n${report?.text()}")
        assertSticky(a, "BR_FIX")
        assertSticky(b, "BR_FIX")
    }

    @Test
    @DisplayName("Existing branch recreated")
    fun existingBranchRecreated() {
        val (a, b) = rootsWithFix("recreated")
        prompts.newBranch = NewBranchRequest("BR_FIX", switch = false)
        prompts.existing = ExistingBranchChoice.RECREATE
        prompts.confirmRecreate = true

        val report = creator().create(listOf(a, b))

        println("${prompts.asked}\n${report?.text()}")
        assertTrue(prompts.asked.any { it.startsWith("recreate BR_FIX: ") && it.contains("lose the branch name") }, "${prompts.asked}")
        val fix = session.checkout("bob", a.fileName.toString(), session.newDirectory("recreated-fix"), tag = "BR_FIX")
        assertEquals("one on the trunk\n", TestData.text(fix.resolve(ONE), Charsets.UTF_8))
        assertBranchExists(a, "BR_FIX")
        assertBranchExists(b, "BR_FIX")
    }

    @Test
    @DisplayName("Existing branch cancelled")
    fun existingBranchCancelled() {
        for ((prefix, choice) in listOf("cancelfirst" to ExistingBranchChoice.CANCEL, "cancelsecond" to ExistingBranchChoice.RECREATE)) {
            val (a, b) = rootsWithFix(prefix)
            val fixBefore = session.tagOf("${a.fileName}/$ONE", "BR_FIX")
            prompts.asked.clear()
            prompts.newBranch = NewBranchRequest("BR_FIX", switch = true)
            prompts.existing = choice
            prompts.confirmRecreate = false

            val report = creator().create(listOf(a, b))

            println("$prefix: ${prompts.asked}")
            assertEquals(if (choice == ExistingBranchChoice.CANCEL) 2 else 3, prompts.asked.size, "${prompts.asked}")
            assertNull(report, prefix)
            assertEquals(fixBefore, session.tagOf("${a.fileName}/$ONE", "BR_FIX"), prefix)
            assertNull(session.tagOf("${b.fileName}/$ONE", "BR_FIX"), prefix)
            assertSticky(a, "")
            assertSticky(b, "")
        }
    }

    // Review focus: a stale list makes CVS refuse the existing branch; the root fails and the branch is not moved.
    @Test
    @DisplayName("Stale list reports the root as failed")
    fun staleListReportsTheRootAsFailed() {
        val (_, b) = roots("stale")
        session.branch(b.fileName.toString(), "BR_FIX")
        val fixBefore = session.tagOf("${b.fileName}/$ONE", "BR_FIX")
        prompts.newBranch = NewBranchRequest("BR_FIX", switch = false)

        val report = creator().create(listOf(b))

        println("${prompts.asked}\n${report?.text()}")
        assertEquals(listOf(b.fileName.toString()), report!!.failed.map { it.first })
        assertTrue(report.switched.isEmpty(), "${report.switched}")
        assertEquals(fixBefore, session.tagOf("${b.fileName}/$ONE", "BR_FIX"))
    }

    // Use Existing with the switch option switches roots to an existing branch, so a partial one is asked too (decision 20).
    @Test
    @DisplayName("Partial root skipped, existing branch used")
    fun partialRootSkippedExistingBranchUsed() {
        val (a, b) = roots("partialused")
        session.branch("${a.fileName}/dir", "BR_SUB")
        val lists = BranchListService.getInstance(project.get())
        lists.load(listOf(a, b))
        lists.awaitLoad()
        refreshed(project.get())
        val filesBefore = session.workingFiles(a)
        prompts.newBranch = NewBranchRequest("BR_SUB", switch = true)
        prompts.existing = ExistingBranchChoice.USE
        prompts.partial = PartialBranchChoice.SKIP

        val report = creator().create(listOf(a, b))

        println("${prompts.asked}\n${report?.text()}")
        assertTrue("partial BR_SUB: {${a.fileName}=1 of 2}" in prompts.asked, "${prompts.asked}")
        assertEquals(filesBefore, session.workingFiles(a))
        assertSticky(a, "")
        assertSticky(b, "BR_SUB")
        assertEquals(listOf(a.fileName.toString() to "branch covers only part of the root"), report!!.skipped)
    }

    // A recreated branch covers the whole root: its old partial counts are dropped (design decision 24).
    @Test
    @DisplayName("Recreated branch covers the root")
    fun recreatedBranchCoversTheRoot() {
        val (a, _) = roots("recreatecounts")
        session.branch("${a.fileName}/dir", "BR_SUB")
        val lists = BranchListService.getInstance(project.get())
        lists.load(listOf(a))
        lists.awaitLoad()
        refreshed(project.get())
        assertNotNull(lists.list(a)!!.partial("BR_SUB"), "${lists.list(a)}")
        prompts.newBranch = NewBranchRequest("BR_SUB", switch = false)
        prompts.existing = ExistingBranchChoice.RECREATE
        prompts.confirmRecreate = true

        creator().create(listOf(a))
        prompts.asked.clear()
        BranchSwitcher(project.get(), prompts).switch(SwitchTarget.Branch("BR_SUB"), listOf(a))

        println("${lists.list(a)}\n${prompts.asked}")
        assertEquals(emptyList<String>(), prompts.asked)
        assertSticky(a, "BR_SUB")
        assertEquals(2, session.workingFiles(a).size, "no file left the working copy")
    }

    private fun creator() = BranchCreator(project.get(), prompts)

    /**
     * [roots] where `BR_FIX` is branched on `<prefix>-a` at revision 1.1 of [ONE], which is 1.2 in alice's working copy
     * (`one on the trunk`); only `<prefix>-a`'s list has `BR_FIX`.
     */
    private fun rootsWithFix(prefix: String): Pair<Path, Path> {
        val (a, b) = roots(prefix)
        session.branch(a.fileName.toString(), "BR_FIX")
        TestData.edit(a.resolve(ONE), "one on the trunk\n".toByteArray())
        session.commit(project.get(), listOf(a.resolve(ONE)), "alice changes one on the trunk")
        assertEquals("1.2", session.revision(a.resolve(ONE)))
        BranchListService.getInstance(project.get()).set(a, listOf("BR_FIX", "BR_RELEASE"), LOADED_AT)
        refreshed(project.get())
        return a to b
    }

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
