package by.devlab.cvs.branches

import com.intellij.cvsSupport2.CvsUtil
import com.intellij.cvsSupport2.application.CvsEntriesManager
import com.intellij.cvsSupport2.connections.CvsConnectionSettings
import com.intellij.cvsSupport2.connections.CvsRootProvider
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler
import com.intellij.cvsSupport2.cvsoperations.common.CvsExecutionEnvironment
import com.intellij.cvsSupport2.cvsoperations.common.LocalPathIndifferentOperation
import com.intellij.cvsSupport2.cvsoperations.cvsLog.LogOperation
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import org.netbeans.lib.cvsclient.IClientEnvironment
import org.netbeans.lib.cvsclient.IRequestProcessor
import org.netbeans.lib.cvsclient.command.AbstractCommand
import org.netbeans.lib.cvsclient.command.Command
import org.netbeans.lib.cvsclient.command.log.LogInformation
import org.netbeans.lib.cvsclient.command.log.LogMessageParser
import org.netbeans.lib.cvsclient.event.ICvsListenerRegistry
import org.netbeans.lib.cvsclient.event.IEventSender
import org.netbeans.lib.cvsclient.progress.IProgressViewer
import org.netbeans.lib.cvsclient.progress.sending.DummyRequestsProgressHandler
import org.netbeans.lib.cvsclient.request.CommandRequest
import org.netbeans.lib.cvsclient.request.Requests
import java.io.File
import java.nio.file.Path

/**
 * The repository side of a merge point (cvs-branch-merge decisions 3, 11, 14): moving the plugin's own tags with
 * `rtag -F -r` on a root's module, deleting a branch and its merge-point tags with `rtag -d` (cvs-branch-delete
 * decision 10), and reading which revision of each file of a root carries a tag with a header-only
 * `log`. OpenCVS's `RtagCommand` has no `-r`, so the command is the plugin's own.
 */
internal object CvsTags {

    /**
     * Puts [tag] on the revisions [revision] (a tag, a branch or `HEAD`) marks in the module of the working copy [root]
     * with `rtag -F -r`, moving it from where it was; without `-B`, so a branch tag is never moved. Returns the CVS
     * error, or null when it succeeded.
     */
    fun move(project: Project, root: Path, revision: String, tag: String): String? =
        rtag(project, root, "Move merge point", listOf("-F", "-r", revision, tag)) { "NOT MOVING" in it || "Not moving" in it }

    /**
     * Deletes the branch tag [branch] from the module of the working copy [root] with `rtag -d -B` (cvs-branch-delete
     * decision 10); a module without the branch prints nothing and succeeds. Returns the CVS error, or null.
     */
    fun deleteBranch(project: Project, root: Path, branch: String): String? =
        rtag(project, root, "Delete branch", listOf("-d", "-B", branch))

    /** Deletes the plain tag [tag] from the module of the working copy [root] with `rtag -d`; the CVS error, or null. */
    fun deleteTag(project: Project, root: Path, tag: String): String? =
        rtag(project, root, "Delete tag", listOf("-d", tag))

    /**
     * Runs `rtag <arguments> <module>` on the module of the working copy [root]. Returns the CVS error, the server's
     * "cannot find module" message, a message [isError] accepts, or that the command did not run; null on success.
     */
    private fun rtag(
        project: Project,
        root: Path,
        title: String,
        arguments: List<String>,
        isError: (String) -> Boolean = { false },
    ): String? {
        val dir = directory(root) ?: return "cannot find $root"
        val settings = CvsEntriesManager.getInstance().getCvsConnectionSettingsFor(dir)
        val module = CvsEntriesManager.getInstance().getRepositoryFor(dir)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "$root has no CVS/Repository"
        val operation = RtagOperation(settings, arguments + modulePath(settings, module))
        val messages = ArrayList<String>()
        val error = CvsRunner.run(project, CommandCvsHandler(title, operation, false)) { messages += it }
        return error
            ?: messages.firstOrNull { CANNOT_FIND.matches(it.trim()) || isError(it) }
            ?: if (!operation.executed) "rtag did not run: CVS root ${settings.cvsRootAsString} is offline or the login failed" else null
    }

    /**
     * The revision of each file of the working copy [root] that carries each of [tags] (tag → path below the root →
     * revision), from a header-only `log` of the root. Throws on a CVS error.
     */
    fun revisions(project: Project, root: Path, tags: Set<String>): Map<String, Map<String, String>> {
        val dir = directory(root) ?: throw IllegalStateException("cannot find $root")
        val settings = CvsEntriesManager.getInstance().getCvsConnectionSettingsFor(dir)
        val module = CvsEntriesManager.getInstance().getRepositoryFor(dir)?.trim().orEmpty()
        val operation = TagLogOperation(root, settings.repository.orEmpty(), module, tags)
        CvsRunner.run(project, CommandCvsHandler("Read merge points", operation, true))?.let { throw IllegalStateException(it) }
        return tags.associateWith { operation.found[it].orEmpty() }
    }

