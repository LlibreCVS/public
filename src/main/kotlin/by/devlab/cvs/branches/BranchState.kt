package by.devlab.cvs.branches

import java.nio.file.Path

/** What a CVS working-copy root is on, as its root directory's `CVS/Tag` records it (design decision 6). */
enum class BranchKind { TRUNK, BRANCH, TAG, DATE }

/**
 * The state of one CVS working-copy root: the trunk, a branch, a plain tag or a date. [name] is the branch or tag
 * name, or the date as the indicator shows it; it is empty on the trunk.
 */
data class BranchState(val kind: BranchKind, val name: String) {

    /** The indicator's text: `trunk`, the branch name, `<tag> (tag)` or `<date> (date)`. */
    val text: String get() = TODO()

    /** The tooltip line for this state; on a tag or a date it says that a commit is not possible there. */
    val tooltip: String get() = TODO()

    companion object {
        val TRUNK = BranchState(BranchKind.TRUNK, "")

        /** Reads the state of the working-copy root [root] from its `CVS/Tag`. Reads only that file. */
        fun read(root: Path): BranchState = TODO()
    }
}
