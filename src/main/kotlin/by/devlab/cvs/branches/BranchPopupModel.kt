package by.devlab.cvs.branches

import com.intellij.openapi.project.Project
import com.intellij.util.text.DateFormatUtil
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

    /** Merge [source], a branch or the trunk, into what each of [roots] is on now (cvs-branch-merge decision 10). */
    data class Merge(val source: SwitchTarget, val roots: List<Path>) : BranchChoice

    /** Move the merge point of [root]'s unfinished merge now (cvs-branch-merge decision 8). */
    data class CompleteMerge(val root: Path) : BranchChoice

    /** Forget [root]'s unfinished merge without moving its merge point (cvs-branch-merge decision 8). */
    data class ForgetMerge(val root: Path) : BranchChoice
}

/**
 * The entries of the branch popup as a plain structure (design decisions 6, 15, 16): built from the roots of
 * [BranchStateService] and the remembered lists of [BranchListService], read by tests without showing UI and turned
 * into actions by [BranchPopup]. [loading] holds the roots whose list was loading when the model was built.
 */
class BranchPopupModel(val entries: List<Entry>, val loading: List<Path>) {

    sealed interface Entry

    /** An entry; choosing it does [choice], or nothing when [choice] is null. [current] marks the current branch. */
    data class Item(val text: String, val choice: BranchChoice?, val current: Boolean = false, val enabled: Boolean = true) : Entry

    /** A root's or a branch's submenu; [current] marks the current branch. */
    data class Submenu(val text: String, val entries: List<Entry>, val current: Boolean = false) : Entry

    /** A separator, with an optional title. */
    data class Separator(val text: String? = null) : Entry

    /** The entries as indented text, one per line: `[current]` and `[disabled]` marks, `>` after a submenu. */
    fun render(): String = buildString { render(entries, "") }

    private fun StringBuilder.render(entries: List<Entry>, indent: String) {
        for (entry in entries) {
            when (entry) {
                is Item -> {
                    append(indent).append(entry.text)
                    if (entry.current) append(" [current]")
                    if (!entry.enabled) append(" [disabled]")
                    append('\n')
                }
                is Submenu -> {
                    append(indent).append(entry.text)
                    if (entry.current) append(" [current]")
                    append(" >\n")
                    render(entry.entries, "$indent  ")
                }
                is Separator -> append(indent).append("---").append(entry.text?.let { " $it" }.orEmpty()).append('\n')
            }
        }
    }

