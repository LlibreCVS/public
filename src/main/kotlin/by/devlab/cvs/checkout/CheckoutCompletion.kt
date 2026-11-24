package by.devlab.cvs.checkout

import com.intellij.cvsSupport2.CvsVcs2
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vcs.CheckoutProvider
import java.io.File

/**
 * The end of a checkout from "Get from Version Control" (design decision 12): without problems the project is created
 * from the checked-out directories at once; with problems the user is asked first, and on "Don't Create" no project
 * is created while the received files stay on disk. The listener's checkout always completes.
 *
 * Called on the EDT, where the question is asked; the listener is called on a pooled thread, because the IDE's
 * checkout listeners refuse the EDT (design decision 19).
 */
class CheckoutCompletion(private val prompts: CheckoutPrompts) {

    fun complete(module: String, problems: CheckoutProblems, directories: List<File>, listener: CheckoutProvider.Listener) {
        if (!problems.isEmpty()) {
            LOG.info("Checkout of $module had ${problems.lines.size} problem(s):\n" + problems.lines.joinToString("\n"))
        }
        val create = problems.isEmpty() || prompts.askCreateProject(module, problems)
        val opened = if (create) directories else emptyList()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                opened.forEach { listener.directoryCheckedOut(it, CvsVcs2.getKey()) }
            } finally {
                listener.checkoutCompleted()
            }
        }
    }

    private companion object {
        val LOG = logger<CheckoutCompletion>()
    }
}
