package by.devlab.cvs

import com.intellij.cvsSupport2.CvsUpdateEnvironment
import com.intellij.cvsSupport2.CvsVcs2
import com.intellij.cvsSupport2.changeBrowser.CvsChangeList
import com.intellij.cvsSupport2.changeBrowser.CvsCommittedChangesProvider
import com.intellij.cvsSupport2.changeBrowser.CvsRepositoryLocation
import com.intellij.cvsSupport2.checkinProject.CvsCheckinEnvironment
import com.intellij.cvsSupport2.config.CvsApplicationLevelConfiguration
import com.intellij.cvsSupport2.config.CvsConfiguration
import com.intellij.cvsSupport2.config.DateOrRevisionSettings
import com.intellij.cvsSupport2.cvsoperations.cvsAdd.AddedFileInfo
import com.intellij.cvsSupport2.cvsoperations.cvsLog.LogOperation
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.annotate.LineAnnotationAspect
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.update.UpdatedFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import com.intellij.cvsSupport2.config.CvsRootConfiguration
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutor
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutorCallback
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler
import com.intellij.cvsSupport2.cvshandlers.CvsHandler
import com.intellij.cvsSupport2.cvsoperations.cvsImport.ImportDetails
import com.intellij.cvsSupport2.cvsoperations.cvsMessages.CvsMessagesAdapter
import com.intellij.cvsSupport2.cvsoperations.cvsMessages.CvsMessagesListener
import org.netbeans.lib.cvsclient.file.ICvsFileSystem
import com.intellij.cvsSupport2.keywordSubstitution.KeywordSubstitutionWrapper
import com.intellij.cvsSupport2.ui.experts.importToCvs.FileExtension
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.netbeans.lib.cvsclient.connection.PServerPasswordScrambler
import org.netbeans.lib.cvsclient.util.IIgnoreFileFilter
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * One throwaway server per test class plus the plugin settings that point at it (design decisions 9, 12, 14, 15).
 * Register it in a companion object: `@JvmField @RegisterExtension val session = CvsTestSession()`.
 *
 * - The plugin's password file is a file under `build/` holding one entry per test user, written in the format
 *   the plugin itself writes on login; the previous setting is restored after the class.
 * - The CVS encoding is set per test with [useEncoding] and restored after each test.
 * - The size and SHA-256 of the user's real `~/.cvspass` are recorded before the class and checked after it.
 * - Operations run through the plugin's own handlers and executor, never through a native client.
 */
class CvsTestSession : BeforeAllCallback, AfterAllCallback, AfterEachCallback {
    val server = TestCvsServer()

    private var previousPasswordFile: String? = null
    private var previousEncoding: String = CvsApplicationLevelConfiguration.DEFAULT
    private var previousGzip = false
    private var realPasswordFileBefore: String? = null

    override fun beforeAll(context: ExtensionContext) {
        realPasswordFileBefore = realPasswordFileFingerprint()
        // Captured before the server starts, so that a failed start restores exactly what was there.
        val configuration = CvsApplicationLevelConfiguration.getInstance()
        previousPasswordFile = configuration.PATH_TO_PASSWORD_FILE
        previousEncoding = configuration.ENCODING
        previousGzip = configuration.USE_GZIP
        // The fixture's transcoder reads the protocol line by line; a gzip stream would hide the lines.
        configuration.USE_GZIP = false
        server.start()
        val passwordFile = server.workDirectory.resolve("cvspass")
        val scrambled = PServerPasswordScrambler.getInstance().scramble(TestCvsServer.PASSWORD)
        Files.writeString(passwordFile, TestCvsServer.USERS.joinToString("") { "${server.cvsRoot(it)} $scrambled\n" })
        configuration.PATH_TO_PASSWORD_FILE = passwordFile.toString()
    }

    override fun afterEach(context: ExtensionContext) {
        CvsApplicationLevelConfiguration.getInstance().ENCODING = previousEncoding
        server.clientCharset = Charsets.UTF_8
    }

