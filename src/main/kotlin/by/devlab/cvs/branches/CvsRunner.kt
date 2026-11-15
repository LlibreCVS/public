package by.devlab.cvs.branches

import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutor
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutorCallback
import com.intellij.cvsSupport2.cvshandlers.CvsHandler
import com.intellij.cvsSupport2.cvsoperations.cvsMessages.CvsMessagesAdapter
import com.intellij.cvsSupport2.cvsoperations.cvsMessages.MessageEvent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Path

/** Runs OpenCVS handlers for the branch actions without a CVS tab or error dialogs (design decision 14). */
internal object CvsRunner {

    /**
     * Runs [handler] synchronously on the calling thread (never the UI thread) and returns the first CVS error, or null
     * when it succeeded. Warnings (e.g. a merge conflict) are not errors.
     */
    fun run(project: Project, handler: CvsHandler, onMessage: (String) -> Unit = {}): String? {
        handler.addCvsListener(object : CvsMessagesAdapter() {
            // cvs 1.11 sends its "NOT MOVING" warnings as ordinary (M) lines, not on the error stream.
            override fun addMessage(event: MessageEvent) {
                if (event.message.isNotBlank()) onMessage(event.message)
            }
        })
        val executor = CvsOperationExecutor(false, project, ModalityState.nonModal())
        executor.setShowErrors(false)
        executor.setIsQuietOperation(true)
        val perform = { executor.performActionSync(handler, CvsOperationExecutorCallback.EMPTY) }
        if (ProgressManager.getInstance().progressIndicator == null) {
            ProgressManager.getInstance().runProcess(perform, EmptyProgressIndicator())
        } else {
            perform()
        }
        val errors = executor.result.errors.filter { !it.isWarning }
        if (errors.isEmpty()) return null
        return errors.joinToString("; ") { error -> error.message?.takeIf { it.isNotBlank() } ?: error.toString() }
    }

    /** Reloads the working copy [root] from disk and marks it dirty for the change list. */
    fun refresh(project: Project, root: Path) {
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: return
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)
        if (!project.isDisposed) VcsDirtyScopeManager.getInstance(project).dirDirtyRecursively(dir)
    }

    /** Shows [report] as one notification of the plugin's group (design decision 9). */
    fun notify(project: Project, report: BranchReport) {
        val type = when {
            report.failed.isNotEmpty() -> NotificationType.ERROR
            report.conflicts.isNotEmpty() -> NotificationType.WARNING
            else -> NotificationType.INFORMATION
        }
        NotificationGroupManager.getInstance().getNotificationGroup("LibreCVS")
            .createNotification(report.title, StringUtil.escapeXmlEntities(report.text()).replace("\n", "<br>"), type)
            .notify(project)
    }
}
