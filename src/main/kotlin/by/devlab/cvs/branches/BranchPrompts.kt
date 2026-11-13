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

    /** The new branch's name and whether to switch to it, for [roots] (root names), or null when cancelled. */
    fun askNewBranch(project: Project, roots: List<String>): NewBranchRequest?

    companion object {
        val UI: BranchPrompts = DialogPrompts
    }
}

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

    override fun askNewBranch(project: Project, roots: List<String>): NewBranchRequest? = onUiThread {
        val dialog = NewBranchDialog(project, roots)
        if (dialog.showAndGet()) NewBranchRequest(dialog.branchName, dialog.switchToBranch) else null
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
