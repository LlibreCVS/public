package by.devlab.cvs

import com.intellij.cvsSupport2.config.CvsApplicationLevelConfiguration
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo

/**
 * Capability cvs-operations, requirement "Checkout delivers the repository content" (design decisions 5, 14, 15),
 * plus "Real password file stays untouched".
 */
@TestApplication
class CheckoutTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        val WINDOWS_1251: Charset = Charset.forName("windows-1251")
    }

    @Test
    @DisplayName("Checkout keeps UTF-8 Cyrillic names and text")
    fun checkoutKeepsUtf8CyrillicNamesAndText() {
        assertCheckoutKeepsTexts("checkout-utf8", Charsets.UTF_8)
    }

    @Test
    @DisplayName("Checkout keeps windows-1251 Cyrillic names and text")
    fun checkoutKeepsWindows1251CyrillicNamesAndText() {
        assertCheckoutKeepsTexts("checkout-cp1251", WINDOWS_1251)
    }

    @Test
    @DisplayName("Checkout keeps binary files intact")
    fun checkoutKeepsBinaryFilesIntact() {
        session.useEncoding(Charsets.UTF_8)
        val source = TestData.seed(session.newDirectory("checkout-binary-seed"), Charsets.UTF_8)
        session.importModule("alice", source, "checkout-binary", binaryExtensions = listOf("bin"))

        val workingCopy = session.checkout("alice", "checkout-binary", session.newDirectory("checkout-binary-wc"))

        val expected = source.resolve(TestData.BINARY).readBytes()
        assertArrayEquals(expected, workingCopy.resolve(TestData.BINARY).readBytes())
    }

    @Test
    @DisplayName("Real password file stays untouched")
    fun realPasswordFileStaysUntouched() {
        val before = session.realPasswordFileFingerprint()
        session.useEncoding(Charsets.UTF_8)
        val source = TestData.seed(session.newDirectory("checkout-password-seed"), Charsets.UTF_8)
        session.importModule("bob", source, "checkout-password", binaryExtensions = listOf("bin"))
        session.checkout("bob", "checkout-password", session.newDirectory("checkout-password-wc"))

        val passFile = CvsApplicationLevelConfiguration.getInstance().passFile.toPath().toAbsolutePath().normalize()
        assertTrue(passFile.startsWith(TestCvsServer.WORK_ROOT), "$passFile")
        assertEquals(before, session.realPasswordFileFingerprint())
    }

    private fun assertCheckoutKeepsTexts(module: String, charset: Charset) {
        session.useEncoding(charset)
        val source = TestData.seed(session.newDirectory("$module-seed"), charset)
        session.importModule("alice", source, module, binaryExtensions = listOf("bin"))

        val workingCopy = session.checkout("alice", module, session.newDirectory("$module-wc"))

        assertEquals(names(source), names(workingCopy))
        // The fixture converts names symmetrically, so the round trip alone would not notice a plugin that ignored
        // its encoding setting: the names must also arrive on the server as the right text, and CVS/Entries must
        // be written in the configured encoding.
        for (file in TestData.TEXTS + TestData.BINARY) {
            assertTrue(Files.isRegularFile(session.server.repositoryFile("$module/$file,v")), "$file on the server")
        }
        assertEquals("1.1.1.1", session.revision(workingCopy.resolve(TestData.CYRILLIC_TEXT), charset))
        for (text in TestData.TEXTS) {
            assertEquals(
                TestData.text(source.resolve(text), charset),
                TestData.text(workingCopy.resolve(text), charset),
                text,
            )
        }
    }

    /** Relative paths of every file and directory below [top] except the CVS administrative ones, sorted. */
    private fun names(top: Path): List<String> =
        Files.walk(top).use { paths ->
            paths.filter { it != top }
                .map { it.relativeTo(top) }
                .filter { relative -> relative.none { it.toString() == "CVS" } }
                .map { it.toString().replace('\\', '/') }
                .toList().sorted()
        }
}
