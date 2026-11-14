package by.devlab.cvs.branches

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.nio.file.Path

/** Told when an unfinished merge was recorded, changed or dropped. */
fun interface MergeListener {
    fun mergesChanged()
}

/**
 * The unfinished merges of the project's CVS roots (cvs-branch-merge decisions 7, 9, 13, 14): one per root, kept in
 * the workspace file across restarts, by the root's normalized path (the same for `C:\x` and `C:/x`).
 */
@Service(Service.Level.PROJECT)
@State(name = "IntelliJCVS.UnfinishedMerges", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class MergeService(private val project: Project) : PersistentStateComponent<MergeService.MergesState> {

    /**
     * A merge of [source] into [target] (branch names, `TRUNK` for the trunk) left uncommitted: the [files] it changed
     * and not yet committed or rolled back (path below the root → `CVS/Entries` revision after the merge, decision 13),
     * the [snapshot] revisions of the `MERGING` tag per file (decision 14) and whether a file of it was [committed]
     * from the IDE.
     */
    data class UnfinishedMerge(
        val source: String,
        val target: String,
        val files: Map<String, String>,
        val snapshot: Map<String, String>,
        val committed: Boolean = false,
    ) {
        val mergingTag: String get() = tagName(source, "MERGING", target)
        val mergedTag: String get() = tagName(source, "MERGED", target)
    }

    private val lock = Any()

    /** The unfinished merges by normalized root path; guarded by [lock]. */
    private val merges = LinkedHashMap<Path, UnfinishedMerge>()

    /** Records [merge] as [root]'s unfinished merge, replacing the one it had. */
    fun record(root: Path, merge: UnfinishedMerge) {
        synchronized(lock) { merges[key(root)] = merge }
        publish()
    }

    /** [root]'s unfinished merge, or null. */
    fun unfinished(root: Path): UnfinishedMerge? = synchronized(lock) { merges[key(root)] }

    /** Every root with an unfinished merge. */
    fun roots(): List<Path> = synchronized(lock) { merges.keys.toList() }

    /** Drops [root]'s unfinished merge. */
    fun drop(root: Path) {
        val removed = synchronized(lock) { merges.remove(key(root)) }
        if (removed != null) publish()
    }

    private fun publish() {
        if (project.isDisposed) return
        project.messageBus.syncPublisher(TOPIC).mergesChanged()
    }

    /** The persistent state: one entry per root. */
    class MergesState {
        @get:XCollection(style = XCollection.Style.v2)
        var roots: MutableList<RootMerge> = ArrayList()
    }

    /** One root's unfinished merge in the workspace file. */
    @Tag("merge")
    class RootMerge {
        @get:Attribute
        var path: String = ""

        @get:Attribute
        var source: String = ""

        @get:Attribute
        var target: String = ""

        @get:Attribute
        var committed: Boolean = false

        @get:XCollection(style = XCollection.Style.v2, propertyElementName = "files")
        var files: MutableList<FileRevision> = ArrayList()

        @get:XCollection(style = XCollection.Style.v2, propertyElementName = "snapshot")
        var snapshot: MutableList<FileRevision> = ArrayList()
    }

    /** A file below the root and a revision. */
    @Tag("file")
    class FileRevision {
        @get:Attribute
        var path: String = ""

        @get:Attribute
        var revision: String = ""
    }

    override fun getState(): MergesState = synchronized(lock) {
        MergesState().apply {
            merges.forEach { (root, merge) ->
                roots += RootMerge().apply {
                    path = root.toString()
                    source = merge.source
                    target = merge.target
                    committed = merge.committed
                    files = revisions(merge.files)
                    snapshot = revisions(merge.snapshot)
                }
            }
        }
    }

    override fun loadState(state: MergesState) {
        synchronized(lock) {
            merges.clear()
            for (root in state.roots) {
                val path = BranchStateService.pathOf(root.path) ?: continue
                merges[path] = UnfinishedMerge(
                    root.source, root.target,
                    root.files.associate { it.path to it.revision },
                    root.snapshot.associate { it.path to it.revision },
                    root.committed,
                )
            }
        }
    }

    private fun revisions(map: Map<String, String>): MutableList<FileRevision> =
        map.entries.sortedBy { it.key }.mapTo(ArrayList()) { (file, number) ->
            FileRevision().apply {
                path = file
                revision = number
            }
        }

    companion object {
        /** The trunk's name in merge-point tags (decision 4). */
        const val TRUNK = "TRUNK"

        @Topic.ProjectLevel
        val TOPIC: Topic<MergeListener> = Topic(MergeListener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(project: Project): MergeService = project.service()

        /** `<SRC>_<kind>_<DST>` (decision 4). */
        fun tagName(source: String, kind: String, target: String): String = "${source}_${kind}_$target"

        /** [root] as the key of the merges: absolute and normalized. */
        private fun key(root: Path): Path = BranchStateService.pathOf(root.toString()) ?: root
    }
}
