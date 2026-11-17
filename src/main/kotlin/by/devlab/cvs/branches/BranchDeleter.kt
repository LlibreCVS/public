package by.devlab.cvs.branches

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Deletes a branch and its merge-point tags from the roots' modules (cvs-branch-delete decisions 3-15): plans each root
 * locally (deleted, or skipped with the reason), reads the branch in each remaining root's module, asks twice, then
 * deletes the branch tag with `rtag -d -B` and its merge-point tags with `rtag -d` on each module, removes the branch
 * from the remembered lists and reports in one notification. A failure in one root does not
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
        val alike = roots.groupBy(BranchSwitcher::rootName).filterValues { it.size > 1 }.keys
        return roots.map { root ->
            // Two checkouts named alike stay apart in the question and the report: `<parent>/<name>`.
            val name = BranchSwitcher.rootName(root).let { if (it in alike) "${root.parent?.fileName}/$it" else it }
            val skip = if (lists.list(root)?.branches?.contains(branch) != true) {
                "lacks the branch $branch"
            } else {
                protection(root, branch) ?: run {
                    val location = CvsTags.moduleLocation(root)
                    // A protecting root whose module cannot be read protects every module (conservative).
                    protecting.firstOrNull { (other, _) ->
                        normalized(other) != normalized(root) && overlap(location, CvsTags.moduleLocation(other), unknownOverlaps = true)
                    }?.let { (other, why) -> "shares its module with ${BranchSwitcher.rootName(other)}, which ${which(why)}" }
                }
            }
            Step(root, name, skip)
        }
    }

    /** What the branch holds in one root's module: files with [unmerged] changes and the merge-point [tags] (decision 5). */
    data class Analysis(val unmerged: Int, val tags: List<String>)

    /**
     * Reads [branch] in the whole module of [root] with one read-only `rlog -r<branch>` (decisions 4, 5, 14): a file
     * counts as unmerged when it has revisions on the branch and its latest one (a dead one too) is not the revision of
     * any `<BR>_MERGED_<X>` tag on that file; the tags are every `<BR>_MERG(ED|ING)_<X>` and `<X>_MERG(ED|ING)_<BR>`
     * of the module. Throws on a CVS error.
     */
    fun analyse(root: Path, branch: String): Analysis {
        val outgoing = Regex("^${Regex.escape(branch)}_MERG(ED|ING)_.+$")
        val incoming = Regex("^.+_MERG(ED|ING)_${Regex.escape(branch)}$")
        val mergedTag = Regex("^${Regex.escape(branch)}_MERGED_.+$")
        val tags = sortedSetOf<String>()
        var unmerged = 0
        for (file in CvsTags.branchLog(project, root, branch)) {
            val names = file.allSymbolicNames
            names.forEach { if (outgoing.matches(it.name) || incoming.matches(it.name)) tags += it.name }
            val branchNumber = names.firstOrNull { it.name == branch }?.revision ?: continue
            val head = branchRevisions(branchNumber, file.revisionList.map { it.number }).maxWithOrNull(REVISION_ORDER) ?: continue
            if (names.none { mergedTag.matches(it.name) && it.revision == head }) unmerged++
        }
        return Analysis(unmerged, tags.toList())
    }

    /** Deletes [branch] in [roots] on the calling thread (never the UI thread), shows the report and returns it. */
    fun delete(branch: String, roots: List<Path>): BranchReport? {
        val steps = plan(branch, roots)
        val deleted = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        val skipped = steps.mapNotNull { step -> step.skip?.let { step.name to it } }
        // The server is read before any question; a root that cannot be read is not deleted in (decision 11).
        val analysed = LinkedHashMap<Step, Analysis>()
        for (step in steps.filter { it.skip == null }) {
            try {
                analysed[step] = analyse(step.root, branch)
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                failed += step.name to (e.message ?: e.toString())
            }
        }
        if (analysed.isNotEmpty()) {
            val summary = DeleteBranchSummary(
                analysed.entries.associate { (step, analysis) -> step.name to analysis.unmerged },
                skipped, failed.toList(), analysed.values.flatMap { it.tags }.distinct().sorted(),
            )
            if (!prompts.confirmDeleteBranch(project, branch, summary)) return null
            if (!prompts.confirmDeleteBranchIrreversible(project, branch, irreversibleWarning(branch))) return null
        }
        val notes = ArrayList<String>()
        val done = ArrayList<Path>()
        val failedRoots = steps.filter { step -> failed.any { it.first == step.name } }.mapTo(HashSet()) { normalized(it.root) }
        for ((step, analysis) in analysed) {
            // The branch tag first: when it fails, none of the root's tags is touched (decision 10).
            val error = guarded { CvsTags.deleteBranch(project, step.root, branch) }
            if (error != null) {
                failed += step.name to error
                failedRoots.add(normalized(step.root))
                continue
            }
            for (tag in analysis.tags) {
                guarded { CvsTags.deleteTag(project, step.root, tag) }?.let { notes += "${step.name}: cannot delete $tag: $it" }
            }
            done.add(step.root)
            deleted += step.name
        }
        // The lists change once every root is done, so that a failed root keeps the branch in its list (decision 11).
        done.forEach { forgetBranch(it, branch, failedRoots) }
        val report = BranchReport(
            "Delete branch $branch", deleted, skipped, emptyList(), failed, switchedLabel = "Deleted", notes = notes,
        )
        CvsRunner.notify(project, report)
        return report
    }

    /** Runs [rtag] and returns its error, or the message of what it threw; control-flow exceptions end the operation. */
    private fun guarded(rtag: () -> String?): String? = try {
        rtag()
    } catch (e: Exception) {
        if (e is ControlFlowException) throw e
        e.message ?: e.toString()
    }

    /** Deletes [branch] in a background task as the project's branch operation (decision 9). */
    fun start(branch: String, roots: List<Path>) {
        BranchOperations.getInstance(project).queue("Deleting CVS branch $branch") { delete(branch, roots) }
    }

    /**
     * Removes [branch] from the list of [root] and of every project root sharing its module (decision 15), except the
     * roots in [failed].
     */
    private fun forgetBranch(root: Path, branch: String, failed: Set<Path>) {
        val lists = BranchListService.getInstance(project)
        val location = CvsTags.moduleLocation(root)
        lists.remove(root, branch)
        for (other in BranchStateService.getInstance(project).roots().map { it.path }) {
            if (normalized(other) == normalized(root) || normalized(other) in failed) continue
            if (overlap(location, CvsTags.moduleLocation(other))) lists.remove(other, branch)
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

    internal companion object {
        /** Revision numbers compared component by component. */
        val REVISION_ORDER: Comparator<String> = Comparator { a, b ->
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            x.zip(y).firstOrNull { (p, q) -> p != q }?.let { (p, q) -> p.compareTo(q) } ?: x.size.compareTo(y.size)
        }

        /**
         * The [revisions] on the branch whose tag holds [branchNumber]: `1.2.0.4` (a magic branch number) means
         * revisions `1.2.4.N`; `rlog -r<BR>` may also list the branch point, which is not on the branch.
         */
        fun branchRevisions(branchNumber: String, revisions: List<String>): List<String> {
            val parts = branchNumber.split('.')
            val branchPrefix = if (parts.size >= 4 && parts[parts.size - 2] == "0") {
                (parts.dropLast(2) + parts.last()).joinToString(".")
            } else {
                branchNumber
            }
            val depth = branchPrefix.count { it == '.' } + 1
            return revisions.filter { it.startsWith("$branchPrefix.") && it.count { c -> c == '.' } == depth }
        }

        private fun irreversibleWarning(branch: String): String =
            "Deleting branch $branch cannot be undone. The branch name and its merge-point tags leave the repository " +
                "for the whole team; the branch's revisions stay without a name.\n\n" +
                "Working copies on $branch, also your teammates', stop updating and committing on it."

        /** A protection reason as the end of "which …": `is on X`, `has files …`, `has an unfinished merge …`. */
        private fun which(reason: String): String = when {
            reason.startsWith("on ") -> "is $reason"
            reason.startsWith("unfinished") -> "has an $reason"
            else -> reason
        }

        /**
         * Whether two module locations ([CvsTags.locationKey]) are equal or one contains the other; an unknown location
         * overlaps everything when [unknownOverlaps], nothing otherwise.
         */
        fun overlap(a: String?, b: String?, unknownOverlaps: Boolean = false): Boolean {
            if (a == null || b == null) return unknownOverlaps
            return a == b || a.startsWith("$b/") || b.startsWith("$a/")
        }

        private fun normalized(path: Path): Path = path.toAbsolutePath().normalize()
    }
}
