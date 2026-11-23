package by.devlab.cvs.checkout

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** The most problem lines the dialog shows; the full list goes to the IDE log (design decision 15). */
const val MAX_SHOWN_PROBLEMS = 500

/** What the problems dialog says: a header, the abort notice when the server aborted, and the problem lines. */
data class CheckoutDialogText(val header: String, val abortNotice: String?, val lines: List<String>)

/** The text of the problems dialog for a checkout of [module] with [problems] (design decisions 11, 15). */
fun dialogText(module: String, problems: CheckoutProblems): CheckoutDialogText {
    val count = problems.lines.size
    val header = "$count ${if (count == 1) "problem" else "problems"} while checking out $module"
    val abortNotice = problems.abortMessage?.let {
        "The server aborted the checkout ($it); part of the module was not received."
    }
    val shown = problems.lines.take(MAX_SHOWN_PROBLEMS)
    val lines = if (count > MAX_SHOWN_PROBLEMS) shown + "… and ${count - MAX_SHOWN_PROBLEMS} more" else shown
    return CheckoutDialogText(header, abortNotice, lines)
}

/**
 * The one dialog a checkout with problems shows (design decision 11): the problems in a scrollable read-only list and
 * the question whether to create the project. "Create Project" is OK; "Don't Create" and closing are cancel.
 */
class CheckoutProblemsDialog(module: String, problems: CheckoutProblems) : DialogWrapper(null, true) {

    private val text = dialogText(module, problems)

    init {
        title = "Check Out from CVS"
        setOKButtonText("Create Project")
        setCancelButtonText("Don't Create")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel(text.header))
            text.abortNotice?.let { add(JBLabel(it)) }
            add(JBLabel("Create the project from the files that arrived?"))
        }
        val list = JBTextArea(text.lines.joinToString("\n")).apply {
            isEditable = false
            caretPosition = 0
        }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(list).apply { preferredSize = Dimension(JBUI.scale(720), JBUI.scale(280)) }, BorderLayout.CENTER)
        }
    }
}
