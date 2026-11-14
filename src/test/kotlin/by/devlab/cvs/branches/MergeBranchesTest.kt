package by.devlab.cvs.branches

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.CvsTestSession.UpdateOptions
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
 * Capability cvs-branch-merge (design decisions 3, 4, 5, 11, 13, 14, 16): [BranchMerger] over modules checked out
 * from the throwaway server as alice; bob commits on the branches and the trunk from his own working copies.
 */
@TestApplication
class MergeBranchesTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        const val ONE = "one.txt"
        const val TWO = "dir/two.txt"
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)
    private val prompts = TestPrompts()

    // Review focus: the merge never moves a branch or another tag.
    @Test
    @DisplayName("Merge a branch into the trunk")
    fun mergeABranchIntoTheTrunk() {
        val (a, b) = roots("into-trunk", listOf("BR_FIX"))
        session.tag(module(a), "RELEASE_1", "HEAD")
        val bobA = commit(a, "BR_FIX", ONE, "one\nfix\n")
        commit(b, "BR_FIX", ONE, "one\nfix\n")
        val branchNumber = session.tagOf("${module(a)}/$ONE", "BR_FIX")
        val release = session.tagOf("${module(a)}/$ONE", "RELEASE_1")

        val report = merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a, b))

        println(report?.text())
        for (root in listOf(a, b)) {
            assertEquals("one\nfix\n", text(root, ONE))
            assertSticky(root, "")
        }
        assertEquals(listOf(module(a), module(b)), report!!.switched)
        assertEquals("Merged", report.switchedLabel)
        assertTrue(report.conflicts.isEmpty() && report.failed.isEmpty(), report.text())
        assertEquals(session.revision(bobA.resolve(ONE)), session.tagOf("${module(a)}/$ONE", "BR_FIX_MERGING_TRUNK"))
        assertEquals(branchNumber, session.tagOf("${module(a)}/$ONE", "BR_FIX"), "the branch tag must not move")
        assertEquals(release, session.tagOf("${module(a)}/$ONE", "RELEASE_1"), "a plain tag must not move")
        assertNull(session.tagOf("${module(a)}/$ONE", "BR_FIX_MERGED_TRUNK"), "nothing is committed, the point stays")
        // Nothing reaches the trunk on the server before a commit.
        assertEquals("one\n", text(session.checkout("bob", module(a), session.newDirectory("${module(a)}-check")), ONE))
        // Decisions 13 and 14: the files changed with their revisions after the merge, the snapshot per file.
        val merge = checkNotNull(MergeService.getInstance(project.get()).unfinished(a)) { "no unfinished merge recorded" }
        assertEquals("BR_FIX" to "TRUNK", merge.source to merge.target)
        assertEquals(mapOf(ONE to session.revision(a.resolve(ONE))), merge.files)
        assertEquals(mapOf(ONE to session.revision(bobA.resolve(ONE)), TWO to session.revision(a.resolve(TWO))), merge.snapshot)
    }

    @Test
    @DisplayName("Merge the trunk into a branch")
    fun mergeTheTrunkIntoABranch() {
        val (a, _) = roots("trunk-into", listOf("BR_FIX"))
        session.updateWith(project.get(), a, UpdateOptions(branch = "BR_FIX"))
        refreshed(project.get())
        val bob = commit(a, null, TWO, "two\ntrunk\n")

        val report = merger().merge(SwitchTarget.Trunk, listOf(a))

        println(report?.text())
        assertEquals("two\ntrunk\n", text(a, TWO))
        assertSticky(a, "BR_FIX")
        assertEquals(listOf(module(a)), report!!.switched)
        assertEquals(session.revision(bob.resolve(TWO)), session.tagOf("${module(a)}/$TWO", "TRUNK_MERGING_BR_FIX"))
        // Nothing reaches the branch on the server before a commit.
        assertEquals("two\n", text(session.checkout("bob", module(a), session.newDirectory("${module(a)}-check"), tag = "BR_FIX"), TWO))
    }

    @Test
    @DisplayName("Merge-point tag names")
    fun mergePointTagNames() {
        val (a, b) = roots("names", listOf("BR_FIX"))
        session.updateWith(project.get(), b, UpdateOptions(branch = "BR_FIX"))
        refreshed(project.get())
        val onBranch = commit(a, "BR_FIX", ONE, "one\nfix\n")
        val onTrunk = commit(b, null, TWO, "two\ntrunk\n")

        merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a))
        merger().merge(SwitchTarget.Trunk, listOf(b))

        assertEquals(session.revision(onBranch.resolve(ONE)), session.tagOf("${module(a)}/$ONE", "BR_FIX_MERGING_TRUNK"))
        assertEquals(session.revision(onTrunk.resolve(TWO)), session.tagOf("${module(b)}/$TWO", "TRUNK_MERGING_BR_FIX"))
    }

    // Review focus: without BR_FIX_MERGED_TRUNK the merge uses one -j and reports no conflict.
    @Test
    @DisplayName("First merge brings every change")
    fun firstMergeBringsEveryChange() {
        val (a, _) = roots("first", listOf("BR_FIX"))
        val bob = commit(a, "BR_FIX", ONE, "one\nfirst\n")
        commit(a, "BR_FIX", TWO, "two\nsecond\n", bob)

        val report = merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a))

        println(report?.text())
        assertEquals("one\nfirst\n", text(a, ONE))
        assertEquals("two\nsecond\n", text(a, TWO))
        assertEquals(emptyList<String>(), report!!.conflicts)
        assertEquals(listOf(module(a)), report.switched)
    }

    @Test
    @DisplayName("Merge one root")
    fun mergeOneRoot() {
        val (a, b) = roots("one", listOf("BR_FIX"))
        commit(a, "BR_FIX", ONE, "one\nfix\n")
        commit(b, "BR_FIX", ONE, "one\nfix\n")

        merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a))

        assertEquals("one\nfix\n", text(a, ONE))
        assertEquals("one\n", text(b, ONE))
        assertNull(MergeService.getInstance(project.get()).unfinished(b))
    }

    @Test
    @DisplayName("Failure in one root")
    fun failureInOneRoot() {
        val (a, b) = roots("failure", listOf("BR_FIX"))
        commit(a, "BR_FIX", ONE, "one\nfix\n")
        commit(b, "BR_FIX", ONE, "one\nfix\n")
        BranchFixtures.unreachable(a)

        val report = merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a, b))

        println(report?.text())
        assertEquals("one\nfix\n", text(b, ONE))
        assertEquals(listOf(module(b)), report!!.switched)
        assertEquals(listOf(module(a)), report.failed.map { it.first })
        assertEquals("one\n", text(a, ONE))
    }

    @Test
    @DisplayName("Root without the source is skipped")
    fun rootWithoutTheSourceIsSkipped() {
        val (a, b) = roots("lacks", emptyList())
        session.branch(module(a), "BR_FIX")
        BranchListService.getInstance(project.get()).set(a, listOf("BR_FIX"), System.currentTimeMillis())
        commit(a, "BR_FIX", ONE, "one\nfix\n")
        val before = contents(b)

        val report = merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a, b))

        println(report?.text())
        assertEquals("one\nfix\n", text(a, ONE))
        assertEquals(before, contents(b))
        assertEquals(listOf(module(a)), report!!.switched)
        assertEquals(listOf(module(b) to "lacks the branch BR_FIX"), report.skipped)
    }

    @Test
    @DisplayName("Root with an unfinished merge is skipped")
    fun rootWithAnUnfinishedMergeIsSkipped() {
        val (a, _) = roots("unfinished", listOf("BR_FIX", "BR_RELEASE"))
        commit(a, "BR_FIX", ONE, "one\nfix\n")
        commit(a, "BR_RELEASE", TWO, "two\nrelease\n")
        merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a))
        assertNotNull(MergeService.getInstance(project.get()).unfinished(a))
        val before = contents(a)

        val report = merger().merge(SwitchTarget.Branch("BR_RELEASE"), listOf(a))

        println(report?.text())
        assertEquals(before, contents(a))
        assertEquals(listOf(module(a) to "unfinished merge of BR_FIX"), report!!.skipped)
        assertNull(session.tagOf("${module(a)}/$TWO", "BR_RELEASE_MERGING_TRUNK"), "a skipped root gets no snapshot")
    }

    @Test
    @DisplayName("Root on a tag is skipped")
    fun rootOnATagIsSkipped() {
        val (a, _) = roots("on-tag", listOf("BR_FIX"))
        session.tag(module(a), "RELEASE_1", "HEAD")
        commit(a, "BR_FIX", ONE, "one\nfix\n")
        session.updateWith(project.get(), a, UpdateOptions(branch = "RELEASE_1"))
        refreshed(project.get())
        val before = contents(a)

        val report = merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a))

        println(report?.text())
        assertEquals(before, contents(a))
        assertEquals(listOf(module(a) to "not on the trunk or a branch"), report!!.skipped)
        assertTrue(report.switched.isEmpty())
    }

    @Test
    @DisplayName("Report of a merge")
    fun reportOfAMerge() {
        val (a, b) = roots("report", emptyList())
        session.branch(module(a), "BR_FIX")
        BranchListService.getInstance(project.get()).set(a, listOf("BR_FIX"), System.currentTimeMillis())
        commit(a, "BR_FIX", ONE, "one on the branch\n")
        commit(a, null, ONE, "one on the trunk\n")

        val (report, notification) = BranchFixtures.notified(project.get()) {
            merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a, b))
        }

        val text = "${notification.title}\n${notification.content}"
        println(text)
        assertEquals(listOf(module(a)), report!!.switched)
        assertEquals(listOf(module(b) to "lacks the branch BR_FIX"), report.skipped)
        assertEquals(listOf("${module(a)}/$ONE"), report.conflicts)
        assertTrue(text.contains("Merged: ${module(a)}"), text)
        assertTrue(text.contains("${module(b)}: lacks the branch BR_FIX"), text)
        assertTrue(text.contains("Conflicts: ${module(a)}/$ONE"), text)
    }

    @Test
    @DisplayName("Keyword lines do not conflict")
    fun keywordLinesDoNotConflict() {
        val (a, _) = roots("keywords", listOf("BR_FIX"), one = "\$Revision\$\nline 1\nline 2\nline 3\n")
        commit(a, "BR_FIX", ONE, "\$Revision\$\nline 1\nline 2\nline 3 on the branch\n")
        commit(a, null, ONE, "\$Revision\$\nline 1 on the trunk\nline 2\nline 3\n")

        val report = merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a))

        println(report?.text())
        val lines = text(a, ONE).lines()
        println(lines)
        assertEquals(emptyList<String>(), report!!.conflicts)
        assertTrue("line 1 on the trunk" in lines && "line 3 on the branch" in lines, "$lines")
        assertFalse(lines.any { it.startsWith("<<<<<<<") }, "$lines")
    }

    private fun merger() = BranchMerger(project.get(), prompts)

    private fun module(root: Path): String = root.fileName.toString()

    private fun text(root: Path, file: String): String = TestData.text(root.resolve(file), Charsets.UTF_8)

    /** Every working file of [root] with its content, below the root. */
    private fun contents(root: Path): Map<String, String> =
        session.workingFiles(root).associate { root.relativize(it).toString().replace('\\', '/') to TestData.text(it, Charsets.UTF_8) }

    /**
     * Two modules `<prefix>-a` and `<prefix>-b` with [ONE] (holding [one]) and [TWO], [branches] made on both, checked
     * out side by side as alice, mapped to CVS, with both lists remembered as [branches].
     */
    private fun roots(prefix: String, branches: List<String>, one: String = "one\n"): Pair<Path, Path> {
        session.useEncoding(Charsets.UTF_8)
        val parent = session.newDirectory("$prefix-wc")
        val roots = listOf("$prefix-a", "$prefix-b").map { module ->
            val seed = session.newDirectory("$module-seed")
            TestData.write(seed.resolve(ONE), one.toByteArray())
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

    /**
     * bob commits [file] of [root]'s module with [content] on [branch] (the trunk when null), from [copy] or a new
     * working copy of his; returns the working copy.
     */
    private fun commit(root: Path, branch: String?, file: String, content: String, copy: Path? = null): Path {
        val bob = copy ?: session.checkout("bob", module(root), session.newDirectory("${module(root)}-bob"), tag = branch)
        session.mapToCvs(project.get(), bob)
        TestData.edit(bob.resolve(file), content.toByteArray())
        session.commit(project.get(), listOf(bob.resolve(file)), "bob changes $file on ${branch ?: "the trunk"}")
        return bob
    }

    private fun assertSticky(root: Path, tag: String) {
        val files = session.workingFiles(root)
        assertFalse(files.isEmpty())
        for (file in files) assertEquals(tag, session.stickyTag(file), "sticky tag of $file")
    }
}
