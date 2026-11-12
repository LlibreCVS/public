package by.devlab.cvs.branches

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.vcs.branch.BranchData
import com.intellij.vcs.branch.BranchStateProvider

/** Names the CVS branch of the root that holds a file, for the commit window (design decision 3). */
class CvsBranchStateProvider(private val project: Project) : BranchStateProvider {

    override fun getCurrentBranch(path: FilePath): BranchData? = TODO()
}
