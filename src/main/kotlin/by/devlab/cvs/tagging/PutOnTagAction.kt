package by.devlab.cvs.tagging

import com.intellij.cvsSupport2.changeBrowser.CvsChangeList
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

/** What the user asked for in the tag dialog. */
data class TagRequest(val tag: String, val move: Boolean)

/** Asks the user which tag to put on a changeset; null when the user cancels (design decision 22). */
fun interface TagRequestSource {
    fun ask(project: Project, changeset: CvsChangeList): TagRequest?
}

/** "Put on Tag..." in the Repository view: tags the files of one committed CVS changeset in the repository. */
class PutOnTagAction(private val requests: TagRequestSource = TagRequestSource { _, _ -> null }) : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = false
    }

    override fun actionPerformed(e: AnActionEvent) {
    }
}
