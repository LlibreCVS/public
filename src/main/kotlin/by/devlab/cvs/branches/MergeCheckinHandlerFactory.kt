package by.devlab.cvs.branches

import com.intellij.cvsSupport2.CvsVcs2
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.VcsCheckinHandlerFactory
import java.nio.file.Path

/**
 * Hands the files of a successful CVS commit from the IDE to the unfinished merges (cvs-branch-merge decision 9). The
 * files are taken when the commit starts: a non-modal commit lets the user change the selection before it succeeds.
 */
class MergeCheckinHandlerFactory : VcsCheckinHandlerFactory(CvsVcs2.getKey()) {

    override fun createVcsHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
        object : CheckinHandler() {
            private var committing: List<Path> = emptyList()

            override fun beforeCheckin(): ReturnResult {
                committing = panel.selectedChanges.flatMap { change ->
                    listOfNotNull(change.afterRevision, change.beforeRevision).map { it.file.ioFile.toPath() }
                }.distinct()
                return ReturnResult.COMMIT
            }

            override fun checkinSuccessful() {
                MergeService.getInstance(panel.project).committed(committing)
            }
        }
}
