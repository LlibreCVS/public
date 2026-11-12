package by.devlab.cvs.branches

import com.intellij.cvsSupport2.CvsVcs2
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Told when the cached branch state of a project's CVS roots changed. */
fun interface BranchStateListener {
    fun stateChanged()
}

/**
 * The branch state of every CVS working-copy root of a project, cached and refreshed in the background (design
 * decisions 5, 8, 11, 16, 17, 19). The widgets and the commit window only read it.
 */
@Service(Service.Level.PROJECT)
class BranchStateService(private val project: Project) : Disposable {

    /** One working-copy root: its directory, the name shown for it (the directory's name) and its status. */
    data class Root(val path: Path, val name: String, val status: RootStatus)

    /** What the indicator shows: [text], [tooltip] (lines separated by `\n`) and the [state] of the shown root. */
    data class Indicator(val text: String, val tooltip: String, val state: BranchState)

    /** Recomputations run one after another on this executor. */
    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("CVS branch state", 1)

    private val lock = Any()

    /** Work requested since the last recomputation started; guarded by [lock]. */
    private var rediscoverRequested = false
    private var scanAllRequested = false
    private val changedPaths = LinkedHashSet<Path>()
    private var scheduled: Future<*>? = null

    /** The last recomputation submitted to [executor]; guarded by [lock]. */
    private var last: Future<*>? = null

    @Volatile
    private var cache: List<Root> = emptyList()

    /** The root directories found at the last discovery, or null before the first one. Used on [executor] only. */
    private var rootPaths: List<Path>? = null

    @Volatile
    private var disposed = false

    init {
        refresh()
    }

    /** The cached roots, sorted by path; empty in a project without CVS roots. */
    fun roots(): List<Root> = cache

    /** The cached root that holds [file], the innermost one when roots are nested, or null. */
    fun rootFor(file: VirtualFile): Root? {
        val path = pathOf(file.path) ?: return null
        return cache.filter { path.startsWith(it.path) }.maxByOrNull { it.path.nameCount }
    }

    /** The indicator for the cached roots with [activeFile] in the active editor, or null without CVS roots. */
    fun indicator(activeFile: VirtualFile? = selectedFile()): Indicator? {
        val roots = cache
        if (roots.isEmpty()) return null
        val shown = activeFile?.let { rootFor(it) }?.takeIf { it in roots } ?: roots.first()
        val others = roots.count { it.status.state != shown.status.state }
        val text = if (others > 0) "${shown.status.text} +$others" else shown.status.text
        val tooltip = buildList {
            add(shown.status.tooltip)
            if (roots.size > 1) roots.forEach { add("${it.name}: ${it.status.text}") }
        }.joinToString("\n")
        return Indicator(text, tooltip, shown.status.state)
    }

    /** Schedules finding the roots again and recomputing all of them. */
    fun refresh() {
        request(rediscover = true, scanAll = true, paths = emptyList())
    }

    /**
     * Schedules recomputing the root states after [paths] changed: their roots get a new mixed scan; with
     * [rediscover] the roots are found again first.
     */
    internal fun changed(paths: Collection<Path>, rediscover: Boolean) {
        request(rediscover = rediscover, scanAll = false, paths = paths)
    }

    /** Waits until no recomputation is pending or running (test hook, design decision 16). */
    fun awaitRefresh() {
        while (true) {
            val future = synchronized(lock) { scheduled ?: last } ?: return
            future.get(30, TimeUnit.SECONDS)
            synchronized(lock) { if (scheduled == null && last === future) return }
        }
    }

