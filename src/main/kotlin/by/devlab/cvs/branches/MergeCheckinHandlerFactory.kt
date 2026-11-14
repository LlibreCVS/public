package by.devlab.cvs.branches

import com.intellij.cvsSupport2.CvsVcs2
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.VcsCheckinHandlerFactory

/** Hands the files of a successful CVS commit from the IDE to the unfinished merges (cvs-branch-merge decision 9). */
class MergeCheckinHandlerFactory : VcsCheckinHandlerFactory(CvsVcs2.getKey()) {

    override fun createVcsHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
        object : CheckinHandler() {
            override fun checkinSuccessful() {
                val files = panel.selectedChanges.flatMap { change ->
                    listOfNotNull(change.afterRevision, change.beforeRevision).map { it.file.ioFile.toPath() }
                }.distinct()
                MergeService.getInstance(panel.project).committed(files)
            }
        }
}
