package by.devlab.cvs.branches

import com.intellij.openapi.project.Project
import java.nio.file.Path

/** What choosing an entry of the branch popup does, and over which roots. */
sealed interface BranchChoice {
    /** Switch [roots] to [branch]. */
    data class Switch(val branch: String, val roots: List<Path>) : BranchChoice

    /** Put [roots] back on the trunk. */
    data class Trunk(val roots: List<Path>) : BranchChoice

    /** Create a branch in [roots]. */
    data class NewBranch(val roots: List<Path>) : BranchChoice

    /** Load the branch lists of [roots] again. */
    data class Refresh(val roots: List<Path>) : BranchChoice
}

/** The entries of the branch popup as a plain structure (design decisions 6, 15, 16). */
class BranchPopupModel(val entries: List<Entry>, val loading: List<Path>) {

    sealed interface Entry

    /** An entry; choosing it does [choice], or nothing when [choice] is null. [current] marks the current branch. */
    data class Item(val text: String, val choice: BranchChoice?, val current: Boolean = false, val enabled: Boolean = true) : Entry

    /** A root's submenu. */
    data class Submenu(val text: String, val entries: List<Entry>) : Entry

    /** A separator, with an optional title. */
    data class Separator(val text: String? = null) : Entry

    fun render(): String = throw NotImplementedError()

    companion object {
        fun build(project: Project, opening: Boolean): BranchPopupModel = throw NotImplementedError()
    }
}
