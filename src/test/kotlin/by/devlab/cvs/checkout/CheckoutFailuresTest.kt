package by.devlab.cvs.checkout

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.checkout.CheckoutFixtures.BLOCKED
import by.devlab.cvs.checkout.CheckoutFixtures.LATER
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.io.path.readText

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
    }

    @Test
    @DisplayName("A file that cannot be written does not stop the checkout")
    fun aFileThatCannotBeWrittenDoesNotStopTheCheckout() {
        val module = "checkout-blocked"
        val checkout = CheckoutFixtures.blockedCheckout(session, module)

        val workingCopy = checkout.workingCopy
        // Text files arrive with the platform's line separator.
        assertEquals("alpha", workingCopy.resolve("a.txt").readText().trimEnd())
        assertEquals("omega", workingCopy.resolve(LATER).readText().trimEnd())
        val entries = workingCopy.resolve("CVS").resolve("Entries").readText(Charsets.UTF_8)
        assertTrue(entries.lines().any { it.startsWith("/a.txt/") }, entries)
        assertTrue(entries.lines().any { it.startsWith("/$LATER/") }, entries)
        assertFalse(entries.lines().any { it.startsWith("/$BLOCKED/") }, entries)

        val problems = CheckoutProblems.of(checkout.handler)
        assertEquals(1, problems.lines.size, "${problems.lines}")
        assertTrue(problems.lines[0].contains("$module/$BLOCKED"), problems.lines[0])
        assertFalse(problems.aborted)
    }

    @Test
    @DisplayName("A server abort ends the checkout with what arrived")
    fun aServerAbortEndsTheCheckoutWithWhatArrived() {
        val checkoutsBefore = checkoutRequests()
        val checkout = CheckoutFixtures.abortedCheckout(session, "checkout-abort")

        assertEquals("a", checkout.workingCopy.resolve("a.txt").readText().trimEnd())
        assertEquals("b", checkout.workingCopy.resolve("b.txt").readText().trimEnd())
        val problems = CheckoutProblems.of(checkout.handler)
        assertTrue(problems.aborted, "${problems.lines}")
        assertTrue(problems.abortMessage!!.contains("aborted"), problems.abortMessage)
        assertEquals(1, checkoutRequests() - checkoutsBefore, "checkout requests sent")
    }

    private fun checkoutRequests(): Int = session.server.requestLines().count { it == "co" }
}
