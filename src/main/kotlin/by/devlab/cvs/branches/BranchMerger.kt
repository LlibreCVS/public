package by.devlab.cvs.branches

import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Merges a branch or the trunk into what each root is on now, with merge-point tags (cvs-branch-merge decisions 3, 4,
 * 5, 11, 13, 14, 16).
 */
class BranchMerger(private val project: Project, private val prompts: BranchPrompts = BranchPrompts.UI) {

    /** Merges [source] into [roots] on the calling thread (never the UI thread), shows the report and returns it. */
    fun merge(source: SwitchTarget, roots: List<Path>): BranchReport? = throw NotImplementedError()

    /** Merges in a background task as the project's branch operation. */
    fun start(source: SwitchTarget, roots: List<Path>): Unit = throw NotImplementedError()
}