    override fun afterAll(context: ExtensionContext) {
        try {
            CvsApplicationLevelConfiguration.getInstance().apply {
                PATH_TO_PASSWORD_FILE = previousPasswordFile
                ENCODING = previousEncoding
                USE_GZIP = previousGzip
            }
        } finally {
            server.close()
        }
        assertEquals(realPasswordFileBefore, realPasswordFileFingerprint(), "the real ~/.cvspass changed")
    }

    /**
     * Sets the plugin's CVS encoding for the rest of the current test, and tells the server about it. Call it before
     * the test's first CVS operation: the server picks a connection's conversion when the connection opens.
     */
    fun useEncoding(charset: Charset) {
        CvsApplicationLevelConfiguration.getInstance().ENCODING = charset.name()
        server.clientCharset = charset
    }

    /** A new empty directory under the server's work directory. */
    fun newDirectory(name: String): Path = Files.createTempDirectory(server.workDirectory, "$name-")

    /** The CVS settings through which [user] reaches the server. */
    fun environment(user: String): CvsRootConfiguration =
        CvsApplicationLevelConfiguration.createNewConfiguration(CvsApplicationLevelConfiguration.getInstance()).apply {
            CVS_ROOT = server.cvsRoot(user)
        }

    /**
     * Imports the content of [source] as [module] through the plugin's import operation; files with one of
     * [binaryExtensions] are imported as binary (`-kb`), the way the import wizard's file types do it.
     */
    fun importModule(user: String, source: Path, module: String, binaryExtensions: List<String> = emptyList()) {
        val wrappers = binaryExtensions.map { FileExtension(it).apply { setKeywordSubstitution(KeywordSubstitutionWrapper.BINARY) } }
        val details = ImportDetails(
            source.toFile(), "vendor", "start", "Initial import of $module", module, environment(user), wrappers,
            IIgnoreFileFilter { _, _ -> false },
        )
        run(CommandCvsHandler.createImportHandler(details))
        // The plugin reports a server-side import failure only as a message; fail here, not at a later assertion.
        check(Files.isDirectory(server.repositoryFile(module))) { "the import of $module created nothing on the server" }
    }

    /** Checks [module] out as [user] into [target] (at [tag], if given) and returns the module's working copy. */
    fun checkout(user: String, module: String, target: Path, tag: String? = null): Path {
        val environment = environment(user).apply {
            // What the checkout dialog's "branch or tag" field sets.
            if (tag != null) DATE_OR_REVISION_SETTINGS.apply { USE_BRANCH = true; BRANCH = tag }
        }
        run(CommandCvsHandler.createCheckoutHandler(environment, arrayOf(module), target.toFile(), false, false, null))
        return target.resolve(module)
    }

    /**
     * Runs [handler] the way the plugin's actions do and fails with the first CVS error, if any, also one the plugin
     * reports only as an error message.
     */
    fun run(handler: CvsHandler, project: Project? = null) {
        val errors = mutableListOf<String>()
        // The CVS transcript goes to the test's standard output, so that a failure can be read in the test report.
        handler.addCvsListener(CvsMessagesListener.STANDARD_OUTPUT)
        handler.addCvsListener(object : CvsMessagesAdapter() {
            override fun addError(message: String, path: String?, fs: ICvsFileSystem?, root: String?, warning: Boolean) {
                println("${if (warning) "warning" else "error"}: $message ${path.orEmpty()}")
                if (!warning) errors += "$message ${path.orEmpty()}".trim()
            }
        })
        val executor = CvsOperationExecutor(false, project, ModalityState.nonModal())
        executor.setShowErrors(false)
        executor.performActionSync(handler, CvsOperationExecutorCallback.EMPTY)
        if (!executor.hasNoErrors()) throw executor.firstError
        if (errors.isNotEmpty()) throw AssertionError("CVS reported errors: $errors")
    }

