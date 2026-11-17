package by.devlab.cvs.branches

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Deletes a branch and its merge-point tags from the roots' modules (cvs-branch-delete decisions 3-15): plans each root
 * locally (deleted, or skipped with the reason), deletes the branch tag with `rtag -d -B` on each remaining root's
 * module, removes the branch from the remembered lists and reports in one notification. A failure in one root does not
 * stop the others; nothing is rolled back.
 */
class BranchDeleter(private val project: Project, private val prompts: BranchPrompts = BranchPrompts.UI) {

    /** One root of a deletion: deleted in, or skipped for [skip]. */
    data class Step(val root: Path, val name: String, val skip: String?)

    /**
     * Each root of [roots] deleted in or skipped (decisions 6, 15), from the working copies and the remembered state
     * only: skipped when its list lacks [branch], when it is on the branch, has files on another branch or has an
     * unfinished merge of the branch, or when its module equals, contains or lies inside the module of a project root
     * (chosen or not) that would be skipped for one of those reasons.
     */
    fun plan(branch: String, roots: List<Path>): List<Step> {
        val lists = BranchListService.getInstance(project)
        val projectRoots = (BranchStateService.getInstance(project).roots().map { it.path } + roots).distinctBy(::normalized)
        val protecting = projectRoots.mapNotNull { root -> protection(root, branch)?.let { root to it } }
        return roots.map { root ->
            val name = BranchSwitcher.rootName(root)
            val skip = if (lists.list(root)?.branches?.contains(branch) != true) {
                "lacks the branch $branch"
            } else {
                protection(root, branch) ?: run {
                    val location = CvsTags.moduleLocation(root)
                    protecting.firstOrNull { (other, _) -> normalized(other) != normalized(root) && overlap(location, CvsTags.moduleLocation(other)) }
                        ?.let { (other, why) -> "shares its module with ${BranchSwitcher.rootName(other)}, which ${which(why)}" }
                }
            }
            Step(root, name, skip)
        }
    }

    /** Deletes [branch] in [roots] on the calling thread (never the UI thread), shows the report and returns it. */
    fun delete(branch: String, roots: List<Path>): BranchReport? {
        val steps = plan(branch, roots)
        val deleted = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        for (step in steps.filter { it.skip == null }) {
            val error = try {
                CvsTags.deleteBranch(project, step.root, branch)
            } catch (e: Exception) {
                // A cancel or another control-flow exception ends the operation; it is not a root's failure.
                if (e is ControlFlowException) throw e
                e.message ?: e.toString()
            }
            if (error != null) {
                failed += step.name to error
                continue
            }
            forgetBranch(step.root, branch)
            deleted += step.name
        }
        val report = BranchReport(
            "Delete branch $branch", deleted, steps.mapNotNull { step -> step.skip?.let { step.name to it } },
            emptyList(), failed, switchedLabel = "Deleted",
        )
        CvsRunner.notify(project, report)
        return report
    }

    /** Deletes [branch] in a background task as the project's branch operation (decision 9). */
    fun start(branch: String, roots: List<Path>) {
        BranchOperations.getInstance(project).queue("Deleting CVS branch $branch") { delete(branch, roots) }
    }

    /** Removes [branch] from the list of [root] and of every project root sharing its module (decision 15). */
    private fun forgetBranch(root: Path, branch: String) {
        val lists = BranchListService.getInstance(project)
        val location = CvsTags.moduleLocation(root)
        lists.remove(root, branch)
        for (other in BranchStateService.getInstance(project).roots().map { it.path }) {
            if (normalized(other) != normalized(root) && overlap(location, CvsTags.moduleLocation(other))) lists.remove(other, branch)
        }
    }

    /** Why [root] itself must keep [branch]: on it, files on another branch, or an unfinished merge of it; else null. */
    private fun protection(root: Path, branch: String): String? {
        val merge = MergeService.getInstance(project).unfinished(root)
        return when {
            BranchState.read(root) == BranchState(BranchKind.BRANCH, branch) -> "on $branch"
            MixedScan.count(root) > 0 -> "has files on another branch"
            merge != null && (merge.source == branch || merge.target == branch) -> "unfinished merge of ${merge.source} into ${merge.target}"
            else -> null
        }
    }

    private companion object {
        /** A protection reason as the end of "which …": `is on X`, `has files …`, `has an unfinished merge …`. */
        fun which(reason: String): String = when {
            reason.startsWith("on ") -> "is $reason"
            reason.startsWith("unfinished") -> "has an $reason"
            else -> reason
        }

        /** Whether two module locations are equal or one contains the other. */
        fun overlap(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            return a == b || a.startsWith("$b/") || b.startsWith("$a/")
        }

        fun normalized(path: Path): Path = path.toAbsolutePath().normalize()
    }
}