    private fun request(rediscover: Boolean, scanAll: Boolean, paths: Collection<Path>) {
        if (disposed) return
        synchronized(lock) {
            rediscoverRequested = rediscoverRequested || rediscover
            scanAllRequested = scanAllRequested || scanAll
            changedPaths += paths
            if (scheduled == null) {
                val future = executor.schedule(::recompute, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
                scheduled = future
                last = future
            }
        }
    }

    private fun recompute() {
        val (rediscover, scanAll, paths) = synchronized(lock) {
            scheduled = null
            Triple(rediscoverRequested, scanAllRequested, changedPaths.toList()).also {
                rediscoverRequested = false
                scanAllRequested = false
                changedPaths.clear()
            }
        }
        if (disposed || project.isDisposed) return
        try {
            val known = rootPaths
            val discovered = if (rediscover || known == null) discoverRoots() else known
            rootPaths = discovered
            val previous = cache.associateBy { it.path }
            // The states first, so that they never wait for the mixed scan (design decision 7).
            var roots = discovered.map { path ->
                Root(path, path.fileName?.toString() ?: path.toString(), RootStatus(BranchState.read(path), previous[path]?.status?.mixedFiles ?: 0))
            }
            publish(roots)
            val toScan = roots.filter { root ->
                scanAll || rediscover || root.path !in previous || paths.any { it.startsWith(root.path) }
            }
            if (toScan.isEmpty()) return
            roots = roots.map { root ->
                if (root in toScan && !disposed) root.copy(status = root.status.copy(mixedFiles = MixedScan.count(root.path))) else root
            }
            publish(roots)
        } catch (e: Exception) {
            LOG.warn("Cannot compute the CVS branch state", e)
        }
    }

    private fun publish(roots: List<Root>) {
        if (roots == cache || disposed || project.isDisposed) return
        cache = roots
        project.messageBus.syncPublisher(TOPIC).stateChanged()
    }

    /**
     * The working-copy roots inside the project's CVS mappings (design decision 17): a mapped working-copy directory
     * is a root itself; below a mapped directory that is not one, every working-copy directory found is a root and is
     * not descended into.
     */
    private fun discoverRoots(): List<Path> {
        val vcsName = CvsVcs2.getKey().name
        val mapped = ProjectLevelVcsManager.getInstance(project).directoryMappings
            .filter { it.vcs == vcsName }
            .mapNotNull { mapping -> pathOf(mapping.directory.ifEmpty { project.basePath.orEmpty() }) }
        val roots = sortedSetOf<Path>(compareBy { it.toString() })
        for (dir in mapped) {
            if (TrackedDirectories.isWorkingCopyDirectory(dir)) roots.add(dir) else roots.addAll(workingCopiesBelow(dir))
        }
        return roots.toList()
    }

    private fun workingCopiesBelow(top: Path): List<Path> {
        val found = ArrayList<Path>()
        val pending = ArrayDeque(listOf(top))
        while (pending.isNotEmpty()) {
            if (disposed) break
            val dir = pending.removeFirst()
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue
            if (TrackedDirectories.isWorkingCopyDirectory(dir)) {
                found.add(dir)
                continue
            }
            try {
                Files.newDirectoryStream(dir).use { stream ->
                    stream.filter { it.fileName.toString() != "CVS" && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                        .sortedBy { it.toString() }
                        .forEach { pending.add(it) }
                }
            } catch (_: IOException) {
                // An unreadable directory holds no root we could read either.
            } catch (_: SecurityException) {
            }
        }
        return found
    }

    private fun selectedFile(): VirtualFile? =
        if (project.isDisposed) null else FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

    override fun dispose() {
        disposed = true
        synchronized(lock) { scheduled?.cancel(false) }
    }

    companion object {
        private val LOG = logger<BranchStateService>()

        /** How long changes are collected before the state is recomputed. */
        private const val DEBOUNCE_MILLIS = 300L

        @Topic.ProjectLevel
        val TOPIC: Topic<BranchStateListener> = Topic(BranchStateListener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(project: Project): BranchStateService = project.service()

        /** [path] (a VFS or mapping path) as an absolute, normalized file-system path, or null if it is not one. */
        internal fun pathOf(path: String): Path? =
            try {
                if (path.isEmpty()) null else Path.of(path).toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                null
            }
    }
}
