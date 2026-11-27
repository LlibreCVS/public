package by.devlab.cvs.update

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.CvsTestSession.UpdateOptions
import by.devlab.cvs.TestData
import by.devlab.cvs.branches.BranchFixtures
import by.devlab.cvs.branches.BranchFixtures.refreshed
import by.devlab.cvs.branches.BranchKind
import by.devlab.cvs.branches.BranchState
import by.devlab.cvs.branches.BranchSwitcher
import by.devlab.cvs.branches.SwitchTarget
import by.devlab.cvs.branches.TestPrompts
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.nio.file.Path

/**
 * Capability changeset-update, requirement "The working copy gets the trunk state of the changeset" (design decisions
 * 7, 8, 9, 10, 18): [BranchSwitcher] with [SwitchTarget.Date] over modules checked out from the throwaway server.
 */
@TestApplication
class UpdateToChangesetTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        const val ONE = "one.txt"
        const val TWO = "dir/two.txt"
        const val NEW = "new.txt"

        /** Longer than a CVS date's resolution (one second), so that commits fall in different seconds. */
        const val PAUSE = 2_100L
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)
    private val prompts = TestPrompts()

    @Test
    @DisplayName("Selected changeset present and later commits absent")
    fun selectedChangesetPresentAndLaterCommitsAbsent() {
        val root = root("present")
        edit(root, ONE, "one, changeset\n", "the changeset")
        Thread.sleep(PAUSE)
        edit(root, ONE, "one, later\n", "a later edit")
        add(root, NEW, "new, later\n", "a later file")

        val report = switcher().switch(SwitchTarget.Date(cutoff(root, "the changeset")), listOf(root))

        println(report?.text())
        assertEquals("one, changeset\n", text(root, ONE))
        assertFalse(Files.exists(root.resolve(NEW)), "the later-added file is gone")
        assertEquals(listOf(root.fileName.toString()), report!!.switched)
    }

    @Test
    @DisplayName("Changeset spread over several seconds is included whole")
    fun changesetSpreadOverSeveralSecondsIsIncludedWhole() {
        val root = root("spread")
        edit(root, ONE, "one, changeset\n", "the changeset")
        Thread.sleep(PAUSE)
        edit(root, TWO, "two, changeset\n", "the changeset")
        Thread.sleep(PAUSE)
        edit(root, ONE, "one, later\n", "a later edit")
        val changeset = changeset(root, "the changeset")
        assertEquals(2, changeset.fileRevisions.size, "one changeset over two seconds")

        switcher().switch(SwitchTarget.Date(ChangesetCutoff.of(changeset)), listOf(root))

        assertEquals("one, changeset\n", text(root, ONE))
        assertEquals("two, changeset\n", text(root, TWO))
    }

    @Test
    @DisplayName("Updated copy is sticky on the date")
    fun updatedCopyIsStickyOnTheDate() {
        val root = root("sticky")
        edit(root, ONE, "one, changeset\n", "the changeset")
        Thread.sleep(PAUSE)
        edit(root, ONE, "one, later\n", "a later edit")
        val cutoff = cutoff(root, "the changeset")

        switcher().switch(SwitchTarget.Date(cutoff), listOf(root))

        val date = cutoff.utcText.removeSuffix(" UTC")
        assertEquals(BranchState(BranchKind.DATE, date), BranchState.read(root))
        val indicator = refreshed(project.get()).indicator(BranchFixtures.virtualFile(root.resolve(ONE)))
        assertEquals("$date (date)", indicator?.text)

        switcher().switch(SwitchTarget.Trunk, listOf(root))

        assertEquals(BranchState.TRUNK, BranchState.read(root))
        assertEquals("one, later\n", text(root, ONE))
    }

    @Test
    @DisplayName("Folder scope leaves other files unchanged")
    fun folderScopeLeavesOtherFilesUnchanged() {
        val root = root("folder")
        edit(root, TWO, "two, changeset\n", "the changeset")
        Thread.sleep(PAUSE)
        edit(root, TWO, "two, later\n", "a later edit")
        edit(root, ONE, "one, later\n", "a later edit outside")

        val report = switcher().switch(SwitchTarget.Date(cutoff(root, "the changeset")), listOf(root.resolve("dir")))

        println(report?.text())
        assertEquals("two, changeset\n", text(root, TWO))
        assertEquals("one, later\n", text(root, ONE))
        assertEquals(BranchState.TRUNK, BranchState.read(root))
        assertEquals(listOf("${root.fileName}/dir"), report!!.switched)
    }

    // Review focus: update -D restores a file removed after the changeset from the Attic.
    @Test
    @DisplayName("File removed after the changeset comes back")
    fun fileRemovedAfterTheChangesetComesBack() {
        val root = root("comeback")
        edit(root, ONE, "one, changeset\n", "the changeset")
        Thread.sleep(PAUSE)
        session.removeAndCommit(project.get(), root.resolve(TWO), "a later removal")

        switcher().switch(SwitchTarget.Date(cutoff(root, "the changeset")), listOf(root))

        assertEquals("two\n", text(root, TWO))
    }

    // Review focus: a removal's (dead) revision time counts for the cut-off, and the file is absent afterwards.
    @Test
    @DisplayName("File removed in the changeset is absent")
    fun fileRemovedInTheChangesetIsAbsent() {
        val root = root("removed")
        session.removeAndCommit(project.get(), root.resolve(TWO), "the changeset")
        Thread.sleep(PAUSE)
        edit(root, ONE, "one, later\n", "a later edit")
        val changeset = changeset(root, "the changeset")
        assertTrue(changeset.fileRevisions.single().removal(), "the changeset is the removal")

        switcher().switch(SwitchTarget.Date(ChangesetCutoff.of(changeset)), listOf(root))

        assertFalse(Files.exists(root.resolve(TWO)), "the file removed in the changeset is absent")
        assertEquals("one\n", text(root, ONE))
    }

    // Review focus, design decision 18: update -D without -r replaces a file's branch tag with the date.
    @Test
    @DisplayName("File sticky on a branch in a trunk root ends at its trunk state")
    fun fileStickyOnABranchInATrunkRootEndsAtItsTrunkState() {
        val root = root("branchfile")
        val module = root.fileName.toString()
        session.branch(module, "BR_SIDE")
        val bob = session.checkout("bob", module, session.newDirectory("$module-bob"), tag = "BR_SIDE")
        session.mapToCvs(project.get(), bob)
        TestData.edit(bob.resolve(ONE), "one on the branch\n".toByteArray())
        session.commit(project.get(), listOf(bob.resolve(ONE)), "bob on the branch")
        edit(root, ONE, "one, changeset\n", "the changeset")
        Thread.sleep(PAUSE)
        edit(root, ONE, "one, later\n", "a later edit")
        session.updateWith(project.get(), root.resolve(ONE), UpdateOptions(branch = "BR_SIDE"))
        assertEquals("BR_SIDE", session.stickyTag(root.resolve(ONE)))

        switcher().switch(SwitchTarget.Date(cutoff(root, "the changeset")), listOf(root))

        assertEquals("one, changeset\n", text(root, ONE))
        assertEquals("", session.stickyTag(root.resolve(ONE)), "no branch tag is left on the file")
    }

    private fun switcher() = BranchSwitcher(project.get(), prompts)

    /** Module `<prefix>-m` with [ONE] and [TWO] (`one\n`, `two\n`), checked out as alice and mapped to CVS. */
    private fun root(prefix: String): Path {
        val module = "$prefix-m"
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve(ONE), "one\n".toByteArray())
        TestData.write(seed.resolve(TWO), "two\n".toByteArray())
        session.importModule("alice", seed, module)
        val root = session.checkout("alice", module, session.newDirectory("$prefix-wc"))
        session.mapToCvs(project.get(), root)
        refreshed(project.get())
        // The import's own revisions must lie in an earlier second than the first commit of the test.
        Thread.sleep(PAUSE)
        return root
    }

    private fun edit(root: Path, file: String, content: String, message: String) {
        TestData.edit(root.resolve(file), content.toByteArray())
        session.commit(project.get(), listOf(root.resolve(file)), message)
    }

    private fun add(root: Path, file: String, content: String, message: String) {
        TestData.write(root.resolve(file), content.toByteArray())
        BranchFixtures.virtualFile(root).refresh(false, true)
        session.add(project.get(), root.resolve(file))
        session.commit(project.get(), listOf(root.resolve(file)), message)
    }

    private fun changeset(root: Path, message: String) =
        session.changesets(project.get(), root).single { it.comment.trim() == message }

    private fun cutoff(root: Path, message: String) = ChangesetCutoff.of(changeset(root, message))

    private fun text(root: Path, file: String) = TestData.text(root.resolve(file), Charsets.UTF_8)
}
