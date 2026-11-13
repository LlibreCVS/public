package by.devlab.cvs.branches

import com.intellij.cvsSupport2.actions.update.UpdateByBranchUpdateSettings
import com.intellij.cvsSupport2.actions.update.UpdateSettings
import com.intellij.cvsSupport2.config.CvsConfiguration
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler
import com.intellij.cvsSupport2.cvshandlers.CvsUpdatePolicy
import com.intellij.cvsSupport2.updateinfo.UpdatedFilesProcessor
import com.intellij.cvsSupport2.cvsoperations.dateOrRevision.RevisionOrDate
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
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
) {
    /** One line per kind: `Switched: a, b`, `Skipped: b: already on X`, `Conflicts: a/f`, `Failed: a: error`. */
    fun text(): String = buildList {
        if (switched.isNotEmpty()) add("$switchedLabel: ${switched.joinToString(", ")}")
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
        return roots.map { root ->
            val name = root.fileName?.toString() ?: root.toString()
            val state = BranchState.read(root)
            val skip = when (target) {
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
        val steps = plan(target, roots)
        val switched = ArrayList<String>()
        val conflicts = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        val readOnly = CvsConfiguration.getInstance(project).MAKE_NEW_FILES_READONLY
        for (step in steps.filter { it.skip == null }) {
            val updated = UpdatedFiles.create()
            val settings = when (target) {
                is SwitchTarget.Branch -> UpdateByBranchUpdateSettings(target.name, readOnly)
                SwitchTarget.Trunk -> TrunkUpdateSettings(readOnly)
            }
            val files = arrayOf(VcsUtil.getFilePath(step.root.toFile(), true))
            val error = try {
                val handler = CommandCvsHandler.createUpdateHandler(files, settings, project, updated)
                // What CvsUpdateEnvironment does: the processor sorts CVS's per-file answers into the update groups.
                handler.addCvsListener(UpdatedFilesProcessor(updated))
                CvsRunner.run(project, handler)
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            CvsRunner.refresh(project, step.root)
            if (error != null) {
                failed += step.name to error
                continue
            }
            switched += step.name
            val conflicting = listOf(FileGroup.MERGED_WITH_CONFLICT_ID, CvsUpdatePolicy.BINARY_MERGED_ID)
                .flatMap { updated.getGroupById(it)?.files.orEmpty() }
            conflicting.sorted().forEach { file ->
                conflicts += relative(step, file)
            }
        }
        val title = when (target) {
            is SwitchTarget.Branch -> "Switch to ${target.name}"
            SwitchTarget.Trunk -> "Back to trunk"
        }
        val report = BranchReport(title, switched, steps.mapNotNull { step -> step.skip?.let { step.name to it } }, conflicts, failed)
        CvsRunner.notify(project, report)
        return report
    }

    /** Switches [roots] to [target] in a background task (called from the popup on the UI thread). */
    fun start(target: SwitchTarget, roots: List<Path>) {
        object : Task.Backgroundable(project, "Switching to ${target.text}", false) {
            override fun run(indicator: ProgressIndicator) {
                switch(target, roots)
            }
        }.queue()
    }

    /** [file] (a path CVS reported) as `<root name>/<path below the root>`. */
    private fun relative(step: Step, file: String): String {
        val path = try {
            Path.of(file)
        } catch (_: InvalidPathException) {
            return file
        }
        val below = if (path.isAbsolute && path.startsWith(step.root)) step.root.relativize(path) else path
        return "${step.name}/${below.toString().replace('\\', '/')}"
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
