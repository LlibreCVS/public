package by.devlab.cvs.branches

import by.devlab.cvs.branches.MergeService.Companion.TRUNK
import by.devlab.cvs.branches.MergeService.UnfinishedMerge
import com.intellij.cvsSupport2.actions.update.UpdateSettings
import com.intellij.cvsSupport2.config.CvsConfiguration
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler
import com.intellij.cvsSupport2.cvshandlers.CvsUpdatePolicy
import com.intellij.cvsSupport2.cvsoperations.dateOrRevision.RevisionOrDate
import com.intellij.cvsSupport2.updateinfo.UpdatedFilesProcessor
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.update.FileGroup
import com.intellij.openapi.vcs.update.UpdatedFiles
import com.intellij.vcsUtil.VcsUtil
import org.netbeans.lib.cvsclient.command.KeywordSubstitution
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Merges a branch or the trunk into what each root is on now, with merge-point tags (cvs-branch-merge decisions 3, 4,
 * 5, 11, 13, 14, 16): plans each root (merged, or skipped with the reason), then per root moves the snapshot tag
 * `<SRC>_MERGING_<DST>` to the source, checks whether `<SRC>_MERGED_<DST>` exists, runs `update -kk -j … [-j …]` and
 * records the unfinished merge. A failure in one root does not stop the others; nothing is committed or rolled back.
 */
class BranchMerger(private val project: Project, private val prompts: BranchPrompts = BranchPrompts.UI) {

    /** One root of a merge: merged into [target] (`TRUNK` or a branch name), or skipped for [skip]. */
    data class Step(val root: Path, val name: String, val target: String?, val skip: String?)

    /**
     * Each root of [roots] merged or skipped (decision 11): skipped when the source is a branch named `TRUNK`, when the
     * root's list lacks the source, when it is on a tag or a date or has files on another branch, when it is on the
     * source itself or when it has an unfinished merge. Reads only the working copy and the remembered state.
     */
    fun plan(source: SwitchTarget, roots: List<Path>): List<Step> {
        val lists = BranchListService.getInstance(project)
        val merges = MergeService.getInstance(project)
        return roots.map { root ->
            val name = BranchSwitcher.rootName(root)
            val state = BranchState.read(root)
            val target = when (state.kind) {
                BranchKind.TRUNK -> TRUNK
                BranchKind.BRANCH -> state.name
                else -> null
            }
            val skip = when {
                source is SwitchTarget.Branch && source.name == TRUNK -> "a branch named $TRUNK cannot be merged"
                source is SwitchTarget.Branch && lists.list(root)?.branches?.contains(source.name) != true ->
                    "lacks the branch ${source.name}"
                target == null || MixedScan.count(root) > 0 -> "not on the trunk or a branch"
                target == sourceName(source) -> "already on ${source.text}"
                else -> merges.unfinished(root)?.let { "unfinished merge of ${it.source}" }
            }
            Step(root, name, target, skip)
        }
    }

    /** Merges [source] into [roots] on the calling thread (never the UI thread), shows the report and returns it. */
    fun merge(source: SwitchTarget, roots: List<Path>): BranchReport? {
        val steps = plan(source, roots)
        val merged = ArrayList<String>()
        val conflicts = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        for (step in steps.filter { it.skip == null }) {
            val result = mergeRoot(step.root, source, step.target!!)
            if (result.error != null) {
                failed += step.name to result.error
                continue
            }
            merged += step.name
            conflicts += result.conflicts
        }
        val report = BranchReport(
            "Merge ${source.text} into current", merged, steps.mapNotNull { step -> step.skip?.let { step.name to it } },
            conflicts, failed, switchedLabel = "Merged",
        )
        CvsRunner.notify(project, report)
        return report
    }

    /** Merges in a background task as the project's branch operation (decision 11). */
    fun start(source: SwitchTarget, roots: List<Path>) {
        BranchOperations.getInstance(project).queue("Merging ${source.text}") { merge(source, roots) }
    }

    /** What merging one root did: the CVS [error], or null, and the files left with [conflicts] (`<root>/<path>`). */
    private data class RootResult(val error: String?, val conflicts: List<String> = emptyList())

