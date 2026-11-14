package by.devlab.cvs.branches

import com.intellij.cvsSupport2.actions.update.UpdateByBranchUpdateSettings
import com.intellij.cvsSupport2.actions.update.UpdateSettings
import com.intellij.cvsSupport2.config.CvsConfiguration
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler
import com.intellij.cvsSupport2.cvshandlers.CvsUpdatePolicy
import com.intellij.cvsSupport2.updateinfo.UpdatedFilesProcessor
import com.intellij.cvsSupport2.cvsoperations.dateOrRevision.RevisionOrDate
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.vcs.update.FileGroup
import com.intellij.openapi.vcs.update.UpdatedFiles
import com.intellij.vcsUtil.VcsUtil
import org.netbeans.lib.cvsclient.command.KeywordSubstitution
import java.nio.file.InvalidPathException
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
    val switchedLabel: String = "Switched",
    val notes: List<String> = emptyList(),
) {
    /** One line per kind: `Switched: a, b`, `Skipped: b: already on X`, `Conflicts: a/f`, `Failed: a: error`. */
    fun text(): String = buildList {
        if (switched.isNotEmpty()) add("$switchedLabel: ${switched.joinToString(", ")}")
        addAll(notes)
        if (skipped.isNotEmpty()) add("Skipped: ${skipped.joinToString("; ") { (root, reason) -> "$root: $reason" }}")
        if (conflicts.isNotEmpty()) add("Conflicts: ${conflicts.joinToString(", ")}")
        if (failed.isNotEmpty()) add("Failed: ${failed.joinToString("; ") { (root, error) -> "$root: $error" }}")
    }.joinToString("\n")
}

/**
 * Switches roots to a branch or back to the trunk (design decisions 7, 9, 14, 19): plans each root (switched, or
 * skipped with the reason), then updates the switched roots one after another; a failure in one root does not stop
 * the others and nothing is rolled back. One notification reports the result.
 */
class BranchSwitcher(private val project: Project, private val prompts: BranchPrompts = BranchPrompts.UI) {

    /** One root of a switch: switched, or skipped for [skip]. */
    data class Step(val root: Path, val name: String, val skip: String?)

    /**
     * Each root of [roots] switched or skipped: a root whose list lacks the branch is skipped before any CVS call
     * (design decision 7); a root already on the target with no file elsewhere is skipped, a mixed one is switched
     * (decision 19). Reads only the working copy's administrative files and the remembered lists.
     */
    fun plan(target: SwitchTarget, roots: List<Path>): List<Step> {
        val lists = BranchListService.getInstance(project)
        val merges = MergeService.getInstance(project)
        return roots.map { root ->
            val name = rootName(root)
            val state = BranchState.read(root)
            // The merged edits must not travel to another branch (cvs-branch-merge decision 15).
            val unfinished = merges.unfinished(root)?.let { "unfinished merge of ${it.source}" }
            val skip = unfinished ?: when (target) {
                is SwitchTarget.Branch -> when {
                    lists.list(root)?.branches?.contains(target.name) != true -> "lacks the branch ${target.name}"
                    state == BranchState(BranchKind.BRANCH, target.name) && MixedScan.count(root) == 0 -> "already on ${target.name}"
                    else -> null
                }
                SwitchTarget.Trunk ->
                    if (state == BranchState.TRUNK && MixedScan.count(root) == 0) "already on the trunk" else null
            }
            Step(root, name, skip)
        }
    }

    /**
     * Switches [roots] to [target] on the calling thread (never the UI thread), shows the report and returns it.
     */
    fun switch(target: SwitchTarget, roots: List<Path>): BranchReport? {
        val planned = plan(target, roots)
        // A branch on part of a root is confirmed first (design decision 20); Back to Trunk is not affected.
        val steps = if (target is SwitchTarget.Branch) confirmPartial(target.name, planned) ?: return null else planned
        // Uncommitted changes are confirmed before anything changes (design decision 8); without any, nothing is asked.
        val changes = uncommittedChanges(steps.filter { it.skip == null })
        if (changes.isNotEmpty() && !prompts.confirmSwitch(project, target.text, changes)) return null
        val switched = ArrayList<String>()
        val conflicts = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        for (step in steps.filter { it.skip == null }) {
            val result = update(step.root, target)
            if (result.error != null) {
                failed += step.name to result.error
                continue
            }
            switched += step.name
            conflicts += result.conflicts
        }
        val title = when (target) {
            is SwitchTarget.Branch -> "Switch to ${target.name}"
            SwitchTarget.Trunk -> "Back to trunk"
        }
        val report = BranchReport(title, switched, steps.mapNotNull { step -> step.skip?.let { step.name to it } }, conflicts, failed)
        CvsRunner.notify(project, report)
        return report
    }

    /** What updating one root did: the CVS [error], or null, and the files left with [conflicts] (`<root>/<path>`). */
    internal data class UpdateResult(val error: String?, val conflicts: List<String>)

