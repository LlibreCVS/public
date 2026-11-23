package by.devlab.cvs.checkout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Capability cvs-operations, requirement "Checkout ends with one problem report that asks before creating the
 * project": what the problems dialog says (design decisions 11, 15), built without showing it.
 */
class CheckoutProblemsDialogTest {

    @Test
    @DisplayName("Dialog lists each problem line")
    fun dialogListsEachProblemLine() {
        val lines = listOf(
            "cvs checkout: could not receive module/файл.txt: Could not delete file C:\\wc\\module\\файл.txt",
            "cvs server: warning: something else",
        )

        val text = dialogText("module", CheckoutProblems(lines, null))

        assertEquals("2 problems while checking out module", text.header)
        assertNull(text.abortNotice)
        assertEquals(lines, text.lines)
    }

    @Test
    @DisplayName("Dialog names the server abort")
    fun dialogNamesTheServerAbort() {
        val abort = "cvs [checkout aborted]: EOF while looking for value in RCS file /repo/module/c.txt,v"

        val text = dialogText("module", CheckoutProblems(listOf(abort), abort))

        assertEquals("1 problem while checking out module", text.header)
        val notice = text.abortNotice!!
        assertTrue(notice.contains("The server aborted the checkout"), notice)
        assertTrue(notice.contains(abort), notice)
        assertTrue(notice.contains("part of the module was not received"), notice)
    }

    @Test
    @DisplayName("Dialog shows at most 500 lines")
    fun dialogShowsAtMost500Lines() {
        val lines = (1..1200).map { "cvs checkout: could not receive module/f$it.txt: Permission denied" }

        val text = dialogText("module", CheckoutProblems(lines, null))

        assertEquals("1200 problems while checking out module", text.header)
        assertEquals(501, text.lines.size)
        assertEquals(lines.take(500), text.lines.take(500))
        assertEquals("… and 700 more", text.lines.last())
    }
}
