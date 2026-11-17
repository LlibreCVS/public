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
        const val THREE = "newdir/three.txt"
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)
    private val prompts = TestPrompts()

    @Test
    @DisplayName("Merge point moves after the commit")
    fun mergePointMovesAfterTheCommit() {
        val (a, _) = roots("moves", listOf("BR_FIX"))
        mergeBoth(a)
        val snapshot = snapshot(a, ONE, TWO)

        ideCommit(a.resolve(ONE), a.resolve(TWO))

        for (file in listOf(ONE, TWO)) {
            assertEquals(snapshot[file], tag(a, file, "BR_FIX_MERGED_TRUNK"), "MERGED of $file")
        }
        assertNull(service().unfinished(a))
    }

    @Test
    @DisplayName("Snapshot deleted after the commit")
    fun snapshotDeletedAfterTheCommit() {
        val (a, _) = roots("deleted", listOf("BR_FIX"))
        mergeBoth(a)
        assertTrue(carriesSnapshot(a), "the merge sets the snapshot")

        ideCommit(a.resolve(ONE), a.resolve(TWO))

        assertTrue(tag(a, ONE, "BR_FIX_MERGED_TRUNK") != null, "MERGED is moved first")
        assertFalse(carriesSnapshot(a), "BR_FIX_MERGING_TRUNK is deleted")
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
        val snapshot = snapshot(a, ONE)
        ideCommit(a.resolve(ONE))
        assertEquals(snapshot[ONE], tag(a, ONE, "BR_FIX_MERGED_TRUNK"))
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
    @DisplayName("Complete Merge moves the merge point")
    fun completeMergeMovesTheMergePoint() {
        val (a, _) = roots("complete", listOf("BR_FIX"))
        mergeBoth(a)
        val snapshot = snapshot(a, ONE, TWO)

        val (report, notification) = BranchFixtures.notified(project.get()) { service().complete(a) }

        println("${notification.title}\n${notification.content}")
        for (file in listOf(ONE, TWO)) {
            assertEquals(snapshot[file], tag(a, file, "BR_FIX_MERGED_TRUNK"), "MERGED of $file")
        }
        assertNull(service().unfinished(a))
        assertEquals(listOf(module(a)), report!!.switched)
    }

    @Test
    @DisplayName("Snapshot deleted by Complete Merge")
    fun snapshotDeletedByCompleteMerge() {
        val (a, _) = roots("complete-deleted", listOf("BR_FIX"))
        mergeBoth(a)
        assertTrue(carriesSnapshot(a), "the merge sets the snapshot")

        service().complete(a)

        assertNull(service().unfinished(a))
        assertFalse(carriesSnapshot(a), "BR_FIX_MERGING_TRUNK is deleted")
    }

    @Test
    @DisplayName("Forget Merge keeps the merge point")
    fun forgetMergeKeepsTheMergePoint() {
        val (a, _) = roots("forget", listOf("BR_FIX"))
        mergeBoth(a)

        val (report, notification) = BranchFixtures.notified(project.get()) { service().forget(a) }

        println("${notification.title}\n${notification.content}")
        assertNull(tag(a, ONE, "BR_FIX_MERGED_TRUNK"))
        assertNull(tag(a, TWO, "BR_FIX_MERGED_TRUNK"))
        assertNull(service().unfinished(a))
        assertEquals(listOf(module(a)), report!!.switched)
        assertEquals("one\nfix\n", text(a, ONE), "Forget Merge leaves the files as they are")
    }

    // Code review: a merge that brings a new directory must still move its merge point.
    @Test
    @DisplayName("Merge bringing a new directory moves the merge point")
    fun mergeBringingANewDirectoryMovesTheMergePoint() {
        val (a, _) = roots("newdir", listOf("BR_FIX"))
        val bob = commit(a, "BR_FIX", ONE, "one\nfix\n")
        TestData.write(bob.resolve(THREE), "three\n".toByteArray())
        session.add(project.get(), bob.resolve("newdir"), bob.resolve(THREE))
        session.commit(project.get(), listOf(bob.resolve(THREE)), "bob adds newdir/three.txt on BR_FIX")
        merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a))
        assertEquals("three\n", text(a, THREE))
        assertEquals(setOf(ONE, THREE), service().unfinished(a)!!.files.keys)
        val snapshot = snapshot(a, ONE, THREE)

        ideCommit(a.resolve(ONE), a.resolve(THREE))

        assertEquals(snapshot[ONE], tag(a, ONE, "BR_FIX_MERGED_TRUNK"))
        assertEquals(snapshot[THREE], tag(a, THREE, "BR_FIX_MERGED_TRUNK"))
        assertNull(service().unfinished(a))
    }

    // Code review, decision 15: New Branch… with the switch leaves a root with an unfinished merge where it is.
    @Test
    @DisplayName("New Branch with switch skips a root with an unfinished merge")
    fun newBranchWithSwitchSkipsARootWithAnUnfinishedMerge() {
        val (a, b) = roots("newbranch", listOf("BR_FIX"))
        commit(a, "BR_FIX", ONE, "one\nfix\n")
        merger().merge(SwitchTarget.Branch("BR_FIX"), listOf(a))
        prompts.newBranch = NewBranchRequest("BR_NEW", switch = true)

        val report = BranchCreator(project.get(), prompts).create(listOf(a, b))

        println(report?.text())
        for (file in session.workingFiles(a)) assertEquals("", session.stickyTag(file), "sticky tag of $file")
        for (file in session.workingFiles(b)) assertEquals("BR_NEW", session.stickyTag(file), "sticky tag of $file")
        assertTrue(report!!.skipped.any { (root, reason) -> root == module(a) && "unfinished merge of BR_FIX" in reason }, report.text())
    }

    // Code review: a commit made after the root left the merge's target must not move the merge point.
    @Test
    @DisplayName("Commit on another branch does not move the merge point")
    fun commitOnAnotherBranchDoesNotMoveTheMergePoint() {
        val (a, _) = roots("elsewhere", listOf("BR_FIX", "BR_RELEASE"))
        mergeBoth(a)
        // Outside the plugin's switch (e.g. OpenCVS's Update dialog with a branch), the edits travel to BR_RELEASE.
        session.updateWith(project.get(), a, CvsTestSession.UpdateOptions(branch = "BR_RELEASE"))

        ideCommit(a.resolve(ONE), a.resolve(TWO))

        assertNull(tag(a, ONE, "BR_FIX_MERGED_TRUNK"))
        assertEquals(setOf(ONE, TWO), service().unfinished(a)!!.files.keys, "nothing reached the trunk")
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

    /**
     * Commits [files] the way the commit dialog does: the merge handler's `beforeCheckin`, the plugin's checkin
     * environment, then `checkinSuccessful`. The panel's selection is cleared before the success callback, as a
     * non-modal commit lets the user change it meanwhile (code review): the handler must use what was committed.
     */
    private fun ideCommit(vararg files: Path) {
        val selected = files.map { Change(null, CurrentContentRevision(VcsUtil.getFilePath(it.toFile(), false))) }.toMutableList()
        val handler = MergeCheckinHandlerFactory().createHandler(panel(project.get(), selected), CommitContext())
        // The commit dialog calls both on the UI thread.
        ApplicationManager.getApplication().invokeAndWait { handler.beforeCheckin() }
        session.commit(project.get(), files.toList(), "alice commits the merge")
        selected.clear()
        ApplicationManager.getApplication().invokeAndWait { handler.checkinSuccessful() }
        awaitIdle()
    }

    /** A commit dialog panel with [changes] selected (read at each call), CVS affected. */
    private fun panel(project: Project, changes: List<Change>): CheckinProjectPanel =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CheckinProjectPanel::class.java)) { _, method, _ ->
            when (method.name) {
                "getProject" -> project
                "vcsIsAffected" -> true
                "getSelectedChanges" -> changes.toList()
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

    /** The revisions `BR_FIX_MERGING_TRUNK` marks on [files] of [root] now; read before a merge ends, which deletes it. */
    private fun snapshot(root: Path, vararg files: String): Map<String, String> =
        files.associateWith { file -> checkNotNull(tag(root, file, "BR_FIX_MERGING_TRUNK")) { "the snapshot marks $file" } }

    /** Whether any file of [root]'s module carries `BR_FIX_MERGING_TRUNK`, from the fixture's own `cvs rlog -h`. */
    private fun carriesSnapshot(root: Path): Boolean =
        session.server.cvs("rlog", "-h", module(root)).lines().any { it.trim().startsWith("BR_FIX_MERGING_TRUNK:") }

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
