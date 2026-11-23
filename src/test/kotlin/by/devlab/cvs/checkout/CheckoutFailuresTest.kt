package by.devlab.cvs.checkout

import by.devlab.cvs.CvsTestSession
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Capability cvs-operations, requirement "Checkout continues past files it cannot receive", against the throwaway
 * server (design decision 13): a file that cannot be written and a server abort.
 */
@TestApplication
class CheckoutFailuresTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        const val BLOCKED = "файл.txt"

        /** Sorted after [BLOCKED], so the server sends it after the failed file. */
        const val LATER = "ящик.txt"
    }

    @Test
    @DisplayName("A file that cannot be written does not stop the checkout")
    fun aFileThatCannotBeWrittenDoesNotStopTheCheckout() {
        session.useEncoding(Charsets.UTF_8)
        val module = "checkout-blocked"
        val source = session.newDirectory("$module-seed")
        source.resolve("a.txt").writeText("alpha\n")
        source.resolve(BLOCKED).writeText("blocked\n")
        source.resolve(LATER).writeText("omega\n")
        session.importModule("alice", source, module)

        val target = session.newDirectory("$module-wc")
        // A non-empty directory at the file's path: the writer cannot delete it, so the write fails (decision 18).
        val blocker = Files.createDirectories(target.resolve(module).resolve(BLOCKED))
        blocker.resolve("inside.txt").writeText("keeps the directory non-empty")

        val handler = session.checkoutWithProblems("alice", module, target)

        val workingCopy = target.resolve(module)
        // Text files arrive with the platform's line separator.
        assertEquals("alpha", workingCopy.resolve("a.txt").readText().trimEnd())
        assertEquals("omega", workingCopy.resolve(LATER).readText().trimEnd())
        val entries = workingCopy.resolve("CVS").resolve("Entries").readText(Charsets.UTF_8)
        assertTrue(entries.lines().any { it.startsWith("/a.txt/") }, entries)
        assertTrue(entries.lines().any { it.startsWith("/$LATER/") }, entries)
        assertFalse(entries.lines().any { it.startsWith("/$BLOCKED/") }, entries)

        val problems = CheckoutProblems.of(handler)
        assertEquals(1, problems.lines.size, "${problems.lines}")
        assertTrue(problems.lines[0].contains("$module/$BLOCKED"), problems.lines[0])
        assertFalse(problems.aborted)
    }
}
