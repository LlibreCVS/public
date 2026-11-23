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

    @Test
    @DisplayName("A server abort ends the checkout with what arrived")
    fun aServerAbortEndsTheCheckoutWithWhatArrived() {
        session.useEncoding(Charsets.UTF_8)
        val module = "checkout-abort"
        val source = session.newDirectory("$module-seed")
        listOf("a", "b", "c").forEach { source.resolve("$it.txt").writeText("$it\n") }
        session.importModule("alice", source, module)
        // A damaged RCS file: the server cannot parse it when it reaches c.txt (design.md risk on damaged RCS).
        val rcsFile = session.server.repositoryFile("$module/c.txt,v")
        rcsFile.toFile().setWritable(true)
        overwrite(rcsFile, "garbage\n")

        val checkoutsBefore = checkoutRequests()
        val handler = session.checkoutWithProblems("alice", module, session.newDirectory("$module-wc").also { target = it })

        val workingCopy = target.resolve(module)
        assertEquals("a", workingCopy.resolve("a.txt").readText().trimEnd())
        assertEquals("b", workingCopy.resolve("b.txt").readText().trimEnd())
        val problems = CheckoutProblems.of(handler)
        assertTrue(problems.aborted, "${problems.lines}")
        assertTrue(problems.abortMessage!!.contains("aborted"), problems.abortMessage)
        assertEquals(1, checkoutRequests() - checkoutsBefore, "checkout requests sent")
    }

    private lateinit var target: java.nio.file.Path

    /**
     * Writes [text] to [file], waiting while the server process of the previous connection still maps it (Windows
     * refuses to write a file with a mapped section open until that process exits).
     */
    private fun overwrite(file: java.nio.file.Path, text: String) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
        while (true) {
            try {
                file.writeText(text)
                return
            } catch (e: java.nio.file.FileSystemException) {
                if (System.nanoTime() > deadline) throw e
                Thread.sleep(100)
            }
        }
    }

    private fun checkoutRequests(): Int = session.server.requestLines().count { it == "co" }
}