    companion object {
        const val NEW_BRANCH = "New Branch…"
        const val BACK_TO_TRUNK = "Back to Trunk"
        const val MERGE_TRUNK = "Merge Trunk into Current"
        const val SWITCH = "Switch"
        const val MERGE_INTO_CURRENT = "Merge into Current"
        const val COMPLETE_MERGE = "Complete Merge"
        const val FORGET_MERGE = "Forget Merge"
        const val LOADING = "Loading branches…"
        const val REFRESH = "Refresh Branch List"

        /**
         * The popup's entries for the project's CVS roots. A root without a remembered list gets its load started
         * (design decision 5); when the popup is being [opening] by the user that includes a root whose last load failed,
         * otherwise (a popup rebuilt after a load) a failed root is not loaded again, so that a failure never loops.
         */
        fun build(project: Project, opening: Boolean): BranchPopupModel {
            val roots = BranchStateService.getInstance(project).roots()
            val service = BranchListService.getInstance(project)
            val toLoad = roots.map { it.path }.filter { path ->
                service.list(path) == null && !service.isLoading(path) && (opening || service.error(path) == null)
            }
            if (toLoad.isNotEmpty()) service.load(toLoad)
            val loading = roots.map { it.path }.filter { service.isLoading(it) }
            val lists = roots.associate { it.path to service.list(it.path) }
            // While a branch operation runs, only Refresh Branch List can be chosen (design decision 21).
            val busy = BranchOperations.getInstance(project).isRunning
            // The unfinished merges are re-checked for rolled-back files first (cvs-branch-merge decision 9), locally.
            val merges = MergeService.getInstance(project).apply { refresh(fresh = false) }

            val entries = ArrayList<Entry>()
            if (busy) entries += Item(BranchOperations.IN_PROGRESS, null, enabled = false)
            if (roots.size == 1) {
                val root = roots.single()
                entries += mergeEntries(merges, root.path, busy)
                entries += rootEntries(roots, lists[root.path]?.branches.orEmpty(), busy)
                entries += statusEntries(service, root.path, root.path in loading, lists[root.path] != null)
            } else {
                // Only branches every root's list has go to the top level (design decision 6, option A).
                val common = if (lists.values.any { it == null }) {
                    emptyList()
                } else {
                    lists.values.map { it!!.branches.toSet() }.reduceOrNull { all, next -> all intersect next }.orEmpty().sorted()
                }
                entries += rootEntries(roots, common, busy)
                if (loading.isNotEmpty()) entries += Item(LOADING, null, enabled = false)
                entries += Separator("Roots")
                for (root in roots) {
                    val list = lists[root.path]?.branches.orEmpty()
                    entries += Submenu("${root.name}: ${root.status.text}", mergeEntries(merges, root.path, busy) +
                        rootEntries(listOf(root), list, busy) +
                        statusEntries(service, root.path, root.path in loading, lists[root.path] != null))
                }
            }
            entries += Separator()
            val loadedAt = lists.values.filterNotNull().minOfOrNull { it.loadedAt }
            val refresh = if (loadedAt == null) REFRESH else "$REFRESH (updated ${DateFormatUtil.formatPrettyDateTime(loadedAt)})"
            entries += Item(refresh, BranchChoice.Refresh(roots.map { it.path }))
            return BranchPopupModel(entries, loading)
        }

        /**
         * New Branch…, Back to Trunk, Merge Trunk into Current and [branches] acting on [roots], each branch a submenu
         * with Switch and Merge into Current (cvs-branch-merge decision 10). A branch every root is on, with no file on
         * another branch, is current: its Switch chooses nothing and it offers no merge (design decisions 6, 19). When
         * [busy], every entry is disabled (design decision 21).
         */
        private fun rootEntries(roots: List<BranchStateService.Root>, branches: List<String>, busy: Boolean): List<Entry> {
            val paths = roots.map { it.path }
            val entries = mutableListOf<Entry>(
                Item(NEW_BRANCH, BranchChoice.NewBranch(paths), enabled = !busy),
                Item(BACK_TO_TRUNK, BranchChoice.Trunk(paths), enabled = !busy),
            )
            // Merging the trunk into roots that are all on it brings nothing (cvs-branch-merge decision 10).
            if (!roots.all { it.status.state == BranchState.TRUNK }) {
                entries += Item(MERGE_TRUNK, BranchChoice.Merge(SwitchTarget.Trunk, paths), enabled = !busy)
            }
            if (branches.isNotEmpty()) entries += Separator("Branches")
            for (branch in branches) {
                val target = BranchState(BranchKind.BRANCH, branch)
                val current = roots.all { it.status.state == target && it.status.mixedFiles == 0 }
                // Each branch is a submenu (decision 10); the current one switches nothing and offers no merge.
                val actions = mutableListOf<Entry>(Item(SWITCH, if (current) null else BranchChoice.Switch(branch, paths), enabled = !busy))
                if (!current) actions += Item(MERGE_INTO_CURRENT, BranchChoice.Merge(SwitchTarget.Branch(branch), paths), enabled = !busy)
                entries += Submenu(branch, actions, current = current)
            }
            return entries
        }

        /**
         * [root]'s unfinished merge (cvs-branch-merge decision 8): `merging <SRC>: N files left`, Complete Merge and
         * Forget Merge, then a separator; nothing without one. When [busy], the actions are disabled.
         */
        private fun mergeEntries(merges: MergeService, root: Path, busy: Boolean): List<Entry> {
            val merge = merges.unfinished(root) ?: return emptyList()
            val source = if (merge.source == MergeService.TRUNK) "the trunk" else merge.source
            val left = merge.files.size
            return listOf(
                Item("merging $source: $left ${if (left == 1) "file" else "files"} left", null, enabled = false),
                Item(COMPLETE_MERGE, BranchChoice.CompleteMerge(root), enabled = !busy),
                Item(FORGET_MERGE, BranchChoice.ForgetMerge(root), enabled = !busy),
                Separator(),
            )
        }

        /**
         * "Loading branches…" while [root]'s list loads, or why its load failed when it has no list: the error's first
         * line only (design decision 22).
         */
        private fun statusEntries(service: BranchListService, root: Path, loading: Boolean, listed: Boolean): List<Entry> {
            val error = service.error(root)?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            return when {
                loading -> listOf(Item(LOADING, null, enabled = false))
                !listed && error != null -> listOf(Item("Cannot load branches: $error", null, enabled = false))
                else -> emptyList()
            }
        }
    }
}