    /**
     * The `rlog -r<branch>` of the module of the working copy [root] (cvs-branch-delete decision 14): per RCS file of
     * the whole module, Attic included, its symbolic names and the revisions on [branch]. Throws on a CVS error, or
     * when the command did not run.
     */
    fun branchLog(project: Project, root: Path, branch: String): List<LogInformation> {
        val dir = directory(root) ?: throw IllegalStateException("cannot find $root")
        val settings = CvsEntriesManager.getInstance().getCvsConnectionSettingsFor(dir)
        val module = CvsEntriesManager.getInstance().getRepositoryFor(dir)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("$root has no CVS/Repository")
        val path = modulePath(settings, module)
        val operation = RlogOperation(settings, listOf("-r$branch", path))
        val messages = ArrayList<String>()
        val error = CvsRunner.run(
            project, CommandCvsHandler("Read branch $branch", operation, true),
            // A file without the branch is not an error: `cvs rlog: warning: no revision `BR' in `…,v'`.
            ignored = { NO_REVISION in it },
        ) { messages += it }
        (error ?: messages.firstOrNull { CANNOT_FIND.matches(it.trim()) })?.let { throw IllegalStateException(it) }
        if (!operation.executed) throw IllegalStateException("rlog did not run: CVS root ${settings.cvsRootAsString} is offline or the login failed")
        return operation.files
    }

    /** cvs 1.11 on a file without the branch asked for. */
    private val NO_REVISION = Regex("""warning: no revision `.*' in `""")

    /**
     * Where the module of the working copy [root] lives, as [locationKey] of its `CVS/Root` and `CVS/Repository`, or
     * null when they cannot be read. Read from the working copy only.
     */
    fun moduleLocation(root: Path): String? {
        val dir = directory(root) ?: return null
        val settings = CvsEntriesManager.getInstance().getCvsConnectionSettingsFor(dir)
        val module = CvsEntriesManager.getInstance().getRepositoryFor(dir)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return locationKey(settings.cvsRootAsString, module)
    }

    /**
     * One spelling for the module [module] (a `CVS/Repository` line) of the repository [cvsRoot] (a `CVS/Root` line),
     * so that two checkouts of one module compare equal (cvs-branch-delete decision 15): `method://host:port/path`,
     * without the user and password, the host in lower case, the pserver port 2401 when none is given, an implicit
     * `user@host:path` as `ext`, and the module `.` as the repository itself.
     */
    internal fun locationKey(cvsRoot: String, module: String): String {
        var rest = cvsRoot.trim()
        var method = "local"
        if (rest.startsWith(":")) {
            method = rest.substring(1).substringBefore(':').substringBefore(';').lowercase()
            rest = rest.substring(1).substringAfter(':')
        } else if (!rest.startsWith("/") && Regex("^[^/]*:").containsMatchIn(rest) && !Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(rest)) {
            method = "ext"
        }
        val pathStart = rest.indexOf('/').let { if (it < 0) rest.length else it }
        val server = rest.substring(0, pathStart).let { if ('@' in it) it.substringAfterLast('@') else it }
        val repository = rest.substring(pathStart).replace('\\', '/').trimEnd('/')
        val host = server.substringBefore(':').lowercase()
        val port = server.substringAfter(':', "").trim(':').ifEmpty { if (method == "pserver") "2401" else "" }
        val base = if (host.isEmpty()) "$method:$repository" else "$method://$host:$port$repository"
        val path = module.replace('\\', '/').trim().let {
            if (repository.isNotEmpty() && (it == repository || it.startsWith("$repository/"))) it.removePrefix(repository) else it
        }.trim('/')
        return if (path.isEmpty() || path == ".") base else "$base/$path"
    }

    /** [module] (a `CVS/Repository` line) as a path relative to the repository root. */
    private fun modulePath(settings: CvsConnectionSettings, module: String): String {
        val repository = settings.repository.orEmpty().replace('\\', '/').trimEnd('/')
        val path = module.replace('\\', '/')
        return if (repository.isNotEmpty() && path.startsWith("$repository/")) path.removePrefix("$repository/") else path.trimStart('/')
    }

