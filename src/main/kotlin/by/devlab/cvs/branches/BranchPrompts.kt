package by.devlab.cvs.branches

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import by.devlab.cvs.tagging.validateTagName
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/**
 * The questions the branch actions ask the user (design decision 15): the production implementation shows dialogs,
 * tests answer them. Called from any thread; the dialogs are shown on the UI thread.
 */
interface BranchPrompts {

    /**
     * Whether to switch to [target] although the roots in [changes] (root name → number of changed files) have
     * uncommitted changes, which CVS carries over and may merge with conflicts (design decision 8).
     */
    fun confirmSwitch(project: Project, target: String, changes: Map<String, Int>): Boolean

    /**
     * Whether to merge [source] although the roots in [changes] (root name → number of changed files) have uncommitted
     * changes, which the merge mixes with its own; the roots are also updated (cvs-branch-merge decisions 6, 13).
     */
    fun confirmMerge(project: Project, source: String, changes: Map<String, Int>): Boolean

    /**
     * What to do with a switch to [branch], which covers only part of the roots in [coverage] (root name → its files
     * with the branch and all its files): the other files would leave the working copy (design decision 20).
     */
    fun confirmPartialBranch(project: Project, branch: String, coverage: Map<String, BranchListService.Coverage>): PartialBranchChoice

    /** The new branch's name and whether to switch to it, for [roots] (root names), or null when cancelled. */
    fun askNewBranch(project: Project, roots: List<String>): NewBranchRequest?

    /** What to do with [branch], already in the lists of [roots] (root names): use, recreate or cancel (decision 10). */
    fun askExistingBranch(project: Project, branch: String, roots: List<String>): ExistingBranchChoice

    /** The second confirmation of Recreate: [warning] names the roots and what happens to commits on [branch]. */
    fun confirmRecreate(project: Project, branch: String, warning: String): Boolean

    companion object {
        val UI: BranchPrompts = DialogPrompts
    }
}

/** The answer to a new branch name that some roots already have (design decision 10). */
enum class ExistingBranchChoice { USE, RECREATE, CANCEL }

/** The answer to a branch that covers only part of some roots (design decision 20). */
enum class PartialBranchChoice { SWITCH, SKIP, CANCEL }

/** What New Branch… asks: the branch [name] (trimmed) and whether to [switch] the roots to it. */
data class NewBranchRequest(val name: String, val switch: Boolean)

/**
 * Asks for a new branch (design decision 10): its name, checked with [validateTagName] while typing, and "Switch to the
 * branch" (on by default), naming the roots. It has no option to override an existing branch.
 */
private class NewBranchDialog(project: Project, roots: List<String>) : DialogWrapper(project) {
    private val nameField = JBTextField(20)
    private val switchBox = JBCheckBox("Switch to the branch", true)
    private val rootList = JBList(roots)

    init {
        title = "New CVS Branch"
        init()
        initValidation()
    }

    val branchName: String get() = nameField.text.trim()

    val switchToBranch: Boolean get() = switchBox.isSelected

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Branch name:", nameField)
        .addComponent(switchBox)
        .addLabeledComponentFillVertically("Roots:", JBScrollPane(rootList))
        .panel

    override fun doValidate(): ValidationInfo? =
        validateTagName(nameField.text)?.replace("tag name", "branch name")?.let { ValidationInfo(it, nameField) }
}

/** The dialogs of [BranchPrompts]. */
private object DialogPrompts : BranchPrompts {

    override fun confirmSwitch(project: Project, target: String, changes: Map<String, Int>): Boolean = onUiThread {
        val roots = changes.entries.joinToString("\n") { (root, count) -> "$root: $count changed ${if (count == 1) "file" else "files"}" }
        Messages.showOkCancelDialog(
            project,
            "These roots have uncommitted changes:\n$roots\n\nCVS carries them over to $target and may merge them with conflicts.",
            "Switch to $target",
            "Switch",
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        ) == Messages.OK
    }

    override fun confirmMerge(project: Project, source: String, changes: Map<String, Int>): Boolean =
        throw NotImplementedError()

    override fun confirmPartialBranch(
        project: Project,
        branch: String,
        coverage: Map<String, BranchListService.Coverage>,
    ): PartialBranchChoice = onUiThread {
        val roots = coverage.entries.joinToString("\n") { (root, it) -> "$root: ${it.files} of ${it.total} files" }
        val answer = Messages.showDialog(
            project,
            "Branch $branch covers only part of these roots:\n$roots\n\n" +
                "Switching them removes their other files from the working copy. The files stay in the repository and " +
                "come back with Back to Trunk.",
            "Switch to $branch",
            arrayOf("Switch", "Skip These Roots", Messages.getCancelButton()),
            // Switch by default: the user already chose the branch, the question only warns (design decision 23).
            0,
            Messages.getWarningIcon(),
        )
        when (answer) {
            0 -> PartialBranchChoice.SWITCH
            1 -> PartialBranchChoice.SKIP
            else -> PartialBranchChoice.CANCEL
        }
    }

    override fun askNewBranch(project: Project, roots: List<String>): NewBranchRequest? = onUiThread {
        val dialog = NewBranchDialog(project, roots)
        if (dialog.showAndGet()) NewBranchRequest(dialog.branchName, dialog.switchToBranch) else null
    }

    override fun askExistingBranch(project: Project, branch: String, roots: List<String>): ExistingBranchChoice = onUiThread {
        val answer = Messages.showDialog(
            project,
            "Branch $branch already exists in:\n${roots.joinToString("\n")}\n\n" +
                "Use Existing creates it only in the other roots. Recreate moves the existing branch to the revisions " +
                "these roots hold.",
            "Branch $branch Exists",
            arrayOf("Use Existing", "Recreate…", Messages.getCancelButton()),
            0,
            Messages.getQuestionIcon(),
        )
        when (answer) {
            0 -> ExistingBranchChoice.USE
            1 -> ExistingBranchChoice.RECREATE
            else -> ExistingBranchChoice.CANCEL
        }
    }

    override fun confirmRecreate(project: Project, branch: String, warning: String): Boolean = onUiThread {
        Messages.showOkCancelDialog(
            project, warning, "Recreate Branch $branch", "Recreate", Messages.getCancelButton(), Messages.getWarningIcon(),
        ) == Messages.OK
    }

    /** Runs [ask] on the UI thread and waits for its answer. */
    fun <T> onUiThread(ask: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return ask()
        var answer: Result<T>? = null
        application.invokeAndWait { answer = runCatching(ask) }
        return answer!!.getOrThrow()
    }
}
