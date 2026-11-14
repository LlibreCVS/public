package by.devlab.cvs.branches

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * The CVS branch widget of the status bar (design decisions 3, 9, 10, 12): available only in a project with CVS
 * roots; its text and tooltip are the indicator's. A click opens the branch popup ([BranchPopup]).
 */
class BranchStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = ID

    override fun getDisplayName(): String = "CVS Branch"

    override fun isAvailable(project: Project): Boolean = BranchStateService.getInstance(project).roots().isNotEmpty()

    override fun createWidget(project: Project): StatusBarWidget = BranchStatusBarWidget(project)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID = "IntelliJCVS.BranchWidget"
    }
}

private class BranchStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null

    override fun ID(): String = BranchStatusBarWidgetFactory.ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        project.messageBus.connect(this).subscribe(BranchStateService.TOPIC, BranchStateListener {
            ApplicationManager.getApplication().invokeLater({ this.statusBar?.updateWidget(ID()) }, project.disposed)
        })
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = indicator()?.text.orEmpty()

    override fun getTooltipText(): String? = indicator()?.let { BranchIcons.html(it.tooltip) }

    override fun getAlignment(): Float = Component.LEFT_ALIGNMENT

    /** Opens the branch popup under the widget (cvs-branch-popup design decision 17). */
    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        if (!project.isDisposed) BranchPopup.show(project, event.component)
    }

    private fun indicator(): BranchStateService.Indicator? =
        if (project.isDisposed) null else BranchStateService.getInstance(project).indicator()

    override fun dispose() {
        statusBar = null
    }
}
