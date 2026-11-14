package by.devlab.cvs.branches

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.TestData
import by.devlab.cvs.branches.BranchFixtures.refreshed
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.vcsUtil.VcsUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Capability cvs-branch-merge, requirements "The merge point moves when the merge is committed" and "A root with an
 * unfinished merge is not switched" (design decisions 7, 9, 13, 14, 15): merges by [BranchMerger] against the
 * throwaway server, commits through the plugin's commit path followed by the handler [MergeCheckinHandlerFactory]
 * builds, rollbacks by deleting files and updating them back.
 */
@TestApplication
class MergeTrackingTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        const val ONE = "one.txt"
        const val TWO = "dir/two.txt"
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)
    private val prompts = TestPrompts()

    @Test
    @DisplayName("Merge point moves after the commit")
    fun mergePointMovesAfterTheCommit() {
        val (a, _) = roots("moves", listOf("BR_FIX"))
        mergeBoth(a)

        ideCommit(a.resolve(ONE), a.resolve(TWO))

        for (file in listOf(ONE, TWO)) {
            assertEquals(tag(a, file, "BR_FIX_MERGING_TRUNK"), tag(a, file, "BR_FIX_MERGED_TRUNK"), "MERGED of $file")
        }
        assertNull(service().unfinished(a))
    }

    @Test
    @DisplayName("Partial commit keeps the merge unfinished")
    fun partialCommitKeepsTheMergeUnfinished() {
        val (a, _) = roots("partial", listOf("BR_FIX"))
        mergeBoth(a)

        ideCommit(a.resolve(ONE))

        assertNull(tag(a, ONE, "BR_FIX_MERGED_TRUNK"))
        val merge = checkNotNull(service().unfinished(a)) { "the merge must stay unfinished" }
        assertEquals(setOf(TWO), merge.files.keys)
        assertTrue(merge.committed)
    }

    @Test
    @DisplayName("Rolled-back merge is forgotten")
    fun rolledBackMergeIsForgotten() {
        val (a, _) = roots("rollback", listOf("BR_FIX"))
        mergeBoth(a)

        rollBack(a, ONE, TWO)
        service().refresh()

        assertNull(service().unfinished(a))
        assertNull(tag(a, ONE, "BR_FIX_MERGED_TRUNK"))
        assertNull(tag(a, TWO, "BR_FIX_MERGED_TRUNK"))
    }

    // Review focus: a rollback followed by a teammate's commit and an update must not look like a commit.
    @Test
    @DisplayName("Rollback and a newer revision do not move the merge point")
    fun rollbackAndANewerRevisionDoNotMoveTheMergePoint() {
        val (a, _) = roots("newer", listOf("BR_FIX"))
        mergeBoth(a)
        Files.delete(a.resolve(ONE))
        commit(a, null, ONE, "one\nbob on the trunk\n")
        session.updateWith(project.get(), a)
        assertEquals("one\nbob on the trunk\n", text(a, ONE))

        service().refresh()

        assertNull(tag(a, ONE, "BR_FIX_MERGED_TRUNK"))
        val merge = checkNotNull(service().unfinished(a)) { "the merge must stay unfinished" }
        assertTrue(ONE in merge.files, "one.txt is neither committed nor rolled back: ${merge.files}")
        assertFalse(merge.committed)
    }

    // Review focus: root keys of unfinished merges on Windows.
    @Test
    @DisplayName("Unfinished merge kept across restarts")
    fun unfinishedMergeKeptAcrossRestarts() {
        val root = Path.of("C:\\x\\module-a")
        val merge = MergeService.UnfinishedMerge("BR_FIX", "TRUNK", mapOf(ONE to "1.1", TWO to "1.2"), mapOf(ONE to "1.1.2.1"), true)
        val stored = MergeService(project.get())
        stored.record(root, merge)

        val reopened = MergeService(project.get())
        reopened.loadState(stored.state)

        assertEquals(merge, reopened.unfinished(Path.of("C:/x/module-a")))
    }

    @Test
    @DisplayName("Repeated merge brings only new changes")
    fun repeatedMergeBringsOnlyNewChanges() {
        val (a, _) = roots("repeated", listOf("BR_FIX"))
        val bob = commit(a, "BR_FIX", ONE, "one\nfirst fix\n")
        merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a))
        ideCommit(a.resolve(ONE))
        assertEquals(tag(a, ONE, "BR_FIX_MERGING_TRUNK"), tag(a, ONE, "BR_FIX_MERGED_TRUNK"))
        commit(a, "BR_FIX", TWO, "two\nsecond fix\n", bob)

        val report = merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a))

        println(report?.text())
        assertEquals("one\nfirst fix\n", text(a, ONE))
        assertEquals("two\nsecond fix\n", text(a, TWO))
        assertEquals(emptyList<String>(), report!!.conflicts)
        assertEquals(setOf(TWO), service().unfinished(a)!!.files.keys, "only the new change is merged")
    }

    @Test
    @DisplayName("Snapshot fixes what is merged")
    fun snapshotFixesWhatIsMerged() {
        val (a, _) = roots("snapshot", listOf("BR_FIX"))
        val bob = commit(a, "BR_FIX", ONE, "one\nfirst fix\n")
        merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a))
        val snapshotOfTwo = tag(a, TWO, "BR_FIX_MERGING_TRUNK")
        commit(a, "BR_FIX", TWO, "two\nafter the snapshot\n", bob)
        ideCommit(a.resolve(ONE))
        assertEquals(snapshotOfTwo, tag(a, TWO, "BR_FIX_MERGED_TRUNK"), "MERGED marks the snapshot, not the branch head")

        merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a))

        assertEquals("two\nafter the snapshot\n", text(a, TWO))
        assertEquals("one\nfirst fix\n", text(a, ONE))
    }

    // Decision 14: the shared snapshot moved by another merge before the commit.
    @Test
    @DisplayName("Snapshot moved by another merge keeps the merge point")
    fun snapshotMovedByAnotherMergeKeepsTheMergePoint() {
        val (a, _) = roots("moved", listOf("BR_FIX"))
        val bob = commit(a, "BR_FIX", ONE, "one\nfirst fix\n")
        merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a))
        commit(a, "BR_FIX", TWO, "two\nbob's next fix\n", bob)
        session.server.cvs("rtag", "-F", "-r", "BR_FIX", "BR_FIX_MERGING_TRUNK", module(a))

        val (_, notification) = BranchFixtures.notified(project.get()) { ideCommit(a.resolve(ONE)) }

        val text = "${notification.title}\n${notification.content}"
        println(text)
        assertNull(tag(a, ONE, "BR_FIX_MERGED_TRUNK"))
        assertTrue(text.contains("moved by another merge"), text)
    }

    @Test
    @DisplayName("Switch skips a root with an unfinished merge")
    fun switchSkipsARootWithAnUnfinishedMerge() {
        val (a, b) = roots("switch", listOf("BR_FIX", "BR_RELEASE"))
        commit(a, "BR_FIX", ONE, "one\nfix\n")
        merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a))

        val report = BranchSwitcher(project.get(), prompts).switch(SwitchTarget.Branch("BR_RELEASE"), listOf(a, b))

        println(report?.text())
        for (file in session.workingFiles(b)) assertEquals("BR_RELEASE", session.stickyTag(file), "sticky tag of $file")
        for (file in session.workingFiles(a)) assertEquals("", session.stickyTag(file), "sticky tag of $file")
        assertEquals("one\nfix\n", text(a, ONE))
        assertEquals(listOf(module(a) to "unfinished merge of BR_FIX"), report!!.skipped)
    }

    private fun service() = MergeService.getInstance(project.get())

    private fun merger() = BranchMerger(project.get(), prompts)

    /** bob changes [ONE] and [TWO] on `BR_FIX`; the merge into [root] changes both. */
    private fun mergeBoth(root: Path) {
        val bob = commit(root, "BR_FIX", ONE, "one\nfix\n")
        commit(root, "BR_FIX", TWO, "two\nfix\n", bob)
        merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(root))
        assertEquals(setOf(ONE, TWO), service().unfinished(root)!!.files.keys)
    }

    /** Commits [files] the way the commit dialog does: the plugin's checkin environment, then the merge handler. */
    private fun ideCommit(vararg files: Path) {
        session.commit(project.get(), files.toList(), "alice commits the merge")
        val changes = files.map { Change(null, CurrentContentRevision(VcsUtil.getFilePath(it.toFile(), false))) }
        val handler = MergeCheckinHandlerFactory().createHandler(panel(project.get(), changes), CommitContext())
        // The commit dialog calls it on the UI thread.
        ApplicationManager.getApplication().invokeAndWait { handler.checkinSuccessful() }
        awaitIdle()
    }

    /** A commit dialog panel with [changes] selected, CVS affected. */
    private fun panel(project: Project, changes: List<Change>): CheckinProjectPanel =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CheckinProjectPanel::class.java)) { _, method, _ ->
            when (method.name) {
                "getProject" -> project
                "vcsIsAffected" -> true
                "getSelectedChanges" -> changes
                "getFiles" -> changes.map { it.afterRevision!!.file.ioFile }
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "test panel"
                else -> null
            }
        } as CheckinProjectPanel

    /** Rolls [files] of [root] back: deleted and updated again from the server. */
    private fun rollBack(root: Path, vararg files: String) {
        files.forEach { Files.delete(root.resolve(it)) }
        session.updateWith(project.get(), root)
        files.forEach { assertTrue(Files.exists(root.resolve(it)), "$it restored") }
    }

    private fun awaitIdle() {
        val operations = BranchOperations.getInstance(project.get())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (operations.isRunning) {
            check(System.nanoTime() < deadline) { "the branch operation did not finish" }
            Thread.sleep(50)
        }
    }

    private fun tag(root: Path, file: String, tag: String): String? = session.tagOf("${module(root)}/$file", tag)

    private fun module(root: Path): String = root.fileName.toString()

    private fun text(root: Path, file: String): String = TestData.text(root.resolve(file), Charsets.UTF_8)

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

    /** bob commits [file] of [root]'s module with [content] on [branch] (the trunk when null); returns his copy. */
    private fun commit(root: Path, branch: String?, file: String, content: String, copy: Path? = null): Path {
        val bob = copy ?: session.checkout("bob", module(root), session.newDirectory("${module(root)}-bob"), tag = branch)
        session.mapToCvs(project.get(), bob)
        TestData.edit(bob.resolve(file), content.toByteArray())
        session.commit(project.get(), listOf(bob.resolve(file)), "bob changes $file on ${branch ?: "the trunk"}")
        return bob
    }
}
