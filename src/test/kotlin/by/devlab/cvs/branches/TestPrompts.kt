package by.devlab.cvs.branches

import com.intellij.openapi.project.Project

/** [BranchPrompts] for tests: records every question and answers it as told; [onAsk] runs when a question comes. */
class TestPrompts(
    var confirmSwitch: Boolean = true,
    var newBranch: NewBranchRequest? = null,
    var existing: ExistingBranchChoice = ExistingBranchChoice.CANCEL,
    var confirmRecreate: Boolean = false,
    var partial: PartialBranchChoice = PartialBranchChoice.SWITCH,
    var confirmMerge: Boolean = true,
) : BranchPrompts {

    /** The questions asked, as text. */
    val asked = mutableListOf<String>()

    var onAsk: () -> Unit = {}

    override fun confirmSwitch(project: Project, target: String, changes: Map<String, Int>): Boolean {
        asked += "switch to $target: $changes"
        onAsk()
        return confirmSwitch
    }

    override fun confirmMerge(project: Project, source: String, changes: Map<String, Int>): Boolean {
        asked += "merge $source: $changes"
        onAsk()
        return confirmMerge
    }

    override fun confirmPartialBranch(
        project: Project,
        branch: String,
        coverage: Map<String, BranchListService.Coverage>,
    ): PartialBranchChoice {
        asked += "partial $branch: ${coverage.mapValues { (_, it) -> "${it.files} of ${it.total}" }}"
        onAsk()
        return partial
    }

    override fun askNewBranch(project: Project, roots: List<String>): NewBranchRequest? {
        asked += "new branch in $roots"
        onAsk()
        return newBranch
    }

    override fun askExistingBranch(project: Project, branch: String, roots: List<String>): ExistingBranchChoice {
        asked += "existing $branch in $roots"
        onAsk()
        return existing
    }

    override fun confirmRecreate(project: Project, branch: String, warning: String): Boolean {
        asked += "recreate $branch: $warning"
        onAsk()
        return confirmRecreate
    }
}
