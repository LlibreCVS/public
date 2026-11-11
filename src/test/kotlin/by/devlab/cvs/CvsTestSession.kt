package by.devlab.cvs

import com.intellij.cvsSupport2.config.CvsApplicationLevelConfiguration
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

    /** Checks [module] out as [user] into [target] and returns the module's working copy. */
    fun checkout(user: String, module: String, target: Path): Path {
        run(CommandCvsHandler.createCheckoutHandler(environment(user), arrayOf(module), target.toFile(), false, false, null))
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
