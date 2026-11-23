package by.devlab.cvs.checkout

import com.intellij.cvsSupport2.connections.CvsEnvironment
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutor
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutorCallback
import com.intellij.cvsSupport2.cvsExecution.ModalityContext
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler
import com.intellij.cvsSupport2.cvsoperations.common.CvsExecutionEnvironment
import com.intellij.cvsSupport2.cvsoperations.common.CvsOperation
import com.intellij.openapi.application.ModalityState
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Capability cvs-operations, requirement "Checkout continues past files it cannot receive": an unexpected failure in
 * the checkout operation still reaches the executor's final step (design decisions 9, 17).
 */
@TestApplication
class CheckoutHandlerGuardTest {

    /** A checkout operation that fails with [failure] instead of talking to a server. */
    private class FailingOperation(private val failure: Throwable) : CvsOperation() {
        override fun execute(executionEnvironment: CvsExecutionEnvironment, underReadAction: Boolean) = throw failure
        override fun appendSelfCvsRootProvider(roots: MutableCollection<CvsEnvironment>) {}
        override fun getLastProcessedCvsRoot(): String? = null
    }

    @Test
    @DisplayName("An unexpected failure still reaches the final step")
    fun anUnexpectedFailureStillReachesTheFinalStep() {
        val (finished, problems) = runCheckout(FailingOperation(AssertionError("broken invariant")))

        assertEquals(listOf(false), finished)
        assertTrue(problems.lines.any { it.contains("broken invariant") }, "${problems.lines}")
    }

    @Test
    @DisplayName("An unexpected exception still reaches the final step")
    fun anUnexpectedExceptionStillReachesTheFinalStep() {
        // CommandCvsHandler.runOperation reports an ordinary exception with LOG.error, which stays (decision 17).
        val logged = mutableListOf<Throwable?>()
        val tolerant = object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                logged += t
                return Action.NONE
            }
        }
        lateinit var result: Pair<List<Boolean>, CheckoutProblems>
        LoggedErrorProcessor.executeWith<Throwable>(tolerant) {
            result = runCheckout(FailingOperation(IllegalStateException("unexpected state")))
        }

        assertEquals(listOf(false), result.first)
        assertTrue(result.second.lines.any { it.contains("unexpected state") }, "${result.second.lines}")
        assertTrue(logged.any { it is IllegalStateException }, "$logged")
    }

    /** Runs a checkout handler around [operation] the way the checkout does; returns the final-step calls and problems. */
    private fun runCheckout(operation: CvsOperation): Pair<List<Boolean>, CheckoutProblems> {
        val handler = CommandCvsHandler.createCheckoutHandler(operation, null)
        val finished = mutableListOf<Boolean>()
        val callback = object : CvsOperationExecutorCallback {
            override fun executionFinished(successfully: Boolean) {
                finished += successfully
            }

            override fun executionFinishedSuccessfully() {}
            override fun executeInProgressAfterAction(modalityContext: ModalityContext) {}
        }
        val executor = CvsOperationExecutor(false, null, ModalityState.nonModal())
        executor.setShowErrors(false)
        executor.performActionSync(handler, callback)
        return finished to CheckoutProblems.of(handler)
    }
}
