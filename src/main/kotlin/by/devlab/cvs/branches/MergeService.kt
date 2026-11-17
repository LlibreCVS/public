package by.devlab.cvs.branches

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.util.messages.Topic
import com.intellij.vcsUtil.VcsUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.nio.file.Files
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
@State(name = "LibreCVS.UnfinishedMerges", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
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

    /** Whether a merge became fully committed and its merge point still has to be moved. */
    private val pending = AtomicBoolean()

    /** The roots whose merge point could not be moved (snapshot moved, CVS error) this session; not retried by itself. */
    private val stuck: MutableSet<Path> = ConcurrentHashMap.newKeySet()

    /**
     * Takes [files], committed from the IDE, out of the unfinished merges (decision 9). Cheap, callable on the UI thread:
     * a merge left with no file has its merge point moved in a background branch operation.
     */
    fun committed(files: Collection<Path>) {
        var changed = false
        synchronized(lock) {
            for ((root, merge) in merges.entries.toList()) {
                val names = files.mapNotNull { relative(root, it) }.filter { it in merge.files }
                if (names.isEmpty()) continue
                // A commit made after the root left the merge's target did not bring anything to it (decision 15).
                if (!onTarget(root, merge)) continue
                val left = merge.copy(files = merge.files - names.toSet(), committed = true)
                merges[root] = left
                changed = true
                if (left.files.isEmpty()) pending.set(true)
            }
        }
        if (changed) publish()
        settleLater()
    }

    /**
     * Takes rolled-back files out of the unfinished merges (decision 9): a file the change list does not show as changed
     * and whose `CVS/Entries` revision is the one after the merge (an added file: gone from `CVS/Entries`). A merge left
     * with no file is forgotten when nothing of it was committed; otherwise its merge point is moved. With [fresh], the
     * change list first takes in the disk state (waits for it); call it off the UI thread then.
     */
    fun refresh(fresh: Boolean = true) {
        val current = synchronized(lock) { merges.toMap() }
        if (current.isEmpty()) return settleLater()
        val changes: Map<Path, Set<Path>> = if (fresh) {
            LocalChanges.files(project, current.keys.toList()).mapValues { (_, files) -> files.map(::normalized).toSet() }
        } else {
            val manager = ChangeListManager.getInstance(project)
            current.keys.associateWith { root ->
                manager.getChangesIn(VcsUtil.getFilePath(root.toFile(), true))
                    .mapNotNull { (it.afterRevision ?: it.beforeRevision)?.file?.ioFile?.toPath()?.let(::normalized) }.toSet()
            }
        }
        var changed = false
        synchronized(lock) {
            for (root in current.keys) {
                val merge = merges[root] ?: continue
                val rolledBack = merge.files.filter { (path, revision) ->
                    val file = normalized(root.resolve(path))
                    file !in changes[root].orEmpty() && rolledBack(file, revision)
                }.keys
                val left = merge.copy(files = merge.files - rolledBack)
                when {
                    left.files.isNotEmpty() -> merges[root] = left
                    // Fully committed (or committed and the rest rolled back): the merge point moves.
                    left.committed -> {
                        merges[root] = left
                        pending.set(true)
                    }
                    // Every file rolled back: forgotten without moving anything.
                    else -> merges.remove(root)
                }
                if (rolledBack.isNotEmpty() || left.files.isEmpty() && !left.committed) changed = true
            }
        }
        if (changed) publish()
        settleLater()
    }

    /** Moves the merge points of the fully committed merges in a background branch operation, or later when one runs. */
    private fun settleLater() {
        if (!pending.get() || project.isDisposed) return
        BranchOperations.getInstance(project).queueIfIdle("Moving CVS merge points") {
            pending.set(false)
            val done = synchronized(lock) { merges.filter { (root, it) -> it.files.isEmpty() && it.committed && root !in stuck }.keys.toList() }
            for (root in done) moveMergePoint(root)?.let { CvsRunner.notify(project, it) }
        }
    }

    /**
     * Moves [root]'s `<SRC>_MERGED_<DST>` to the snapshot and drops its unfinished merge (decisions 3, 14), on the calling
     * thread (never the UI thread); returns the report, or null without an unfinished merge. When the snapshot no longer
     * marks the recorded revisions (another merge moved it) or CVS fails, nothing is moved and the merge stays.
     */
    internal fun moveMergePoint(root: Path): BranchReport? {
        val merge = unfinished(root) ?: return null
        val name = BranchSwitcher.rootName(root)
        val title = "Merge of ${merge.source} into ${merge.target}"
        val error = try {
            val now = if (onTarget(root, merge)) CvsTags.revisions(project, root, setOf(merge.mergingTag))[merge.mergingTag].orEmpty() else null
            when {
                now == null -> "the root is no longer on ${targetText(merge.target)}; ${merge.mergedTag} was not moved"
                // Only the files recorded before the merge are compared: a merge may bring files the first log could
                // not see (a new directory), which carry the snapshot afterwards.
                merge.snapshot.any { (path, revision) -> now[path] != revision } ->
                    "snapshot ${merge.mergingTag} was moved by another merge; ${merge.mergedTag} was not moved (use Forget Merge, then merge again)"
                else -> CvsTags.move(project, root, merge.mergingTag, merge.mergedTag)
            }
        } catch (e: Exception) {
            if (e is ControlFlowException) throw e
            e.message ?: e.toString()
        }
        if (error != null) {
            stuck.add(key(root))
            return BranchReport(title, emptyList(), emptyList(), emptyList(), listOf(name to error))
        }
        stuck.remove(key(root))
        drop(root)
        // The snapshot was checked above: it is this merge's and is needed no more (merge-drop-merging-tag).
        val notes = listOfNotNull(snapshotNotDeleted(merge, deleteTag(root, merge.mergingTag)))
        return BranchReport(
            title, listOf(name), emptyList(), emptyList(), emptyList(), switchedLabel = "Merge point ${merge.mergedTag} moved", notes = notes,
        )
    }

    /** `rtag -d [tag]` of [root]'s module: the CVS error, or null. */
    private fun deleteTag(root: Path, tag: String): String? = try {
        CvsTags.delete(project, root, tag)
    } catch (e: Exception) {
        if (e is ControlFlowException) throw e
        e.message ?: e.toString()
    }

    /** The report note for a snapshot left in the repository for [reason], or null without one. */
    private fun snapshotNotDeleted(merge: UnfinishedMerge, reason: String?): String? =
        reason?.let { "Snapshot ${merge.mergingTag} was not deleted: $it" }

    /**
     * Whether [file], recorded at [revision] after the merge, is back at it (an added file: no longer listed). A missing
     * file is not rolled back: the change list shows it as locally deleted, not as a change, and its entry is unchanged.
     */
    private fun rolledBack(file: Path, revision: String): Boolean {
        val now = entryRevision(file)
        return when {
            revision.startsWith("0") -> now == null
            !Files.exists(file) -> false
            revision.startsWith("-") -> now == revision.removePrefix("-")
            else -> now == revision
        }
    }

    /** Whether [root] is still on the merge's target: the trunk for `TRUNK`, else that branch. */
    private fun onTarget(root: Path, merge: UnfinishedMerge): Boolean {
        val state = BranchState.read(root)
        return if (merge.target == TRUNK) state == BranchState.TRUNK else state == BranchState(BranchKind.BRANCH, merge.target)
    }

    private fun targetText(target: String): String = if (target == TRUNK) "the trunk" else target

    /**
     * Re-checks the unfinished merges once the change list has taken in what is pending (decision 9), in the
     * background; for callers on the UI thread such as the popup, which must not act on a stale change list.
     */
    fun refreshLater() {
        if (roots().isEmpty() || project.isDisposed) return
        ChangeListManager.getInstance(project).invokeAfterUpdate(false) {
            ApplicationManager.getApplication().executeOnPooledThread { if (!project.isDisposed) refresh(fresh = false) }
        }
    }

    /** [file] below [root], `/`-separated, or null. */
    private fun relative(root: Path, file: Path): String? {
        val path = normalized(file)
        return if (path.startsWith(root)) root.relativize(path).toString().replace('\\', '/') else null
    }

    private fun normalized(path: Path): Path = path.toAbsolutePath().normalize()

    /** Complete Merge: moves [root]'s merge point now and reports it (decision 8). */
    fun complete(root: Path): BranchReport? {
        val report = moveMergePoint(root) ?: return null
        CvsRunner.notify(project, report)
        return report
    }

    /** Forget Merge: forgets [root]'s unfinished merge without moving its merge point and reports it (decision 8). */
    fun forget(root: Path): BranchReport? {
        val merge = unfinished(root) ?: return null
        stuck.remove(key(root))
        drop(root)
        val report = BranchReport(
            "Merge of ${merge.source} into ${merge.target}", listOf(BranchSwitcher.rootName(root)), emptyList(), emptyList(),
            emptyList(), switchedLabel = "Forgotten, ${merge.mergedTag} not moved",
        )
        CvsRunner.notify(project, report)
        return report
    }

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
