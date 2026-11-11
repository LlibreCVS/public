package by.devlab.cvs.tagging

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** "Put on Tag..." in the Repository view: tags the files of one committed CVS changeset in the repository. */
class PutOnTagAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = false
    }

    override fun actionPerformed(e: AnActionEvent) {
    }
}
