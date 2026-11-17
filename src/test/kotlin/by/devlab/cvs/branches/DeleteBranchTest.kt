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
        const val THREE = "newdir/three.txt"
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

    // 2.2 Analysis and the two questions

    @Test
    @DisplayName("Confirmation names unmerged files and tags")
    fun confirmationNamesUnmergedFilesAndTags() {
        val (a, b) = roots("confirm", listOf("BR_FIX"))
        addOnTrunk(a, "outside.txt")
        val bob = commit(a, "BR_FIX", ONE, "one\nfix\n")
        commit(a, "BR_FIX", TWO, "two\nfix\n", bob)
        mergedThroughPlugin(a)
        commit(a, "BR_FIX", TWO, "two\nfix again\n", bob)
        remap(a, b)

        deleter().delete("BR_FIX", listOf(a))

        println(prompts.asked.joinToString("\n"))
        assertEquals(2, prompts.asked.size, "${prompts.asked}")
        assertTrue(
            prompts.asked[0].startsWith("delete BR_FIX: unmerged {${name(a)}=1}, skipped [], failed [], " +
                "tags [BR_FIX_MERGED_TRUNK, BR_FIX_MERGING_TRUNK]"),
            prompts.asked[0],
        )
        assertTrue("cannot be undone" in prompts.asked[1], prompts.asked[1])
    }

    @Test
    @DisplayName("Fully merged branch has no unmerged files")
    fun fullyMergedBranchHasNoUnmergedFiles() {
        val (a, b) = roots("merged", listOf("BR_FIX"))
        val bob = commit(a, "BR_FIX", ONE, "one\nfix\n")
        commit(a, "BR_FIX", TWO, "two\nfix\n", bob)
        mergedThroughPlugin(a)
        remap(a, b)

        deleter().delete("BR_FIX", listOf(a))

        println(prompts.asked.joinToString("\n"))
        assertTrue(prompts.asked[0].startsWith("delete BR_FIX: unmerged {${name(a)}=0}"), prompts.asked[0])
    }

    @Test
    @DisplayName("Deletion cancelled")
    fun deletionCancelled() {
        val (a, b) = roots("cancel", listOf("BR_FIX"))
        commit(a, "BR_FIX", ONE, "one\nfix\n")
        mergedThroughPlugin(a)
        remap(a, b)
        val lists = BranchListService.getInstance(project.get())

        for ((first, second) in listOf(false to true, true to false)) {
            prompts.confirmDelete = first
            prompts.confirmDeleteIrreversible = second
            prompts.asked.clear()

            val report = deleter().delete("BR_FIX", listOf(a, b))

            println(prompts.asked.joinToString("\n"))
            assertNull(report)
            assertEquals(if (first) 2 else 1, prompts.asked.size, "${prompts.asked}")
            for (root in listOf(a, b)) {
                assertNotNull(tag(root, ONE, "BR_FIX"), "BR_FIX of ${name(root)}")
                assertTrue("BR_FIX" in lists.list(root)!!.branches, "list of ${name(root)}")
            }
            assertNotNull(tag(a, ONE, "BR_FIX_MERGED_TRUNK"))
            assertNotNull(tag(a, ONE, "BR_FIX_MERGING_TRUNK"))
        }
    }

    // Review focus: a removal on the branch is a change.
    @Test
    @DisplayName("Dead branch head counts as unmerged")
    fun deadBranchHeadCountsAsUnmerged() {
        val (a, b) = roots("dead", listOf("BR_FIX"))
        val bob = commit(a, "BR_FIX", ONE, "one\nfix\n")
        mergedThroughPlugin(a)
        session.removeAndCommit(project.get(), bob.resolve(ONE), "bob removes one.txt on BR_FIX")
        remap(a, b)

        deleter().delete("BR_FIX", listOf(a))

        println(prompts.asked.joinToString("\n"))
        assertTrue(prompts.asked[0].startsWith("delete BR_FIX: unmerged {${name(a)}=1}"), prompts.asked[0])
    }

    // Review focus, decision 14: a directory alice's trunk working copy does not have.
    @Test
    @DisplayName("Directory only on the branch is analysed")
    fun directoryOnlyOnTheBranchIsAnalysed() {
        val (a, b) = roots("branchdir", listOf("BR_FIX"))
        val bob = session.checkout("bob", module(a), session.newDirectory("${name(a)}-bob"), tag = "BR_FIX")
        session.mapToCvs(project.get(), bob)
        TestData.write(bob.resolve(THREE), "three\n".toByteArray())
        session.add(project.get(), bob.resolve("newdir"), bob.resolve(THREE))
        session.commit(project.get(), listOf(bob.resolve(THREE)), "bob adds newdir/three.txt on BR_FIX")
        // Only newdir carries the merge point: missing the directory would miss the tag.
        session.server.cvs("rtag", "-r", "BR_FIX", "BR_FIX_MERGED_TRUNK", "${module(a)}/newdir")
        commit(a, "BR_FIX", THREE, "three\nfix\n", bob)
        remap(a, b)
        assertTrue(Files.notExists(a.resolve("newdir")), "alice's trunk copy has no newdir")

        deleter().delete("BR_FIX", listOf(a))

        println(prompts.asked.joinToString("\n"))
        assertTrue(
            prompts.asked[0].startsWith("delete BR_FIX: unmerged {${name(a)}=1}, skipped [], failed [], tags [BR_FIX_MERGED_TRUNK]"),
            prompts.asked[0],
        )
    }

    /** The repository state a committed plugin merge of `BR_FIX` into the trunk leaves on [root]'s module. */
    private fun mergedThroughPlugin(root: Path) {
        session.tag(module(root), "BR_FIX_MERGING_TRUNK", "BR_FIX")
        session.tag(module(root), "BR_FIX_MERGED_TRUNK", "BR_FIX")
    }

    /** alice adds [file] to [root] on the trunk after the branches were made, so it has none of them. */
    private fun addOnTrunk(root: Path, file: String) {
        TestData.write(root.resolve(file), "$file\n".toByteArray())
        session.add(project.get(), root.resolve(file))
        session.commit(project.get(), listOf(root.resolve(file)), "alice adds $file on the trunk")
    }

    /** bob commits [file] of [root]'s module with [content] on [branch] (the trunk when null); returns his copy. */
    private fun commit(root: Path, branch: String?, file: String, content: String, copy: Path? = null): Path {
        val bob = copy ?: session.checkout("bob", module(root), session.newDirectory("${name(root)}-bob"), tag = branch)
        session.mapToCvs(project.get(), bob)
        TestData.edit(bob.resolve(file), content.toByteArray())
        session.commit(project.get(), listOf(bob.resolve(file)), "bob changes $file on ${branch ?: "the trunk"}")
        return bob
    }

    /** Maps the project to alice's [roots] only after bob's commits: his copy on the branch would protect the module. */
    private fun remap(vararg roots: Path) {
        BranchFixtures.mapToCvs(project.get(), *roots)
        refreshed(project.get())
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
