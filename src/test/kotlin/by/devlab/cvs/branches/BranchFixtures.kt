package by.devlab.cvs.branches

import by.devlab.vcs.FakeWorkingCopy
import com.intellij.cvsSupport2.CvsVcs2
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import kotlin.io.path.createDirectories

/** Shared setup of the branch-indicator tests: fake working copies mapped to CVS in a test project. */
object BranchFixtures {

    const val REPOSITORY = ":pserver:anonymous@cvs.example.invalid:/cvsroot"

    /** A fake working copy [name] under [parent] of module [name], on [tag] (a `CVS/Tag` line) or the trunk. */
    fun workingCopy(parent: Path, name: String, tag: String? = null): Path {
        val root = FakeWorkingCopy.write(parent.resolve(name).createDirectories(), REPOSITORY, name)
        if (tag != null) FakeWorkingCopy.tag(root, tag)
        return root
    }

    /** Replaces the CVS mappings of [project] with [dirs]. */
    fun mapToCvs(project: Project, vararg dirs: Path) {
        ProjectLevelVcsManager.getInstance(project).directoryMappings =
            dirs.map { VcsDirectoryMapping(virtualFile(it).path, CvsVcs2.getKey().name) }
    }

    /** The VFS file for [path], refreshed from disk first. */
    fun virtualFile(path: Path): VirtualFile =
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)) { "no VFS file for $path" }
            .also { it.refresh(false, it.isDirectory) }

    /** The project's branch state service after a full refresh. */
    fun refreshed(project: Project): BranchStateService =
        BranchStateService.getInstance(project).apply {
            refresh()
            awaitRefresh()
        }
}
