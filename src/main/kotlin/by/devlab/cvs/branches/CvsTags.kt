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
import org.netbeans.lib.cvsclient.event.ICvsListenerRegistry
import org.netbeans.lib.cvsclient.event.IEventSender
import org.netbeans.lib.cvsclient.progress.IProgressViewer
import org.netbeans.lib.cvsclient.progress.sending.DummyRequestsProgressHandler
import org.netbeans.lib.cvsclient.request.CommandRequest
import org.netbeans.lib.cvsclient.request.Requests
import java.io.File
import java.nio.file.Path

/**
 * The repository side of a merge point (cvs-branch-merge decisions 3, 11, 14): moving and deleting the plugin's own
 * tags with `rtag -F -r` and `rtag -d` on a root's module, and reading which revision of each file of a root carries a
 * tag with a header-only `log`. OpenCVS's `RtagCommand` has no `-r`, so the command is the plugin's own.
 */
internal object CvsTags {

    /**
     * Puts [tag] on the revisions [revision] (a tag, a branch or `HEAD`) marks in the module of the working copy [root]
     * with `rtag -F -r`, moving it from where it was; without `-B`, so a branch tag is never moved. Returns the CVS
     * error, or null when it succeeded.
     */
    fun move(project: Project, root: Path, revision: String, tag: String): String? =
        rtag(project, root, "Move merge point", listOf("-F", "-r", revision, tag))

    /**
     * Deletes [tag] from every file of the module of the working copy [root] with `rtag -d`; a branch tag is never
     * deleted (no `-B`). Returns the CVS error, or null when it succeeded.
     */
    fun delete(project: Project, root: Path, tag: String): String? = rtag(project, root, "Delete merge snapshot", listOf("-d", tag))

    /** `rtag [arguments] <module>` of [root]'s module; the CVS error, or null. */
    private fun rtag(project: Project, root: Path, title: String, arguments: List<String>): String? {
        val dir = directory(root) ?: return "cannot find $root"
        val settings = CvsEntriesManager.getInstance().getCvsConnectionSettingsFor(dir)
        val module = CvsEntriesManager.getInstance().getRepositoryFor(dir)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "$root has no CVS/Repository"
        val operation = RtagOperation(settings, modulePath(settings, module), arguments)
        val messages = ArrayList<String>()
        val error = CvsRunner.run(project, CommandCvsHandler(title, operation, false)) { messages += it }
        return error
            ?: messages.firstOrNull { CANNOT_FIND.matches(it.trim()) || "NOT MOVING" in it || "Not moving" in it }
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

    /** [module] (a `CVS/Repository` line) as a path relative to the repository root. */
    private fun modulePath(settings: CvsConnectionSettings, module: String): String {
        val repository = settings.repository.orEmpty().replace('\\', '/').trimEnd('/')
        val path = module.replace('\\', '/')
        return if (repository.isNotEmpty() && path.startsWith("$repository/")) path.removePrefix("$repository/") else path.trimStart('/')
    }

    private fun directory(root: Path): VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)

    /** "cvs rtag: cannot find module `module' - ignored". */
    private val CANNOT_FIND = Regex("""\S+ [^:]+: cannot find module `.+' - ignored""")

    /** `rtag [arguments] <module>` on a repository path, without a working copy. */
    private class RtagOperation(
        settings: CvsConnectionSettings,
        private val module: String,
        private val arguments: List<String>,
    ) : LocalPathIndifferentOperation(settings) {
        @Volatile
        var executed = false

        override fun createCommand(root: CvsRootProvider, environment: CvsExecutionEnvironment): Command {
            executed = true
            return PlainRtagCommand(module, arguments)
        }

        override fun getOperationName(): String = "rtag"
    }

    private class PlainRtagCommand(private val module: String, private val arguments: List<String>) : AbstractCommand() {
        override fun execute(
            requestProcessor: IRequestProcessor,
            eventSender: IEventSender,
            listenerRegistry: ICvsListenerRegistry,
            clientEnvironment: IClientEnvironment,
            progressViewer: IProgressViewer,
        ): Boolean {
            val requests = Requests(CommandRequest.RTAG, clientEnvironment)
            arguments.forEach(requests::addArgumentRequest)
            requests.addArgumentRequest(module)
            return requestProcessor.processRequests(requests, DummyRequestsProgressHandler())
        }

        override fun getCvsCommandLine(): String = "rtag ${arguments.joinToString(" ")} $module"
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
