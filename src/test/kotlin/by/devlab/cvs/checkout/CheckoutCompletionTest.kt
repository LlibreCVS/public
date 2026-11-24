package by.devlab.cvs.checkout

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.checkout.CheckoutFixtures.BLOCKED
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vcs.CheckoutProvider
import com.intellij.openapi.vcs.VcsKey
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Capability cvs-operations, requirement "Checkout ends with one problem report that asks before creating the
 * project" (design decision 12): [CheckoutCompletion] after real checkouts on the throwaway server, with recording
 * prompts and a recording listener.
 */
@TestApplication
class CheckoutCompletionTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()
    }

    /** One call the listener got: its name and whether it ran on the EDT. */
    private data class Call(val name: String, val onEdt: Boolean)

    /**
     * Records what the checkout told the "Get from Version Control" listener, with the thread of each call;
     * [done] is released by `checkoutCompleted`.
     */
    private class RecordingListener : CheckoutProvider.Listener {
        val checkedOut: MutableList<File> = Collections.synchronizedList(mutableListOf())
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
        @Volatile
        var completed = 0
        val done = CountDownLatch(1)

        override fun directoryCheckedOut(directory: File, vcs: VcsKey) {
            calls += Call("directoryCheckedOut", SwingUtilities.isEventDispatchThread())
            checkedOut += directory
        }

        override fun checkoutCompleted() {
            calls += Call("checkoutCompleted", SwingUtilities.isEventDispatchThread())
            completed++
            done.countDown()
        }
    }

    private val prompts = TestCheckoutPrompts()
    private val listener = RecordingListener()

    @Test
    @DisplayName("Checkout without problems creates the project without asking")
    fun checkoutWithoutProblemsCreatesTheProjectWithoutAsking() {
        val module = "completion-clean"
        val checkout = CheckoutFixtures.cleanCheckout(session, module)

        complete(module, checkout)

        assertEquals(emptyList<TestCheckoutPrompts.Question>(), prompts.asked)
        assertEquals(listOf(checkout.workingCopy.toFile()), listener.checkedOut)
        assertEquals(1, listener.completed)
    }

    @Test
    @DisplayName("The user creates the project despite failed files")
    fun theUserCreatesTheProjectDespiteFailedFiles() {
        val module = "completion-create"
        val checkout = CheckoutFixtures.blockedCheckout(session, module)
        prompts.createProject = true

        complete(module, checkout)

        val question = prompts.asked.single()
        assertEquals(module, question.module)
        assertTrue(question.problems.lines.any { it.contains("$module/$BLOCKED") && it.contains("Could not delete file") },
                   "${question.problems.lines}")
        assertEquals(listOf(checkout.workingCopy.toFile()), listener.checkedOut)
        assertEquals(1, listener.completed)
    }

    @Test
    @DisplayName("The user declines the project")
    fun theUserDeclinesTheProject() {
        val module = "completion-decline"
        val checkout = CheckoutFixtures.blockedCheckout(session, module)
        prompts.createProject = false

        complete(module, checkout)

        assertEquals(1, prompts.asked.size)
        assertEquals(emptyList<File>(), listener.checkedOut)
        assertEquals(1, listener.completed)
        assertTrue(Files.isRegularFile(checkout.workingCopy.resolve("a.txt")))
        assertTrue(Files.isRegularFile(checkout.workingCopy.resolve(CheckoutFixtures.LATER)))
    }

    @Test
    @DisplayName("A server abort is named in the question")
    fun aServerAbortIsNamedInTheQuestion() {
        val module = "completion-abort"
        val checkout = CheckoutFixtures.abortedCheckout(session, module)

        complete(module, checkout)

        val question = prompts.asked.single()
        assertTrue(question.problems.aborted, "${question.problems.lines}")
        assertTrue(question.problems.abortMessage!!.startsWith("cvs [checkout aborted]: "), question.problems.abortMessage)
    }

    @Test
    @DisplayName("Project is opened off the EDT")
    fun projectIsOpenedOffTheEdt() {
        val module = "completion-thread"
        val checkout = CheckoutFixtures.cleanCheckout(session, module)

        complete(module, checkout)

        assertEquals(
            listOf(Call("directoryCheckedOut", false), Call("checkoutCompleted", false)),
            listener.calls.toList(),
        )
    }

    /**
     * Calls [CheckoutCompletion] on the EDT, as the executor's callback does, and waits until the listener's checkout
     * completed (design decision 19: the listener calls run on a pooled thread).
     */
    private fun complete(module: String, checkout: CheckoutFixtures.Checkout) {
        ApplicationManager.getApplication().invokeAndWait {
            CheckoutCompletion(prompts).complete(
                module, CheckoutProblems.of(checkout.handler), listOf(checkout.workingCopy.toFile()), listener,
            )
        }
        assertTrue(listener.done.await(30, TimeUnit.SECONDS), "checkoutCompleted was not called")
    }
}
