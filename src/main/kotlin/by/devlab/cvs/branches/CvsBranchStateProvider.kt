package by.devlab.cvs.branches

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.vcs.branch.BranchData
import com.intellij.vcs.branch.BranchStateProvider

/**
 * Names the CVS branch of the root that holds a file, for the commit window (design decision 3): the root's name and
 * its state in the indicator's words (`trunk`, the branch, `<tag> (tag)`, `<date> (date)`). It reads only the cache.
 */
class CvsBranchStateProvider(private val project: Project) : BranchStateProvider {

    override fun getCurrentBranch(path: FilePath): BranchData? {
        if (project.isDisposed) return null
        val root = BranchStateService.getInstance(project).rootFor(path.path) ?: return null
        return CvsBranchData(root.name, root.status.state.text)
    }

    private data class CvsBranchData(private val rootName: String, private val branch: String) : BranchData {
        override fun getPresentableRootName(): String = rootName
        override fun getBranchName(): String = branch
    }
}
