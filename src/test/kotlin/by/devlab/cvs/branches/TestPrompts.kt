package by.devlab.cvs.branches

import com.intellij.openapi.project.Project

/** [BranchPrompts] for tests: records every question and answers it as told. */
class TestPrompts(var confirmSwitch: Boolean = true) : BranchPrompts {

    /** The questions asked, as text. */
    val asked = mutableListOf<String>()

    override fun confirmSwitch(project: Project, target: String, changes: Map<String, Int>): Boolean {
        asked += "switch to $target: $changes"
        return confirmSwitch
    }
}
