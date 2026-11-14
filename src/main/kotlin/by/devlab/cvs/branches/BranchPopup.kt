package by.devlab.cvs.branches

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.awt.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows the branch popup (design decisions 12, 16, 17): the platform's action-group popup with speed search, built
 * from [BranchPopupModel], under the clicked widget.
 */
object BranchPopup {

    /**
     * Shows the popup under [component] (centered in the project's window without one). While a list it shows is
     * loading, the popup closes itself when the load ends and a new one opens at the same place with the new lists;
     * a popup the user closed meanwhile is not reopened.
     */
    fun show(project: Project, component: Component?, opening: Boolean = true) {
        if (project.isDisposed) return
        val model = BranchPopupModel.build(project, opening)
        val dataContext = component?.let { DataManager.getInstance().getDataContext(it) }
            ?: SimpleDataContext.getProjectContext(project)
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "CVS Branches", group(project, model.entries, component), dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
        )
        if (model.loading.isNotEmpty()) reopenAfterLoad(project, model, popup, component)
        if (component != null && component.isShowing) popup.showUnderneathOf(component) else popup.showCenteredInCurrentWindow(project)
    }

    private fun reopenAfterLoad(project: Project, model: BranchPopupModel, popup: JBPopup, component: Component?) {
        val service = BranchListService.getInstance(project)
        val reopened = AtomicBoolean()
        val reopenIfLoaded = {
            if (model.loading.none { service.isLoading(it) } && reopened.compareAndSet(false, true)) {
                ApplicationManager.getApplication().invokeLater({
                    if (!popup.isDisposed && popup.isVisible) {
                        popup.cancel()
                        show(project, component, opening = false)
                    }
                }, project.disposed)
            }
        }
        project.messageBus.connect(popup).subscribe(BranchListService.TOPIC, BranchListListener { reopenIfLoaded() })
        // The load may have ended before the subscription.
        reopenIfLoaded()
    }

    private fun group(project: Project, entries: List<BranchPopupModel.Entry>, component: Component?): ActionGroup {
        val group = DefaultActionGroup()
        for (entry in entries) {
            when (entry) {
                is BranchPopupModel.Item -> group.add(ChoiceAction(project, entry, component))
                is BranchPopupModel.Submenu -> group.add((group(project, entry.entries, component) as DefaultActionGroup).apply {
                    isPopup = true
                    // Branch names often hold `_`, which must not become a mnemonic.
                    templatePresentation.setText(entry.text, false)
                    if (entry.current) templatePresentation.icon = AllIcons.Actions.Checked
                })
                is BranchPopupModel.Separator -> group.add(if (entry.text == null) Separator.create() else Separator.create(entry.text))
            }
        }
        return group
    }

    /** Does what choosing a popup entry means. */
    internal fun perform(project: Project, choice: BranchChoice, component: Component?) {
        when (choice) {
            is BranchChoice.Refresh -> {
                BranchListService.getInstance(project).load(choice.roots)
                // A new popup shows "Loading branches…" and reopens itself with the new lists (design decision 16).
                ApplicationManager.getApplication().invokeLater({ show(project, component, opening = false) }, project.disposed)
            }
            is BranchChoice.Switch -> BranchSwitcher(project).start(SwitchTarget.Branch(choice.branch), choice.roots)
            is BranchChoice.Trunk -> BranchSwitcher(project).start(SwitchTarget.Trunk, choice.roots)
            is BranchChoice.NewBranch -> BranchCreator(project).start(choice.roots)
            is BranchChoice.Merge -> BranchMerger(project).start(choice.source, choice.roots)
            is BranchChoice.CompleteMerge -> BranchOperations.getInstance(project).queue("Completing CVS merge") {
                MergeService.getInstance(project).complete(choice.root)
            }
            is BranchChoice.ForgetMerge -> BranchOperations.getInstance(project).queue("Forgetting CVS merge") {
                MergeService.getInstance(project).forget(choice.root)
            }
        }
    }

    private class ChoiceAction(
        private val project: Project,
        private val item: BranchPopupModel.Item,
        private val component: Component?,
    ) : DumbAwareAction() {

        init {
            templatePresentation.setText(item.text, false)
            if (item.current) templatePresentation.icon = AllIcons.Actions.Checked
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = item.enabled
        }

        override fun actionPerformed(e: AnActionEvent) {
            item.choice?.let { perform(project, it, component) }
        }
    }
}
