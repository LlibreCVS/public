package by.devlab.cvs.branches

import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutor
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutorCallback
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler
import com.intellij.cvsSupport2.cvsoperations.cvsLog.LogOperation
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import com.intellij.vcsUtil.VcsUtil
import org.netbeans.lib.cvsclient.command.log.LogInformation
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Told when a remembered branch list was loaded, changed or failed to load. */
fun interface BranchListListener {
    fun listsChanged()
}

/**
 * The remembered branch list of every CVS root of a project (design decisions 4, 5, 13, 18): kept in the workspace
 * file, loaded from the server only on request ([load]) with a header-only `log` over the root, in the background.
 * Reading a list never contacts the server.
 */
@Service(Service.Level.PROJECT)
@State(name = "IntelliJCVS.BranchLists", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class BranchListService(private val project: Project) : PersistentStateComponent<BranchListService.ListsState>, Disposable {

    /** A root's remembered [branches], sorted, and when they were loaded from the server ([loadedAt], epoch millis). */
    data class BranchList(val branches: List<String>, val loadedAt: Long)

    /** The persistent state: one entry per root. */
    class ListsState {
        @get:XCollection(style = XCollection.Style.v2)
        var roots: MutableList<RootList> = ArrayList()
    }

    /** One root's list in the workspace file. */
    @Tag("root")
    class RootList {
        @get:Attribute
        var path: String = ""

        @get:Attribute
        var loadedAt: Long = 0

        @get:XCollection(style = XCollection.Style.v2, elementName = "branch", valueAttributeName = "name")
        var branches: MutableList<String> = ArrayList()
    }

    /** One load runs at a time; the roots of a load are loaded one after another. */
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("CVS branch lists", 1)

    private val lock = Any()

    /** The lists by normalized root path; guarded by [lock]. */
    private val lists = LinkedHashMap<Path, BranchList>()

    /** The roots whose load is requested or running, and the last load error per root; guarded by [lock]. */
    private val loading = HashSet<Path>()
    private val errors = HashMap<Path, String>()

    /** The loads submitted and not yet finished; guarded by [lock]. */
    private val pending = ArrayList<Future<*>>()

    @Volatile
    private var disposed = false

    /** The remembered list of [root], or null when none was loaded yet. */
    fun list(root: Path): BranchList? = synchronized(lock) { lists[key(root)] }

    /** Whether a load of [root]'s list is requested or running. */
    fun isLoading(root: Path): Boolean = synchronized(lock) { key(root) in loading }

    /** Why the last load of [root]'s list failed, or null when it did not fail. */
    fun error(root: Path): String? = synchronized(lock) { errors[key(root)] }

    /**
     * Loads the lists of [roots] from the server in the background, one root after another; a root whose load is
     * already requested is not requested again. Each finished root is published on [TOPIC]; a failed one keeps its
     * previous list and records the error.
     */
    fun load(roots: Collection<Path>) {
        if (disposed) return
        val requested = synchronized(lock) {
            roots.map(::key).distinct().filter { loading.add(it) }.onEach { errors.remove(it) }
        }
        if (requested.isEmpty()) return
        val future = executor.submit {
            for (root in requested) {
                if (disposed || project.isDisposed) break
                loadRoot(root)
            }
        }
        synchronized(lock) {
            pending.removeAll { it.isDone }
            pending += future
        }
        publish()
    }

    /** Adds [branch] to the remembered list of [root] without contacting the server (design decision 5). */
    fun add(root: Path, branch: String) {
        synchronized(lock) {
            val key = key(root)
            val list = lists[key] ?: return
            if (branch in list.branches) return
            lists[key] = list.copy(branches = (list.branches + branch).sorted())
        }
        publish()
    }

    /** Waits until every requested load has finished (test hook). */
    fun awaitLoad() {
        while (true) {
            val futures = synchronized(lock) { pending.toList() }
            if (futures.isEmpty()) return
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
            synchronized(lock) { pending.removeAll { it.isDone } }
        }
    }

    /** Sets [root]'s list (test hook). */
    internal fun set(root: Path, branches: Collection<String>, loadedAt: Long) {
        synchronized(lock) { lists[key(root)] = BranchList(branches.distinct().sorted(), loadedAt) }
        publish()
    }

    private fun loadRoot(root: Path) {
        try {
            val branches = BranchLog.branches(project, root)
            synchronized(lock) {
                lists[root] = BranchList(branches.sorted(), System.currentTimeMillis())
                loading.remove(root)
            }
        } catch (e: Exception) {
            LOG.warn("Cannot load the CVS branches of $root", e)
            synchronized(lock) {
                errors[root] = e.message ?: e.toString()
                loading.remove(root)
            }
        }
        publish()
    }

    private fun publish() {
        if (disposed || project.isDisposed) return
        project.messageBus.syncPublisher(TOPIC).listsChanged()
    }

    override fun getState(): ListsState = synchronized(lock) {
        ListsState().apply {
            lists.forEach { (path, list) ->
                roots += RootList().apply {
                    this.path = path.toString()
                    loadedAt = list.loadedAt
                    branches = list.branches.toMutableList()
                }
            }
        }
    }

    override fun loadState(state: ListsState) {
        synchronized(lock) {
            lists.clear()
            for (root in state.roots) {
                val path = BranchStateService.pathOf(root.path) ?: continue
                lists[path] = BranchList(root.branches.distinct().sorted(), root.loadedAt)
            }
        }
    }

    override fun dispose() {
        disposed = true
    }

    companion object {
        private val LOG = logger<BranchListService>()

        @Topic.ProjectLevel
        val TOPIC: Topic<BranchListListener> = Topic(BranchListListener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(project: Project): BranchListService = project.service()

        /** [root] as the key of the lists: absolute and normalized, the same for `C:\x` and `C:/x`. */
        private fun key(root: Path): Path = BranchStateService.pathOf(root.toString()) ?: root
    }
}

/** Reads the branch names of a working-copy root from the server (design decisions 13, 18). */
internal object BranchLog {

    /**
     * The branches of the working copy [root]: a header-only `log` over the root through OpenCVS's [LogOperation], as
     * the branch chooser runs it, keeping only the symbolic names on branch revision numbers. Throws on any CVS error.
     */
    fun branches(project: Project, root: Path): Set<String> {
        val operation = BranchLogOperation(root)
        val executor = CvsOperationExecutor(false, project, ModalityState.nonModal())
        executor.setShowErrors(false)
        executor.setIsQuietOperation(true)
        val run = { executor.performActionSync(CommandCvsHandler("Load CVS branches", operation, true), CvsOperationExecutorCallback.EMPTY) }
        if (ProgressManager.getInstance().progressIndicator == null) {
            ProgressManager.getInstance().runProcess(run, EmptyProgressIndicator())
        } else {
            run()
        }
        if (!executor.hasNoErrors()) throw executor.firstError
        return operation.branches
    }

    /**
     * Whether [revision] is a branch number: `x.y.0.n` (a branch made with `tag -b`), or `x.y.z` with an odd number of
     * parts (the vendor branch of an import). A plain tag's number has an even number of parts and no `0` before the
     * last one.
     */
    fun isBranchRevision(revision: String): Boolean {
        val parts = revision.trim().split('.')
        if (parts.any { part -> part.isEmpty() || part.any { !it.isDigit() } }) return false
        return when {
            parts.size >= 3 && parts.size % 2 == 1 -> true
            parts.size >= 4 && parts.size % 2 == 0 -> parts[parts.size - 2] == "0"
            else -> false
        }
    }

    /** OpenCVS's header-only log, keeping the names on branch revisions from each file's [LogInformation]. */
    private class BranchLogOperation(root: Path) : LogOperation(listOf(VcsUtil.getFilePath(root.toFile(), true))) {
        val branches = HashSet<String>()

        override fun fileInfoGenerated(info: Any?) {
            super.fileInfoGenerated(info)
            if (info is LogInformation) {
                info.allSymbolicNames.filter { isBranchRevision(it.revision) }.forEach { branches += it.name }
            }
        }
    }
}
