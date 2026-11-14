package by.devlab.cvs.branches

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.vcsUtil.VcsUtil
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val LOG = logger<LocalChanges>()

/** The IDE's change list as the branch actions read it: locally, without contacting the server (design decision 8). */
internal object LocalChanges {

    /**
     * The changed files of each of [roots] (absolute paths, distinct), from the IDE's change list after it took in the
     * current disk state. Call it off the UI thread.
     */
    fun files(project: Project, roots: List<Path>): Map<Path, List<Path>> {
        if (roots.isEmpty()) return emptyMap()
        val manager = ChangeListManager.getInstance(project)
        roots.forEach { CvsRunner.refresh(project, it) }
        val updated = CountDownLatch(1)
        manager.invokeAfterUpdate(false) { updated.countDown() }
        if (!updated.await(2, TimeUnit.MINUTES)) LOG.warn("The change list did not update in time; counting what it has")
        return roots.associateWith { root ->
            manager.getChangesIn(VcsUtil.getFilePath(root.toFile(), true))
                .mapNotNull { (it.afterRevision ?: it.beforeRevision)?.file?.ioFile?.toPath() }
                .distinct()
        }
    }
}