    /** Maps [roots] (working copies) to CVS in [project], next to the mappings it already has. */
    fun mapToCvs(project: Project, vararg roots: Path) {
        val manager = ProjectLevelVcsManager.getInstance(project)
        val added = roots.map { VcsDirectoryMapping(virtualFile(it).path, CvsVcs2.getKey().name) }
        manager.directoryMappings = manager.directoryMappings.filter { it.directory.isNotEmpty() } + added
    }

    /**
     * Schedules [paths] for addition the way the add dialog does on OK: one [AddedFileInfo] per path, nested
     * under its parent's when that is added too, handed to the plugin's add handler. Directories come first.
     */
    fun add(project: Project, vararg paths: Path) {
        val configuration = CvsConfiguration.getInstance(project)
        val infos = paths.associateWith { AddedFileInfo(virtualFile(it), project, configuration) }
        infos.forEach { (path, info) -> infos[path.parent]?.let { info.setParent(it) } }
        val roots = infos.filterKeys { it.parent !in infos }.values
        run(CommandCvsHandler.createAddFilesHandler(project, roots), project)
    }

    /** Commits [files] through the plugin's checkin environment and fails on any CVS error. */
    fun commit(project: Project, files: List<Path>, message: String) {
        val changes = files.map { Change(null, CurrentContentRevision(VcsUtil.getFilePath(virtualFile(it)))) }
        val errors = CvsCheckinEnvironment(project).commit(changes, message, CommitContext(), mutableSetOf())
        errors?.firstOrNull()?.let { throw it }
    }

    /** Commits [files] with the commit dialog's tag-after-commit option set to [tag]. */
    fun commitWithTag(project: Project, files: List<Path>, message: String, tag: String) {
        val configuration = CvsConfiguration.getInstance(project)
        val (previousOption, previousName) = configuration.TAG_AFTER_PROJECT_COMMIT to configuration.TAG_AFTER_PROJECT_COMMIT_NAME
        configuration.TAG_AFTER_PROJECT_COMMIT = true
        configuration.TAG_AFTER_PROJECT_COMMIT_NAME = tag
        try {
            commit(project, files, message)
        } finally {
            configuration.TAG_AFTER_PROJECT_COMMIT = previousOption
            configuration.TAG_AFTER_PROJECT_COMMIT_NAME = previousName
        }
    }

    /** Starts branch [branch] in the repository from the current trunk revisions of [module]. */
    fun branch(module: String, branch: String) {
        server.cvs("rtag", "-b", branch, module)
    }

    /**
     * Creates branch [branch] on the working copy [root] the way the Create Branch action does after OK in its dialog
     * (design decision 17): the root as the selected file, "override existing" off, read-only setting from the
     * configuration; with [switch] the working copy is then updated to the branch.
     */
    fun createBranch(project: Project, root: Path, branch: String, switch: Boolean) {
        val readOnly = CvsConfiguration.getInstance(project).MAKE_NEW_FILES_READONLY
        val selected = arrayOf(VcsUtil.getFilePath(virtualFile(root)))
        run(CommandCvsHandler.createBranchHandler(selected, branch, switch, false, readOnly, project), project)
        virtualFile(root).refresh(false, true)
    }

    /**
     * The branch names the branch chooser offers for the working copy [root] (design decision 16): a header-only
     * `log` through a [CommandCvsHandler], as the chooser's private `TagsHelper.collectAllBranches` runs it.
     */
    fun branches(project: Project, root: Path): Collection<String> {
        val operation = LogOperation(listOf(VcsUtil.getFilePath(virtualFile(root))))
        run(CommandCvsHandler("load tags", operation, true), project)
        return operation.allBranches
    }

    /** The sticky tag CVS/Entries records for [file] (without its `T`), or "" on the trunk (design decision 15). */
    fun stickyTag(file: Path, charset: Charset = Charsets.UTF_8): String =
        entry(file, charset).split('/').getOrElse(5) { "" }.removePrefix("T")

