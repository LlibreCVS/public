package by.devlab.cvs.tagging

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/** Why [name] (trimmed) is not a valid CVS tag name, or null when it is (design decision 10). */
fun validateTagName(name: String): String? {
    val tag = name.trim()
    return when {
        tag.isEmpty() -> "Enter a tag name"
        tag.first() !in 'A'..'Z' && tag.first() !in 'a'..'z' -> "A tag name must start with a Latin letter"
        !TAG_NAME.matches(tag) -> "A tag name may contain only Latin letters, digits, '-' and '_'"
        tag in RESERVED -> "$tag is a name CVS reserves for itself"
        else -> null
    }
}

private val TAG_NAME = Regex("[A-Za-z][A-Za-z0-9_-]*")

/** Names CVS refuses as tags ("Attempt to add reserved tag name"). */
private val RESERVED = setOf("HEAD", "BASE")

/**
 * Asks for the tag to put on a changeset: the tag name, "Move tag if it already exists" (checked by default,
 * design decision 5) and the changeset's files.
 */
class PutOnTagDialog(project: Project?, files: List<String>) : DialogWrapper(project) {
    private val nameField = JBTextField(20)
    private val moveBox = JBCheckBox("Move tag if it already exists", true)
    private val fileList = JBList(files)

    init {
        title = "Put on Tag"
        init()
        // Validates while the user types; an invalid name shows its reason and disables OK.
        initValidation()
    }

    /** The trimmed tag name. */
    val tagName: String get() = nameField.text.trim()

    val moveExisting: Boolean get() = moveBox.isSelected

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Tag name:", nameField)
        .addComponent(moveBox)
        .addLabeledComponentFillVertically("Files:", JBScrollPane(fileList))
        .panel

    override fun doValidate(): ValidationInfo? = validateTagName(nameField.text)?.let { ValidationInfo(it, nameField) }
}