    private fun directory(root: Path): VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)

    /** "cvs rtag: cannot find module `module' - ignored". */
    private val CANNOT_FIND = Regex("""\S+ [^:]+: cannot find module `.+' - ignored""")

    /** `rtag <arguments>` on a repository path (the module is the last argument), without a working copy. */
    private class RtagOperation(settings: CvsConnectionSettings, private val arguments: List<String>) :
        LocalPathIndifferentOperation(settings) {
        @Volatile
        var executed = false

        override fun createCommand(root: CvsRootProvider, environment: CvsExecutionEnvironment): Command {
            executed = true
            return RtagArgumentsCommand(arguments)
        }

        override fun getOperationName(): String = "rtag"
    }

    private class RtagArgumentsCommand(private val arguments: List<String>) : AbstractCommand() {
        override fun execute(
            requestProcessor: IRequestProcessor,
            eventSender: IEventSender,
            listenerRegistry: ICvsListenerRegistry,
            clientEnvironment: IClientEnvironment,
            progressViewer: IProgressViewer,
        ): Boolean {
            val requests = Requests(CommandRequest.RTAG, clientEnvironment)
            arguments.forEach { requests.addArgumentRequest(it) }
            return requestProcessor.processRequests(requests, DummyRequestsProgressHandler())
        }

        override fun getCvsCommandLine(): String = "rtag ${arguments.joinToString(" ")}"
    }

    /** `rlog <arguments>` on a repository path (the module is the last argument), collecting each file's log. */
    private class RlogOperation(settings: CvsConnectionSettings, private val arguments: List<String>) :
        LocalPathIndifferentOperation(settings) {
        @Volatile
        var executed = false

        val files = ArrayList<LogInformation>()

        override fun createCommand(root: CvsRootProvider, environment: CvsExecutionEnvironment): Command {
            executed = true
            return RlogCommand(arguments)
        }

        override fun fileInfoGenerated(info: Any?) {
            super.fileInfoGenerated(info)
            if (info is LogInformation) files += info
        }

        override fun getOperationName(): String = "rlog"
    }

    private class RlogCommand(private val arguments: List<String>) : AbstractCommand() {
        override fun execute(
            requestProcessor: IRequestProcessor,
            eventSender: IEventSender,
            listenerRegistry: ICvsListenerRegistry,
            clientEnvironment: IClientEnvironment,
            progressViewer: IProgressViewer,
        ): Boolean {
            val requests = Requests(CommandRequest.RLOG, clientEnvironment)
            arguments.forEach { requests.addArgumentRequest(it) }
            val parser = LogMessageParser(eventSender, clientEnvironment.cvsFileSystem)
            parser.registerListeners(listenerRegistry)
            try {
                return requestProcessor.processRequests(requests, DummyRequestsProgressHandler())
            } finally {
                parser.unregisterListeners(listenerRegistry)
            }
        }

        override fun getCvsCommandLine(): String = "rlog ${arguments.joinToString(" ")}"
    }

    /** OpenCVS's header-only log of a root, keeping the revisions of [tags] per file below the root. */
    private class TagLogOperation(
        private val root: Path,
        repository: String,
        module: String,
        private val tags: Set<String>,
    ) : LogOperation(listOf(VcsUtil.getFilePath(root.toFile(), true))) {
        val found = HashMap<String, MutableMap<String, String>>()

        /** The repository directory of the root's module, as RCS file names start with it. */
        private val moduleDir: String = run {
            val path = module.replace('\\', '/').trimEnd('/')
            val repo = repository.replace('\\', '/').trimEnd('/')
            if (path.startsWith("/") || path.contains(':')) path else "$repo/$path"
        }
        private val moduleName = module.replace('\\', '/').trim('/').substringAfterLast('/')

        override fun fileInfoGenerated(info: Any?) {
            super.fileInfoGenerated(info)
            if (info !is LogInformation) return
            val path = relative(info) ?: return
            for (name in info.allSymbolicNames) {
                if (name.name in tags) found.getOrPut(name.name, ::HashMap)[path] = name.revision
            }
        }

        /** The file's path below the root: from its working file when absolute, else from its RCS file name. */
        private fun relative(info: LogInformation): String? {
            val file: File? = info.file
            if (file != null && file.isAbsolute) {
                val path = file.toPath().toAbsolutePath().normalize()
                if (path.startsWith(root)) return root.relativize(path).toString().replace('\\', '/')
            }
            val rcs = info.rcsFileName?.replace('\\', '/')?.removeSuffix(",v") ?: return null
            val name = rcs.substringAfterLast('/')
            var dir = rcs.substringBeforeLast('/', "")
            if (dir.endsWith("/Attic")) dir = dir.removeSuffix("/Attic")
            val full = "$dir/$name"
            if (full.startsWith("$moduleDir/")) return full.removePrefix("$moduleDir/")
            val marker = "/$moduleName/"
            val index = full.indexOf(marker)
            return if (index >= 0) full.substring(index + marker.length) else null
        }
    }
}

/** The `CVS/Entries` revision of [file] (`0` for an added file, `-<rev>` for a removed one), or null when not listed. */
internal fun entryRevision(file: Path): String? {
    val parent = file.parent ?: return null
    return try {
        CvsUtil.getEntriesIn(parent.toFile())?.getEntry(file.fileName.toString())?.revision
    } catch (_: Exception) {
        null
    }
}
