package by.devlab.cvs.branches

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * The unfinished merges of the project's CVS roots (cvs-branch-merge decisions 7, 9, 13, 14): one per root, kept in
 * the workspace file across restarts.
 */
@Service(Service.Level.PROJECT)
class MergeService(private val project: Project) {

    /**
     * A merge of [source] into [target] (branch names, `TRUNK` for the trunk) left uncommitted: the [files] it changed
     * (path below the root → `CVS/Entries` revision after the merge, decision 13), the [snapshot] revisions of the
     * `MERGING` tag per file (decision 14) and the files already [committed] from the IDE.
     */
    data class UnfinishedMerge(
        val source: String,
        val target: String,
        val files: Map<String, String>,
        val snapshot: Map<String, String>,
        val committed: Set<String> = emptySet(),
    ) {
        val mergingTag: String get() = "${source}_MERGING_$target"
        val mergedTag: String get() = "${source}_MERGED_$target"
    }

    fun record(root: Path, merge: UnfinishedMerge): Unit = throw NotImplementedError()

    fun unfinished(root: Path): UnfinishedMerge? = throw NotImplementedError()

    companion object {
        fun getInstance(project: Project): MergeService = project.service()
    }
}
