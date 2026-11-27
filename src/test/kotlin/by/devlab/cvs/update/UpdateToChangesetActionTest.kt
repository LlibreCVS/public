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
import com.intellij.cvsSupport2.changeBrowser.CvsChangeList
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Capability changeset-update, requirements "Update to This Changeset is offered only on the trunk" and "The scope is
 * chosen in a dialog" (design decisions 2, 4, 6, 12, 13, 16): the action against changesets loaded from the throwaway
 * server, with the dialog replaced by a fixed answer.
 */
@TestApplication
class UpdateToChangesetActionTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        const val ONE = "one.txt"

        /** Longer than a CVS date's resolution (one second), so that commits fall in different seconds. */
        const val PAUSE = 2_100L
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("Update action enabled for a trunk changeset")
    fun updateActionEnabledForATrunkChangeset() {
        val root = root("enabled")
        edit(root, "one, changeset\n", "the changeset")

        assertTrue(enabled(changeset(root, "the changeset")))
    }

    @Test
    @DisplayName("Update action disabled for a branch changeset")
    fun updateActionDisabledForABranchChangeset() {
        val root = root("branch-cs")
        val module = root.fileName.toString()
        session.branch(module, "BR")
        val branch = session.checkout("alice", module, session.newDirectory("$module-br"), tag = "BR")
        session.mapToCvs(project.get(), branch)
        edit(branch, "one on the branch\n", "branch edit")
        val changeset = changeset(branch, "branch edit")
        // Only the trunk root stays in the project: the action is disabled by the changeset, not by a root.
        BranchFixtures.mapToCvs(project.get(), root)

        assertFalse(enabled(changeset))
    }

    @Test
    @DisplayName("Update action disabled for several changesets")
    fun updateActionDisabledForSeveralChangesets() {
        val root = root("several")
        edit(root, "one, first\n", "first edit")
        edit(root, "one, second\n", "second edit")

        assertFalse(enabled(changeset(root, "first edit"), changeset(root, "second edit")))
    }

    @Test
    @DisplayName("Update action disabled on a branch or tag working copy")
    fun updateActionDisabledOnABranchOrTagWorkingCopy() {
        val root = root("on-branch")
        val other = root("on-tag")
        edit(root, "one, changeset\n", "the changeset")
        val changeset = changeset(root, "the changeset")
        session.branch(other.fileName.toString(), "BR_OTHER")
        session.updateWith(project.get(), other, UpdateOptions(branch = "BR_OTHER"))

        assertFalse(enabled(changeset), "a root on a branch")

        session.tag(other.fileName.toString(), "RELEASE_1", "HEAD")
        session.updateWith(project.get(), other, UpdateOptions(branch = "RELEASE_1"))
        assertEquals(BranchKind.TAG, BranchState.read(other).kind)

        assertFalse(enabled(changeset), "a root on a tag")
    }

    @Test
    @DisplayName("Update action enabled on a dated working copy")
    fun updateActionEnabledOnADatedWorkingCopy() {
        val root = root("dated")
        edit(root, "one, first\n", "first edit")
        Thread.sleep(PAUSE)
        edit(root, "one, second\n", "second edit")
        val first = changeset(root, "first edit")
        BranchSwitcher(project.get(), TestPrompts()).switch(SwitchTarget.Date(ChangesetCutoff.of(first)), listOf(root))
        assertEquals(BranchKind.DATE, BranchState.read(root).kind)

        assertTrue(enabled(changeset(root, "second edit")))
    }

    @Test
    @DisplayName("Cancelled dialog changes nothing")
    fun cancelledDialogChangesNothing() {
        val root = root("cancel")
        edit(root, "one, changeset\n", "the changeset")
        Thread.sleep(PAUSE)
        edit(root, "one, later\n", "a later edit")
        val changeset = changeset(root, "the changeset")
        val requestsBefore = session.server.requestLines().size
        var asked = 0

        val notification = perform(changeset, UpdateScopeSource { _, _, _ -> asked++; null }, wait = 3)

        assertEquals(1, asked)
        assertNull(notification)
        assertEquals(requestsBefore, session.server.requestLines().size, "no CVS command ran")
        assertEquals("one, later\n", text(root))
        assertEquals(BranchState.TRUNK, BranchState.read(root))
    }

    // Also the review focus of design decision 16: a root on another repository is left alone and not reported.
    @Test
    @DisplayName("Whole project is the default scope")
    fun wholeProjectIsTheDefaultScope() {
        val a = root("whole-a")
        val b = root("whole-b")
        val foreign = BranchFixtures.workingCopy(session.newDirectory("whole-foreign"), "foreign")
        session.mapToCvs(project.get(), foreign)
        edit(a, "one, changeset\n", "the changeset")
        Thread.sleep(PAUSE)
        edit(a, "one, later\n", "a later edit")
        val changeset = changeset(a, "the changeset")
        var offered: List<Path>? = null

        val notification = perform(changeset, UpdateScopeSource { _, _, roots -> offered = roots; UpdateScope.WholeProject })

        val text = "${notification!!.title}\n${notification.content}"
        println(text)
        assertEquals(setOf(a, b), offered?.toSet(), "a folder is accepted only in a root on the changeset's repository")
        assertEquals("one, changeset\n", text(a))
        assertEquals(BranchKind.DATE, BranchState.read(a).kind)
        assertEquals(BranchKind.DATE, BranchState.read(b).kind)
        assertEquals(BranchState.TRUNK, BranchState.read(foreign))
        assertTrue(text.contains("Updated: ${a.fileName}, ${b.fileName}") || text.contains("Updated: ${b.fileName}, ${a.fileName}"), text)
        assertFalse(text.contains("foreign"), text)
    }

    @Test
    @DisplayName("One folder scope updates that folder")
    fun oneFolderScopeUpdatesThatFolder() {
        val root = root("folder")
        edit(root, "one, changeset\n", "the changeset")
        Thread.sleep(PAUSE)
        edit(root, "one, later\n", "a later edit")
        val folder = root.resolve("dir")

        val notification = perform(changeset(root, "the changeset"), UpdateScopeSource { _, _, _ -> UpdateScope.Folder(folder) })

        val text = "${notification!!.title}\n${notification.content}"
        assertTrue(text.contains("Updated: ${root.fileName}/dir"), text)
        assertEquals(BranchKind.DATE, BranchState.read(folder).kind, "the folder is sticky on the date")
        assertEquals(BranchState.TRUNK, BranchState.read(root))
    }

    // Code review: a mapped folder above several checkouts is not a root; its working copies are (decision 17).
    @Test
    @DisplayName("Whole project under a mapped parent folder updates its working copies")
    fun wholeProjectUnderAMappedParentFolderUpdatesItsWorkingCopies() {
        val parent = session.newDirectory("parent-wc")
        val (a, b) = listOf("parent-a", "parent-b").map { module ->
            val seed = session.newDirectory("$module-seed")
            TestData.write(seed.resolve(ONE), "one\n".toByteArray())
            session.importModule("alice", seed, module)
            session.checkout("alice", module, parent)
        }
        BranchFixtures.mapToCvs(project.get(), parent)
        Thread.sleep(PAUSE)
        edit(a, "one, changeset\n", "the changeset")
        Thread.sleep(PAUSE)
        edit(a, "one, later\n", "a later edit")
        val changeset = changeset(a, "the changeset")

        val notification = perform(changeset, UpdateScopeSource { _, _, _ -> UpdateScope.WholeProject })

        val text = "${notification!!.title}\n${notification.content}"
        println(text)
        assertEquals("one, changeset\n", text(a))
        assertEquals(BranchKind.DATE, BranchState.read(b).kind)
        assertTrue(text.contains("Updated: ${a.fileName}, ${b.fileName}"), text)
    }

    /** Module `<prefix>-m` with [ONE] and `dir/two.txt`, checked out as alice and mapped to CVS. */
    private fun root(prefix: String): Path {
        val module = "$prefix-m"
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve(ONE), "one\n".toByteArray())
        TestData.write(seed.resolve("dir/two.txt"), "two\n".toByteArray())
        session.importModule("alice", seed, module)
        val root = session.checkout("alice", module, session.newDirectory("$prefix-wc"))
        session.mapToCvs(project.get(), root)
        Thread.sleep(PAUSE)
        return root
    }

    private fun edit(root: Path, content: String, message: String) {
        TestData.edit(root.resolve(ONE), content.toByteArray())
        session.commit(project.get(), listOf(root.resolve(ONE)), message)
    }

    private fun changeset(root: Path, message: String): CvsChangeList =
        session.changesets(project.get(), root).single { it.comment.trim() == message }

    private fun text(root: Path) = TestData.text(root.resolve(ONE), Charsets.UTF_8)

    /** Whether the action is enabled with [changesets] selected, after the root states were refreshed. */
    private fun enabled(vararg changesets: CommittedChangeList): Boolean {
        refreshed(project.get())
        val action = UpdateToChangesetAction()
        val event = TestActionEvent.createTestEvent(action, context(*changesets))
        action.update(event)
        return event.presentation.isEnabled
    }

    /**
     * Runs the action on [changeset] with the dialog answering through [scopes]; returns the LibreCVS notification it
     * shows within [wait] seconds, or null.
     */
    private fun perform(changeset: CvsChangeList, scopes: UpdateScopeSource, wait: Long = 120): Notification? {
        refreshed(project.get())
        val shown = LinkedBlockingQueue<Notification>()
        val disposable = Disposer.newDisposable()
        try {
            project.get().messageBus.connect(disposable).subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId == "LibreCVS") shown += notification
                }
            })
            val action = UpdateToChangesetAction(scopes, TestPrompts())
            runInEdtAndWait { action.actionPerformed(TestActionEvent.createTestEvent(action, context(changeset))) }
            return shown.poll(wait, TimeUnit.SECONDS)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun context(vararg changesets: CommittedChangeList) = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project.get())
        .add(VcsDataKeys.CHANGE_LISTS, arrayOf(*changesets))
        .build()
}
