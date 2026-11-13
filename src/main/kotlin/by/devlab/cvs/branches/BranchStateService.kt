package by.devlab.cvs.branches

import com.intellij.cvsSupport2.CvsVcs2
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    /** Test hook: called with each directory the mixed scan reaches, before it is read. */
    @Volatile
    internal var onScanDirectory: (Path) -> Unit = {}

    /** Test hook: how many times the roots were looked for. */
    internal val discoveries = AtomicInteger()

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = onFileEvents(events)
        })
        connection.subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED, VcsListener { refresh() })
        // With several roots the shown root follows the active editor (design decision 8).
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                if (cache.size > 1) project.messageBus.syncPublisher(TOPIC).stateChanged()
            }
        })
        refresh()
    }

    /**
     * Turns VFS events into recomputations (design decision 11): a changed `CVS/Tag`, `CVS/Entries` or
     * `CVS/Entries.Log` recomputes the root that holds it; a `CVS` directory or its `Root` or `Repository` file
     * created, deleted or moved finds the roots again. Runs on the thread that applies the events, so it only looks
     * at the paths.
     */
    private fun onFileEvents(events: List<VFileEvent>) {
        if (disposed) return
        var rediscover = false
        val changed = ArrayList<Path>()
        for (event in events) {
            val paths = if (event is VFileMoveEvent) listOf(event.oldPath, event.path) else listOf(event.path)
            for (eventPath in paths) {
                val name = eventPath.substringAfterLast('/')
                val parentName = eventPath.substringBeforeLast('/', "").substringAfterLast('/')
                when {
                    name == "CVS" && event !is VFileContentChangeEvent -> {
                        rediscover = true
                        pathOf(eventPath.substringBeforeLast('/', ""))?.let { changed.add(it) }
                    }
                    parentName == "CVS" && name in STATE_FILES -> {
                        pathOf(eventPath.substringBeforeLast('/').substringBeforeLast('/', ""))?.let { changed.add(it) }
                    }
                    parentName == "CVS" && name in ROOT_FILES && event !is VFileContentChangeEvent -> rediscover = true
                    event is VFileDeleteEvent || event is VFileMoveEvent ->
                        // A deleted or moved directory may hold roots.
                        pathOf(eventPath)?.let { path -> if (cache.any { it.path.startsWith(path) }) rediscover = true }
                }
            }
        }
        if (rediscover || changed.any { path -> cache.any { path.startsWith(it.path) } }) changed(changed, rediscover)
    }

    /** The cached roots, sorted by path; empty in a project without CVS roots. */
    fun roots(): List<Root> = cache

    /** The cached root that holds [file], the innermost one when roots are nested, or null. */
    fun rootFor(file: VirtualFile): Root? = rootFor(file.path)

    /** The cached root that holds the file at [path] (a VFS or file-system path), the innermost one, or null. */
    fun rootFor(path: String): Root? {
        val file = pathOf(path) ?: return null
        return cache.filter { file.startsWith(it.path) }.maxByOrNull { it.path.nameCount }
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
            roots = roots.map { root ->
                if (root !in toScan || disposed) return@map root
                root.copy(status = root.status.copy(mixedFiles = MixedScan.count(root.path) { onScanDirectory(it); watch(it) }))
            }
            // The roots not scanned now still need their own CVS directory watched, e.g. after a rediscovery.
            roots.filter { it !in toScan }.forEach { watch(it.path) }
            publish(roots)
        } catch (e: Exception) {
            LOG.warn("Cannot compute the CVS branch state", e)
        }
    }

    /**
     * Loads the children of the working-copy directory [dir] and of its `CVS` directory into the VFS, which reports
     * changes only for loaded files (as OpenCVS's `CvsEntriesManager.ensureFilesCached` does). It reads the disk and
     * writes nothing.
     */
    private fun watch(dir: Path) {
        val fileSystem = LocalFileSystem.getInstance()
        val directory = fileSystem.findFileByNioFile(dir) ?: return
        directory.children
        directory.findChild("CVS")?.children
    }

    private fun publish(roots: List<Root>) {
        if (roots == cache || disposed || project.isDisposed) return
        val availabilityChanged = roots.isEmpty() != cache.isEmpty()
        cache = roots
        if (availabilityChanged) {
            // The status-bar widget exists only in a project with CVS roots (design decision 9).
            ApplicationManager.getApplication().invokeLater({
                project.service<StatusBarWidgetsManager>().updateWidget(BranchStatusBarWidgetFactory::class.java)
            }, project.disposed)
        }
        project.messageBus.syncPublisher(TOPIC).stateChanged()
    }

    /**
     * The working-copy roots inside the project's CVS mappings (design decision 17): a mapped working-copy directory
     * is a root itself; below a mapped directory that is not one, every working-copy directory found is a root and is
     * not descended into.
     */
    private fun discoverRoots(): List<Path> {
        discoveries.incrementAndGet()
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

        /** The administrative files whose change can change a root's state or mixed count. */
        private val STATE_FILES = setOf("Tag", "Entries", "Entries.Log")

        /** The administrative files that, next to `Entries`, make a directory a working-copy directory. */
        private val ROOT_FILES = setOf("Root", "Repository")

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
