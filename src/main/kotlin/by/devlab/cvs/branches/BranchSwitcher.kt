package by.devlab.cvs.branches

import com.intellij.openapi.project.Project
import java.nio.file.Path

/** Where a switch takes the roots: a branch or the trunk. */
sealed interface SwitchTarget {
    val text: String

    data class Branch(val name: String) : SwitchTarget {
        override val text: String get() = name
    }

    data object Trunk : SwitchTarget {
        override val text: String get() = "the trunk"
    }
}

/**
 * What an action over the roots did, for its one notification (design decision 9): the roots switched (or given the
 * branch), the roots skipped with the reason, the files left with conflicts and the roots that failed with the error.
 */
data class BranchReport(
    val title: String,
    val switched: List<String>,
    val skipped: List<Pair<String, String>>,
    val conflicts: List<String>,
    val failed: List<Pair<String, String>>,
) {
    fun text(): String = throw NotImplementedError()
}

/** Switches roots to a branch or back to the trunk (design decisions 7, 8, 9, 14, 19). */
class BranchSwitcher(private val project: Project, private val prompts: BranchPrompts = BranchPrompts.UI) {

    /** One root of a switch: switched, or skipped for [skip]. */
    data class Step(val root: Path, val name: String, val skip: String?)

    fun plan(target: SwitchTarget, roots: List<Path>): List<Step> = throw NotImplementedError()

    fun switch(target: SwitchTarget, roots: List<Path>): BranchReport? = throw NotImplementedError()

    fun start(target: SwitchTarget, roots: List<Path>): Unit = throw NotImplementedError()
}