    /**
     * Updates the working copy [root] to [target] with OpenCVS's update handler (`update -r` or `update -A`, design
     * decision 14) and reloads it from disk.
     */
    internal fun update(root: Path, target: SwitchTarget): UpdateResult {
        val readOnly = CvsConfiguration.getInstance(project).MAKE_NEW_FILES_READONLY
        val updated = UpdatedFiles.create()
        val settings = when (target) {
            is SwitchTarget.Branch -> UpdateByBranchUpdateSettings(target.name, readOnly)
            SwitchTarget.Trunk -> TrunkUpdateSettings(readOnly)
        }
        val files = arrayOf(VcsUtil.getFilePath(root.toFile(), true))
        val error = try {
            val handler = CommandCvsHandler.createUpdateHandler(files, settings, project, updated)
            // What CvsUpdateEnvironment does: the processor sorts CVS's per-file answers into the update groups.
            handler.addCvsListener(UpdatedFilesProcessor(updated))
            CvsRunner.run(project, handler)
        } catch (e: Exception) {
            // A cancel or another control-flow exception ends the operation; it is not a root's failure (decision 22).
            if (e is ControlFlowException) throw e
            e.message ?: e.toString()
        }
        CvsRunner.refresh(project, root)
        val conflicts = listOf(FileGroup.MERGED_WITH_CONFLICT_ID, CvsUpdatePolicy.BINARY_MERGED_ID)
            .flatMap { updated.getGroupById(it)?.files.orEmpty() }
            .sorted()
            .map { relative(root, it) }
        return UpdateResult(error, conflicts)
    }

    /**
     * Switches [roots] to [target] in a background task (called from the popup on the UI thread), unless another branch
     * operation runs (design decision 21).
     */
    fun start(target: SwitchTarget, roots: List<Path>) {
        BranchOperations.getInstance(project).queue("Switching to ${target.text}") { switch(target, roots) }
    }

    /**
     * Asks about the switched roots of [steps] that [branch] covers only in part by their remembered counts (design
     * decision 20): the steps unchanged for Switch, with those roots skipped for Skip, null for Cancel. Nothing is asked
     * when no root is partial.
     */
    internal fun confirmPartial(branch: String, steps: List<Step>): List<Step>? {
        val lists = BranchListService.getInstance(project)
        val partial = steps.filter { it.skip == null }.mapNotNull { step -> lists.list(step.root)?.partial(branch)?.let { step to it } }
        if (partial.isEmpty()) return steps
        return when (prompts.confirmPartialBranch(project, branch, partial.associate { (step, coverage) -> step.name to coverage })) {
            PartialBranchChoice.SWITCH -> steps
            PartialBranchChoice.SKIP -> {
                val skipped = partial.map { it.first }.toSet()
                steps.map { if (it in skipped) it.copy(skip = PARTIAL) else it }
            }
            PartialBranchChoice.CANCEL -> null
        }
    }

    /**
     * The number of changed files per root of [steps] (root name → count, roots without changes left out), from the
     * IDE's change list after it took in the current disk state; no server is contacted (design decision 8).
     */
    internal fun uncommittedChanges(steps: List<Step>): Map<String, Int> {
        val files = LocalChanges.files(project, steps.map { it.root })
        val counts = LinkedHashMap<String, Int>()
        for (step in steps) {
            val count = files[step.root].orEmpty().size
            if (count > 0) counts[step.name] = count
        }
        return counts
    }

    /** [file] (a path CVS reported) as `<root name>/<path below the root>`. */
    private fun relative(root: Path, file: String): String {
        val path = try {
            Path.of(file)
        } catch (_: InvalidPathException) {
            return file
        }
        val below = if (path.isAbsolute && path.startsWith(root)) root.relativize(path) else path
        return "${rootName(root)}/${below.toString().replace('\\', '/')}"
    }

    companion object {
        /** Why a root that a branch covers only in part was skipped (design decision 20). */
        const val PARTIAL = "branch covers only part of the root"

        /** The name a root is reported by: its directory's name. */
        fun rootName(root: Path): String = root.fileName?.toString() ?: root.toString()
    }

    /** The update settings of a switch back to the trunk: `update -A`, reset sticky tags (design decision 14). */
    private class TrunkUpdateSettings(private val readOnly: Boolean) : UpdateSettings {
        override fun getPruneEmptyDirectories(): Boolean = true
        override fun getBranch1ToMergeWith(): String? = null
        override fun getBranch2ToMergeWith(): String? = null
        override fun getResetAllSticky(): Boolean = true
        override fun getDontMakeAnyChanges(): Boolean = false
        override fun getCreateDirectories(): Boolean = true
        override fun getCleanCopy(): Boolean = false
        override fun getRevisionOrDate(): RevisionOrDate = RevisionOrDate.EMPTY
        override fun getKeywordSubstitution(): KeywordSubstitution? = null
        override fun getMakeNewFilesReadOnly(): Boolean = readOnly
    }
}
