package by.devlab.cvs.branches

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

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

    companion object {
        val UI: BranchPrompts = DialogPrompts
    }
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

    /** Runs [ask] on the UI thread and waits for its answer. */
    fun <T> onUiThread(ask: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return ask()
        var answer: Result<T>? = null
        application.invokeAndWait { answer = runCatching(ask) }
        return answer!!.getOrThrow()
    }
}
