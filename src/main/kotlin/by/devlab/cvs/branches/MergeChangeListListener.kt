package by.devlab.cvs.branches

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListListener

/**
 * Re-checks the unfinished merges for rolled-back files after each change-list update (cvs-branch-merge decision 9):
 * from what the change list already has, so that the check never starts another update.
 */
class MergeChangeListListener(private val project: Project) : ChangeListListener {

    override fun changeListUpdateDone() {
        if (project.isDisposed) return
        val service = MergeService.getInstance(project)
        if (service.roots().isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!project.isDisposed) service.refresh(fresh = false)
        }
    }
}
