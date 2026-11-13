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

    /** A root's submenu. */
    data class Submenu(val text: String, val entries: List<Entry>) : Entry

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
                    append(indent).append(entry.text).append(" >\n")
                    render(entry.entries, "$indent  ")
                }
                is Separator -> append(indent).append("---").append(entry.text?.let { " $it" }.orEmpty()).append('\n')
            }
        }
    }

    companion object {
        const val NEW_BRANCH = "New Branch…"
        const val BACK_TO_TRUNK = "Back to Trunk"
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

            val entries = ArrayList<Entry>()
            if (roots.size == 1) {
                val root = roots.single()
                entries += rootEntries(roots, lists[root.path]?.branches.orEmpty())
                entries += statusEntries(service, root.path, root.path in loading, lists[root.path] != null)
            } else {
                // Only branches every root's list has go to the top level (design decision 6, option A).
                val common = if (lists.values.any { it == null }) {
                    emptyList()
                } else {
                    lists.values.map { it!!.branches.toSet() }.reduceOrNull { all, next -> all intersect next }.orEmpty().sorted()
                }
                entries += rootEntries(roots, common)
                if (loading.isNotEmpty()) entries += Item(LOADING, null, enabled = false)
                entries += Separator("Roots")
                for (root in roots) {
                    val list = lists[root.path]?.branches.orEmpty()
                    entries += Submenu("${root.name}: ${root.status.text}", rootEntries(listOf(root), list) +
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
         * New Branch…, Back to Trunk and [branches] acting on [roots]. A branch every root is on, with no file on another
         * branch, is current and chooses nothing (design decisions 6, 19).
         */
        private fun rootEntries(roots: List<BranchStateService.Root>, branches: List<String>): List<Entry> {
            val paths = roots.map { it.path }
            val entries = mutableListOf<Entry>(
                Item(NEW_BRANCH, BranchChoice.NewBranch(paths)),
                Item(BACK_TO_TRUNK, BranchChoice.Trunk(paths)),
            )
            if (branches.isNotEmpty()) entries += Separator("Branches")
            for (branch in branches) {
                val target = BranchState(BranchKind.BRANCH, branch)
                val current = roots.all { it.status.state == target && it.status.mixedFiles == 0 }
                entries += Item(branch, if (current) null else BranchChoice.Switch(branch, paths), current = current)
            }
            return entries
        }

        /** "Loading branches…" while [root]'s list loads, or why its load failed when it has no list. */
        private fun statusEntries(service: BranchListService, root: Path, loading: Boolean, listed: Boolean): List<Entry> = when {
            loading -> listOf(Item(LOADING, null, enabled = false))
            !listed && service.error(root) != null ->
                listOf(Item("Cannot load branches: ${service.error(root)}", null, enabled = false))
            else -> emptyList()
        }
    }
}
