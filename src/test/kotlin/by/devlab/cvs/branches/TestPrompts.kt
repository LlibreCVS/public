package by.devlab.cvs.branches

import com.intellij.openapi.project.Project

/** [BranchPrompts] for tests: records every question and answers it as told; [onAsk] runs when a question comes. */
class TestPrompts(var confirmSwitch: Boolean = true, var newBranch: NewBranchRequest? = null) : BranchPrompts {

    /** The questions asked, as text. */
    val asked = mutableListOf<String>()

    var onAsk: () -> Unit = {}

    override fun confirmSwitch(project: Project, target: String, changes: Map<String, Int>): Boolean {
        asked += "switch to $target: $changes"
        onAsk()
        return confirmSwitch
    }

    override fun askNewBranch(project: Project, roots: List<String>): NewBranchRequest? {
        asked += "new branch in $roots"
        onAsk()
        return newBranch
    }
}
