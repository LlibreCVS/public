package by.devlab.cvs

import com.intellij.cvsSupport2.CvsUpdateEnvironment
import com.intellij.cvsSupport2.CvsVcs2
import com.intellij.cvsSupport2.checkinProject.CvsCheckinEnvironment
import com.intellij.cvsSupport2.config.CvsApplicationLevelConfiguration
import com.intellij.cvsSupport2.config.CvsConfiguration
import com.intellij.cvsSupport2.cvsoperations.cvsAdd.AddedFileInfo
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
    private var realPasswordFileBefore: String? = null

    override fun beforeAll(context: ExtensionContext) {
        realPasswordFileBefore = realPasswordFileFingerprint()
        server.start()
        val configuration = CvsApplicationLevelConfiguration.getInstance()
        previousPasswordFile = configuration.PATH_TO_PASSWORD_FILE
        previousEncoding = configuration.ENCODING
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
            }
        } finally {
            server.close()
        }
        assertEquals(realPasswordFileBefore, realPasswordFileFingerprint(), "the real ~/.cvspass changed")
    }

    /** Sets the plugin's CVS encoding for the rest of the current test, and tells the server about it. */
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

    /** Runs [handler] the way the plugin's actions do and fails with the first CVS error, if any. */
    fun run(handler: CvsHandler, project: Project? = null) {
        // The CVS transcript goes to the test's standard output, so that a failure can be read in the test report.
        handler.addCvsListener(CvsMessagesListener.STANDARD_OUTPUT)
        handler.addCvsListener(object : CvsMessagesAdapter() {
            override fun addError(message: String, path: String?, fs: ICvsFileSystem?, root: String?, warning: Boolean) {
                println("${if (warning) "warning" else "error"}: $message ${path.orEmpty()}")
            }
        })
        val executor = CvsOperationExecutor(false, project, ModalityState.nonModal())
        executor.setShowErrors(false)
        executor.performActionSync(handler, CvsOperationExecutorCallback.EMPTY)
        if (!executor.hasNoErrors()) throw executor.firstError
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
