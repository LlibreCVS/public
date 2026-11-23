package by.devlab.cvs.checkout

import by.devlab.cvs.CvsTestSession
import com.intellij.cvsSupport2.cvshandlers.CvsHandler
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/** Checkouts with problems on the throwaway server, shared by the checkout tests (design decision 13). */
object CheckoutFixtures {

    /** The file whose path is blocked in [blockedCheckout]. */
    const val BLOCKED = "файл.txt"

    /** Sorted after [BLOCKED], so the server sends it after the failed file. */
    const val LATER = "ящик.txt"

    /** One checkout: its target directory, the module's working copy and the handler that ran it. */
    data class Checkout(val target: Path, val workingCopy: Path, val handler: CvsHandler)

    /** Imports [module] with `a.txt`, [BLOCKED] and [LATER] and checks it out with [BLOCKED]'s path occupied. */
    fun blockedCheckout(session: CvsTestSession, module: String): Checkout {
        session.useEncoding(Charsets.UTF_8)
        val source = session.newDirectory("$module-seed")
        source.resolve("a.txt").writeText("alpha\n")
        source.resolve(BLOCKED).writeText("blocked\n")
        source.resolve(LATER).writeText("omega\n")
        session.importModule("alice", source, module)

        val target = session.newDirectory("$module-wc")
        // A non-empty directory at the file's path: the writer cannot delete it, so the write fails (decision 18).
        val blocker = Files.createDirectories(target.resolve(module).resolve(BLOCKED))
        blocker.resolve("inside.txt").writeText("keeps the directory non-empty")
        return Checkout(target, target.resolve(module), session.checkoutWithProblems("alice", module, target))
    }

    /** Imports [module] with `a.txt`, `b.txt`, `c.txt`, damages `c.txt`'s RCS file and checks the module out. */
    fun abortedCheckout(session: CvsTestSession, module: String): Checkout {
        session.useEncoding(Charsets.UTF_8)
        val source = session.newDirectory("$module-seed")
        listOf("a", "b", "c").forEach { source.resolve("$it.txt").writeText("$it\n") }
        session.importModule("alice", source, module)
        // A damaged RCS file: the server cannot parse it when it reaches c.txt (design.md risk on damaged RCS).
        val rcsFile = session.server.repositoryFile("$module/c.txt,v")
        rcsFile.toFile().setWritable(true)
        overwrite(rcsFile, "garbage\n")
        val target = session.newDirectory("$module-wc")
        return Checkout(target, target.resolve(module), session.checkoutWithProblems("alice", module, target))
    }

    /** Imports [module] with two files and checks it out; nothing goes wrong. */
    fun cleanCheckout(session: CvsTestSession, module: String): Checkout {
        session.useEncoding(Charsets.UTF_8)
        val source = session.newDirectory("$module-seed")
        listOf("a", "b").forEach { source.resolve("$it.txt").writeText("$it\n") }
        session.importModule("alice", source, module)
        val target = session.newDirectory("$module-wc")
        return Checkout(target, target.resolve(module), session.checkoutWithProblems("alice", module, target))
    }

    /**
     * Writes [text] to [file], waiting while the server process of the previous connection still maps it (Windows
     * refuses to write a file with a mapped section open until that process exits).
     */
    private fun overwrite(file: Path, text: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            try {
                file.writeText(text)
                return
            } catch (e: FileSystemException) {
                if (System.nanoTime() > deadline) throw e
                Thread.sleep(100)
            }
        }
    }
}
