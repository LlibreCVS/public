package by.devlab.cvs.branches

import com.intellij.openapi.project.Project
import java.nio.file.Path

/** Creates a branch in roots (design decisions 5, 10, 11, 14). */
class BranchCreator(private val project: Project, private val prompts: BranchPrompts = BranchPrompts.UI) {

    fun create(roots: List<Path>): BranchReport? = throw NotImplementedError()

    fun start(roots: List<Path>): Unit = throw NotImplementedError()
}
