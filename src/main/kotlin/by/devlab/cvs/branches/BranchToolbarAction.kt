package by.devlab.cvs.branches

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.text.StringUtil
import javax.swing.Icon

/**
 * The CVS branch widget of the main toolbar (design decisions 3, 9, 10, 12): the indicator's text and icon, hidden
 * in a project without CVS roots. Its update only reads the cache of [BranchStateService]; a click opens the branch
 * popup ([BranchPopup]).
 */
class BranchToolbarAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val indicator = if (project == null || project.isDisposed) null else BranchStateService.getInstance(project).indicator()
        val presentation = e.presentation
        if (indicator == null) {
            presentation.isEnabledAndVisible = false
            return
        }
        presentation.isEnabledAndVisible = true
        // Branch names often hold `_`, which must not become a mnemonic.
        presentation.setText(indicator.text, false)
        presentation.description = indicator.tooltip
        presentation.icon = BranchIcons.of(indicator.state.kind)
        presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    /** Opens the branch popup under the widget (cvs-branch-popup design decision 17). */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        BranchPopup.show(project, e.inputEvent?.component ?: e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT))
    }
}

/** The icons of the indicator: a branch for the trunk and branches, a tag, a clock for a date (design decision 6). */
internal object BranchIcons {
    fun of(kind: BranchKind): Icon = when (kind) {
        BranchKind.TRUNK, BranchKind.BRANCH -> AllIcons.Vcs.Branch
        BranchKind.TAG -> AllIcons.Nodes.Tag
        BranchKind.DATE -> AllIcons.Vcs.History
    }

    /** The indicator's tooltip lines as HTML. */
    fun html(tooltip: String): String =
        tooltip.lines().joinToString("<br>", "<html>", "</html>") { StringUtil.escapeXmlEntities(it) }
}