    private fun mergeRoot(root: Path, source: SwitchTarget, target: String): RootResult {
        val src = sourceName(source)
        val merging = MergeService.tagName(src, "MERGING", target)
        val merged = MergeService.tagName(src, "MERGED", target)
        try {
            // The snapshot first (decision 3): it marks exactly what this merge brings, even if the source moves on.
            CvsTags.move(project, root, if (source is SwitchTarget.Branch) source.name else "HEAD", merging)
                ?.let { return RootResult(it) }
            // One log tells whether the root was merged before and which revisions the snapshot marks (decisions 11, 14).
            val tags = CvsTags.revisions(project, root, setOf(merging, merged))
            val snapshot = tags[merging].orEmpty()
            // Without MERGED, `-j A -j B` would treat every file as added between A and B: one -j from the branch point.
            val from = if (tags[merged].orEmpty().isNotEmpty()) merged else null
            val update = update(root, from, merging)
            if (update.error != null) return update
            val changed = LocalChanges.files(project, listOf(root))[root].orEmpty()
            val files = changed.mapNotNull { file -> relative(root, file)?.let { it to entryRevision(file).orEmpty() } }.toMap()
            if (files.isNotEmpty()) {
                MergeService.getInstance(project).record(root, UnfinishedMerge(src, target, files, snapshot))
            }
            return update
        } catch (e: Exception) {
            // A cancel or another control-flow exception ends the operation; it is not a root's failure.
            if (e is ControlFlowException) throw e
            return RootResult(e.message ?: e.toString())
        }
    }

    /** `update -kk -j [from] -j to` of [root] (decisions 3, 16) through OpenCVS's update handler; reloads the root. */
    private fun update(root: Path, from: String?, to: String): RootResult {
        val readOnly = CvsConfiguration.getInstance(project).MAKE_NEW_FILES_READONLY
        val updated = UpdatedFiles.create()
        val settings = MergeUpdateSettings(from ?: to, if (from == null) null else to, readOnly)
        val handler = CommandCvsHandler.createUpdateHandler(arrayOf(VcsUtil.getFilePath(root.toFile(), true)), settings, project, updated)
        handler.addCvsListener(UpdatedFilesProcessor(updated))
        val error = CvsRunner.run(project, handler)
        CvsRunner.refresh(project, root)
        val conflicts = listOf(FileGroup.MERGED_WITH_CONFLICT_ID, CvsUpdatePolicy.BINARY_MERGED_ID)
            .flatMap { updated.getGroupById(it)?.files.orEmpty() }
            .sorted()
            .map { file -> "${BranchSwitcher.rootName(root)}/${relativeReported(root, file)}" }
        return RootResult(error, conflicts)
    }

    /** [file] (a path CVS reported) below [root], `/`-separated. */
    private fun relativeReported(root: Path, file: String): String {
        val path = try {
            Path.of(file)
        } catch (_: InvalidPathException) {
            return file
        }
        val below = if (path.isAbsolute && path.startsWith(root)) root.relativize(path) else path
        return below.toString().replace('\\', '/')
    }

    private fun relative(root: Path, file: Path): String? {
        val path = file.toAbsolutePath().normalize()
        val base = root.toAbsolutePath().normalize()
        return if (path.startsWith(base)) base.relativize(path).toString().replace('\\', '/') else null
    }

    companion object {
        /** The source's name in merge-point tags: the branch name, `TRUNK` for the trunk (decision 4). */
        fun sourceName(source: SwitchTarget): String = when (source) {
            is SwitchTarget.Branch -> source.name
            SwitchTarget.Trunk -> TRUNK
        }
    }

    /** The settings of a merge: `update -kk -j branch1 [-j branch2]`, sticky tags kept (decisions 3, 16). */
    private class MergeUpdateSettings(
        private val branch1: String,
        private val branch2: String?,
        private val readOnly: Boolean,
    ) : UpdateSettings {
        override fun getPruneEmptyDirectories(): Boolean = true
        override fun getBranch1ToMergeWith(): String = branch1
        override fun getBranch2ToMergeWith(): String? = branch2
        override fun getResetAllSticky(): Boolean = false
        override fun getDontMakeAnyChanges(): Boolean = false
        override fun getCreateDirectories(): Boolean = true
        override fun getCleanCopy(): Boolean = false
        override fun getRevisionOrDate(): RevisionOrDate = RevisionOrDate.EMPTY
        override fun getKeywordSubstitution(): KeywordSubstitution = KeywordSubstitution.KEYWORD_COMPRESSION
        override fun getMakeNewFilesReadOnly(): Boolean = readOnly
    }
}
