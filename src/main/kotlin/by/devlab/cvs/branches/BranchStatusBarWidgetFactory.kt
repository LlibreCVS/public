package by.devlab.cvs.branches

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/** The CVS branch widget of the status bar (design decisions 3, 9, 10, 12). */
class BranchStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = ID

    override fun getDisplayName(): String = "CVS Branch"

    override fun isAvailable(project: Project): Boolean = TODO()

    override fun createWidget(project: Project): StatusBarWidget = TODO()

    companion object {
        const val ID = "IntelliJCVS.BranchWidget"
    }
}
