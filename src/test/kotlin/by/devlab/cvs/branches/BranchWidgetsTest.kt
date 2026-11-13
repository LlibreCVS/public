package by.devlab.cvs.branches

import by.devlab.cvs.branches.BranchFixtures.mapToCvs
import by.devlab.cvs.branches.BranchFixtures.refreshed
import by.devlab.cvs.branches.BranchFixtures.workingCopy
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Capability cvs-branch-indicator, requirement "Indicator in the toolbar and the status bar": the toolbar action and
 * the status-bar widget factory in projects with and without CVS roots (design decisions 3, 9, 10).
 */
@TestApplication
class BranchWidgetsTest {

    private val temp = tempPathFixture()
    private val project = projectFixture(temp, openAfterCreation = true)

    @Test
    @DisplayName("Shown with a CVS root")
    fun shownWithACvsRoot() {
        val root = workingCopy(temp.get(), "wc", "TBR_RELEASE")
        mapToCvs(project.get(), root)
        refreshed(project.get())

        val factory = BranchStatusBarWidgetFactory()
        assertTrue(factory.isAvailable(project.get()))
        assertEquals("BR_RELEASE", statusBarText(factory))
        val event = toolbarUpdate()
        assertTrue(event.presentation.isEnabledAndVisible)
        assertEquals("BR_RELEASE", event.presentation.text)
    }

    @Test
    @DisplayName("Hidden without CVS roots")
    fun hiddenWithoutCvsRoots() {
        refreshed(project.get())

        assertFalse(BranchStatusBarWidgetFactory().isAvailable(project.get()))
        assertFalse(toolbarUpdate().presentation.isVisible)
    }

    // Review focus: the toolbar action's update reads only the cache, off the UI thread.
    @Test
    @DisplayName("Toolbar update reads only the cache")
    fun toolbarUpdateReadsOnlyTheCache() {
        val root = workingCopy(temp.get(), "wc", "TBR_RELEASE")
        mapToCvs(project.get(), root)
        refreshed(project.get())

        Files.writeString(root.resolve("CVS/Tag"), "NRELEASE_1\n")

        assertEquals(ActionUpdateThread.BGT, BranchToolbarAction().actionUpdateThread)
        assertEquals("BR_RELEASE", toolbarUpdate().presentation.text)
    }

    /** The toolbar action updated in a data context of the test project. */
    private fun toolbarUpdate(): AnActionEvent {
        val action = BranchToolbarAction()
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project.get()))
        action.update(event)
        return event
    }

    /** The text of a status-bar widget the factory creates for the test project. */
    private fun statusBarText(factory: BranchStatusBarWidgetFactory): String? {
        val widget = factory.createWidget(project.get())
        try {
            return (widget.getPresentation() as StatusBarWidget.TextPresentation).getText()
        } finally {
            Disposer.dispose(widget)
        }
    }
}
