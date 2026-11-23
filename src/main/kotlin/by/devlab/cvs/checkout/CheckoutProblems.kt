package by.devlab.cvs.checkout

import com.intellij.cvsSupport2.cvshandlers.CvsHandler
import com.intellij.openapi.vcs.VcsException
import org.netbeans.lib.cvsclient.command.CommandAbortedException

/**
 * What went wrong in one checkout, as lines of text (design decision 10): the errors and warnings of the checkout
 * handler, duplicates removed, in arrival order. A cancelled checkout is not a problem and is left out.
 *
 * [abortMessage] is the server's `cvs [checkout aborted]: ...` line when the server aborted the checkout.
 */
class CheckoutProblems(val lines: List<String>, val abortMessage: String?) {

    val aborted: Boolean get() = abortMessage != null

    fun isEmpty(): Boolean = lines.isEmpty()

    companion object {
        private val ABORT = Regex("""^cvs \[.* aborted].*""")

        fun of(handler: CvsHandler): CheckoutProblems = of(handler.errors)

        fun of(errors: List<VcsException>): CheckoutProblems {
            val lines = errors
                .filter { it.cause !is CommandAbortedException }
                .map { text(it) }
                .filter { it.isNotBlank() }
                .distinct()
            return CheckoutProblems(lines, lines.firstOrNull { ABORT.matches(it) })
        }

        private fun text(error: VcsException): String =
            error.messages.filter { it.isNotBlank() }.joinToString("\n").ifBlank { error.cause?.toString().orEmpty() }.trim()
    }
}