    /** Every file of the working copy [root], CVS administrative files left out. */
    fun workingFiles(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.parent.fileName.toString() != "CVS" }.toList().sorted()
        }

    /** Deletes [file] from the working copy, schedules its removal through the plugin and commits it. */
    fun removeAndCommit(project: Project, file: Path, message: String) {
        Files.delete(file)
        virtualFile(file.parent)
        run(CommandCvsHandler.createRemoveFilesHandler(project, listOf(file.toFile())), project)
        val change = Change(CurrentContentRevision(VcsUtil.getFilePath(file.toFile(), false)), null)
        val errors = CvsCheckinEnvironment(project).commit(listOf(change), message, CommitContext(), mutableSetOf())
        errors?.firstOrNull()?.let { throw it }
    }

    /**
     * The revision the tag [tag] points to in the repository file [path] (`module/file.txt`), read from the RCS file
     * with the fixture's own `cvs rlog -h`, or null when the file does not carry the tag.
     */
    fun tagOf(path: String, tag: String): String? {
        val log = server.cvs("rlog", "-h", path)
        val names = log.lines().dropWhile { !it.startsWith("symbolic names:") }.drop(1).takeWhile { it.startsWith("\t") }
        return names.map { it.trim().split(": ") }.firstOrNull { it[0] == tag }?.get(1)
    }

    /** The keyword substitution CVS/Entries records for [file] (e.g. `-kb`), or "" for the default. */
    fun keywordSubstitution(file: Path, charset: Charset = Charsets.UTF_8): String = entry(file, charset).split('/')[4]

    /** One revision as the history shows it. */
    data class Revision(val number: String, val author: String, val message: String)

    /** The history of [file] from the CVS history provider, newest first. */
    fun history(project: Project, file: Path): List<Revision> {
        val provider = checkNotNull(CvsVcs2.getInstance(project).vcsHistoryProvider)
        val session = checkNotNull(provider.createSessionFor(VcsUtil.getFilePath(virtualFile(file)))) { "no history for $file" }
        return session.revisionList
            .sortedByDescending { it.revisionNumber }
            .map { Revision(it.revisionNumber.asString(), it.author.orEmpty(), it.commitMessage.orEmpty().trimEnd()) }
    }

    /** The repository location of the working copy [root] as the Repository view gets it from the provider. */
    fun repositoryLocation(project: Project, root: Path): CvsRepositoryLocation =
        checkNotNull(committedChangesProvider(project).getLocationFor(VcsUtil.getFilePath(virtualFile(root)))) {
            "no repository location for $root"
        }

    /** The committed changesets of the working copy [root], loaded the way the Repository view loads them. */
    fun changesets(project: Project, root: Path): List<CvsChangeList> {
        val provider = committedChangesProvider(project)
        return provider.getCommittedChanges(provider.createDefaultSettings(), repositoryLocation(project, root), 0)
    }

    private fun committedChangesProvider(project: Project): CvsCommittedChangesProvider =
        CvsVcs2.getInstance(project).committedChangesProvider as CvsCommittedChangesProvider

    /** One line as annotate shows it. */
    data class AnnotatedLine(val revision: String, val author: String, val text: String)

    /** [file] annotated through the CVS annotation provider, line by line. */
    fun annotate(project: Project, file: Path): List<AnnotatedLine> {
        val provider = checkNotNull(CvsVcs2.getInstance(project).annotationProvider)
        val annotation = provider.annotate(virtualFile(file))
        try {
            val author = annotation.aspects.first { it.id == LineAnnotationAspect.AUTHOR }
            val text = annotation.annotatedContent.orEmpty().lines()
            return (0 until annotation.lineCount).map { line ->
                AnnotatedLine(annotation.getLineRevisionNumber(line)?.asString().orEmpty(), author.getValue(line), text[line])
            }
        } finally {
            annotation.dispose()
        }
    }

    /** Updates the working copy [root] through the plugin's update environment and fails on any CVS error. */
    fun update(project: Project, root: Path) {
        val session = CvsUpdateEnvironment(project).updateDirectories(
            arrayOf(VcsUtil.getFilePath(virtualFile(root))), UpdatedFiles.create(), EmptyProgressIndicator(), Ref(),
        )
        session.exceptions.firstOrNull()?.let { throw it }
        virtualFile(root).refresh(false, true)
    }

    /**
     * What the Update dialog sets on OK for a switch or a merge (design decision 8): [branch] is the "branch or tag"
     * field (`-r`), [resetSticky] the reset-sticky option (`-A`), [merge1] and [merge2] the merge fields (`-j`, and a
     * second `-j` with [merge2]).
     */
    data class UpdateOptions(
        val branch: String? = null,
        val resetSticky: Boolean = false,
        val merge1: String? = null,
        val merge2: String? = null,
    )

    /**
     * Updates the working copy [root] the way the IDE does after OK in the Update dialog with [options] (design
     * decisions 12, 13, 14) and returns the update result. A merge conflict does not fail it; other CVS errors do.
     */
    fun updateWith(project: Project, root: Path, options: UpdateOptions = UpdateOptions()): UpdatedFiles {
        // With both, the environment splits the update in two through its context; the IDE loops over that, the
        // fixture does not (design decision 13).
        require(options.branch == null || options.merge1 == null) { "a switch and a merge run as separate updates" }
        require(options.merge2 == null || options.merge1 != null) { "a second merge point needs the first" }
        if (options.merge1 != null) throw NotImplementedError("merge")
        val environment = CvsUpdateEnvironment(project)
        val roots = listOf(VcsUtil.getFilePath(virtualFile(root)))
        // What the IDE calls when it shows the Update dialog: without it, updateDirectories drops RESET_STICKY. It
        // resets the flags itself, so the dialog's settings go in after it (design decision 12).
        environment.createConfigurable(roots)
        val configuration = CvsConfiguration.getInstance(project)
        val previousRevision = configuration.UPDATE_DATE_OR_REVISION_SETTINGS
        configuration.UPDATE_DATE_OR_REVISION_SETTINGS = DateOrRevisionSettings().apply {
            if (options.branch != null) {
                USE_BRANCH = true
                BRANCH = options.branch
            }
        }
        configuration.RESET_STICKY = options.resetSticky
        val updated = UpdatedFiles.create()
        try {
            val session = environment.updateDirectories(roots.toTypedArray(), updated, EmptyProgressIndicator(), Ref())
            session.exceptions.firstOrNull { !it.isWarning }?.let { throw it }
        } finally {
            configuration.UPDATE_DATE_OR_REVISION_SETTINGS = previousRevision
            configuration.RESET_STICKY = false
        }
        virtualFile(root).refresh(false, true)
        return updated
    }

    /** The revision CVS/Entries records for [file], read in [charset]. */
    fun revision(file: Path, charset: Charset = Charsets.UTF_8): String = entry(file, charset).split('/')[2]

    /** The CVS/Entries line of [file]: `/name/revision/timestamp/options/tag`. */
    private fun entry(file: Path, charset: Charset): String {
        val entries = file.resolveSibling("CVS").resolve("Entries").readBytes().toString(charset)
        return entries.lines().firstOrNull { it.startsWith("/${file.fileName}/") }
            ?: throw AssertionError("no entry for ${file.fileName} in $entries")
    }

    /** The VFS file for [path], refreshed from disk first so that new and edited files are seen. */
    private fun virtualFile(path: Path): VirtualFile =
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)) { "no VFS file for $path" }
            .also { it.refresh(false, it.isDirectory) }

    /** Size and SHA-256 of the user's real `~/.cvspass`; `user.home` is redirected, so the home comes from the environment. */
    fun realPasswordFileFingerprint(): String {
        val home = System.getenv("USERPROFILE") ?: System.getenv("HOME") ?: return "no home"
        val file = Path.of(home, ".cvspass")
        if (!file.exists()) return "missing"
        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "${bytes.size} bytes, sha256 $digest"
    }
}
