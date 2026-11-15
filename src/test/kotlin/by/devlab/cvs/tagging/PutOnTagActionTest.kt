package by.devlab.cvs.tagging

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.TestData
import com.intellij.cvsSupport2.changeBrowser.CvsChangeList
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
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
import com.intellij.cvsSupport2.connections.CvsConnectionSettings
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Capability changeset-tagging, requirements "Put on Tag is offered for one trunk commit" and "The result of
 * tagging is reported" (design decisions 4, 11, 12, 18, 20, 21, 22): the action runs against changesets loaded
 * from the throwaway server, with the dialog replaced by a fixed answer.
 */
@TestApplication
class PutOnTagActionTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("Action enabled for one trunk changeset")
    fun actionEnabledForOneTrunkChangeset() {
        val copy = module("action-trunk")
        edit(copy, "trunk edit")

        assertTrue(enabled(changeset(copy, "trunk edit")))
    }

    @Test
    @DisplayName("Action enabled for a trunk changeset that became a branch point")
    fun actionEnabledForATrunkChangesetThatBecameABranchPoint() {
        val copy = module("action-branch-point")
        edit(copy, "trunk edit")
        session.branch("action-branch-point", "BR")
        val branch = session.checkout("alice", "action-branch-point", session.newDirectory("action-branch-point-br"), tag = "BR")
        session.mapToCvs(project.get(), branch)
        edit(branch, "branch edit")

        assertTrue(enabled(changeset(copy, "trunk edit")))
    }

    @Test
    @DisplayName("Action disabled for several changesets")
    fun actionDisabledForSeveralChangesets() {
        val copy = module("action-several")
        edit(copy, "first edit")
        edit(copy, "second edit")

        assertFalse(enabled(changeset(copy, "first edit"), changeset(copy, "second edit")))
    }

    @Test
    @DisplayName("Action disabled for a branch changeset")
    fun actionDisabledForABranchChangeset() {
        val copy = module("action-branch")
        session.branch("action-branch", "BR")
        val branch = session.checkout("alice", "action-branch", session.newDirectory("action-branch-br"), tag = "BR")
        session.mapToCvs(project.get(), branch)
        edit(branch, "branch edit")

        assertFalse(enabled(changeset(branch, "branch edit")))
    }

    @Test
    @DisplayName("Success is reported")
    fun successIsReported() {
        val copy = module("action-success")
        edit(copy, "edit of two files")

        val notification = perform(changeset(copy, "edit of two files"), TagRequest("TEST", move = true))

        assertEquals(NotificationType.INFORMATION, notification.type)
        assertTrue("TEST" in notification.content && "2 files" in notification.content, notification.content)
        assertEquals("1.2", session.tagOf("action-success/one.txt", "TEST"))
    }

    @Test
    @DisplayName("Files left unchanged are reported as a warning")
    fun filesLeftUnchangedAreReportedAsAWarning() {
        val copy = module("action-warning")
        session.branch("action-warning", "BR")
        edit(copy, "edit after the branch")

        val notification = perform(changeset(copy, "edit after the branch"), TagRequest("BR", move = true))

        assertEquals(NotificationType.WARNING, notification.type)
        assertTrue("Not moving branch tag" in notification.content, notification.content)
        assertFalse("set on" in notification.title + notification.content, notification.content)
    }

    @Test
    @DisplayName("Server error is reported")
    fun serverErrorIsReported() {
        val copy = module("action-error")
        edit(copy, "edit of two files")
        val changeset = changeset(copy, "edit of two files")
        Files.move(session.server.repositoryFile("action-error"), session.server.repositoryFile("action-error-gone"))

        val notification = perform(changeset, TagRequest("TEST", move = true))

        assertEquals(NotificationType.ERROR, notification.type)
        assertTrue("action-error" in notification.content, notification.content)
    }

    @Test
    @DisplayName("Server error is reported when the root is offline")
    fun serverErrorIsReportedWhenTheRootIsOffline() {
        val copy = module("action-offline")
        edit(copy, "edit of two files")
        val changeset = changeset(copy, "edit of two files")
        // The plugin takes a root offline when the user cancels a login; no command runs then.
        val settings = changeset.environment as CvsConnectionSettings
        settings.setOffline(true)
        try {
            val notification = perform(changeset, TagRequest("TEST", move = true))

            assertEquals(NotificationType.ERROR, notification.type, notification.content)
            assertNull(session.tagOf("action-offline/one.txt", "TEST"))
        } finally {
            settings.setOffline(false)
        }
    }

    /** Imports `one.txt` and `two.txt` as [name], checks the module out as `alice` and maps it to CVS. */
    private fun module(name: String): Path {
        val seed = session.newDirectory("$name-seed")
        listOf("one.txt", "two.txt").forEach { TestData.write(seed.resolve(it), "$it\n".toByteArray()) }
        session.importModule("alice", seed, name)
        val copy = session.checkout("alice", name, session.newDirectory(name))
        session.mapToCvs(project.get(), copy)
        return copy
    }

    /** Edits and commits both files of [copy] with [message]. */
    private fun edit(copy: Path, message: String) {
        val files = listOf(copy.resolve("one.txt"), copy.resolve("two.txt"))
        files.forEach { TestData.edit(it, "$message\n".toByteArray()) }
        session.commit(project.get(), files, message)
    }

    private fun changeset(copy: Path, message: String): CvsChangeList =
        session.changesets(project.get(), copy).single { it.comment.trim() == message }

    /** Whether the action is enabled with [changesets] selected in the Repository view. */
    private fun enabled(vararg changesets: CommittedChangeList): Boolean {
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project.get())
            .add(VcsDataKeys.CHANGE_LISTS, arrayOf(*changesets))
            .build()
        val event = TestActionEvent.createTestEvent(PutOnTagAction(), context)
        PutOnTagAction().update(event)
        return event.presentation.isEnabledAndVisible
    }

    /** Runs the action on [changeset] with the dialog answering [request]; returns the notification it shows. */
    private fun perform(changeset: CvsChangeList, request: TagRequest): Notification {
        val shown = LinkedBlockingQueue<Notification>()
        val disposable = Disposer.newDisposable()
        try {
            project.get().messageBus.connect(disposable).subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId == "LibreCVS") shown += notification
                }
            })
            val action = PutOnTagAction { _, _ -> request }
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project.get())
                .add(VcsDataKeys.CHANGE_LISTS, arrayOf<CommittedChangeList>(changeset))
                .build()
            runInEdtAndWait { action.actionPerformed(TestActionEvent.createTestEvent(action, context)) }
            return checkNotNull(shown.poll(120, TimeUnit.SECONDS)) { "no LibreCVS notification" }
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
