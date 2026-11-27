package by.devlab.cvs.update

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.FormBuilder
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.ButtonGroup
import javax.swing.JComponent

/** What to update to a changeset (design decision 3): the whole project, or one folder inside a CVS root. */
sealed interface UpdateScope {
    data object WholeProject : UpdateScope

    data class Folder(val path: Path) : UpdateScope
}

/**
 * The scope dialog's choice (design decisions 3, 13): "Whole project" by default, or the [folder] typed or chosen; a
 * folder must be a working-copy folder inside one of [roots], the project's CVS working-copy roots on the changeset's
 * repository (decision 20).
 */
class UpdateScopeModel(private val roots: List<Path>) {
    var wholeProject: Boolean = true
    var folder: String = ""

    /** Why the choice cannot be confirmed, or null when it can. */
    fun validate(): String? {
        if (wholeProject) return null
        if (folder.isBlank()) return "Choose a folder"
        val path = folderPath() ?: return OUTSIDE
        if (roots.none { path.startsWith(it.toAbsolutePath().normalize()) }) return OUTSIDE
        // CVS updates only a folder it tracks: one with its own administrative directory.
        return if (Files.isRegularFile(path.resolve("CVS").resolve("Entries"))) null else "The folder is not a CVS working-copy folder"
    }

    /** The chosen scope, or null while the choice is not valid. */
    fun scope(): UpdateScope? {
        if (validate() != null) return null
        return if (wholeProject) UpdateScope.WholeProject else UpdateScope.Folder(folderPath()!!)
    }

    private fun folderPath(): Path? = try {
        Path.of(folder.trim()).toAbsolutePath().normalize()
    } catch (_: InvalidPathException) {
        null
    }

    private companion object {
        const val OUTSIDE = "Choose a folder inside a CVS root of the project that uses this changeset's repository"
    }
}

/**
 * Asks what to update to the trunk as of [cutoff] (design decisions 3, 13): "Whole project" (default) or "One folder"
 * with the IDE's folder chooser; a folder outside [roots] shows its reason and disables OK.
 */
class UpdateToChangesetDialog(project: Project, private val cutoff: ChangesetCutoff, roots: List<Path>) : DialogWrapper(project) {
    private val model = UpdateScopeModel(roots)
    private val wholeButton = JBRadioButton("Whole project", true)
    private val folderButton = JBRadioButton("One folder:")
    private val folderField = TextFieldWithBrowseButton()

    init {
        title = "Update to This Changeset"
        ButtonGroup().apply {
            add(wholeButton)
            add(folderButton)
        }
        folderField.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Folder to Update"))
        folderField.isEnabled = false
        wholeButton.addActionListener { folderField.isEnabled = false }
        folderButton.addActionListener { folderField.isEnabled = true }
        setOKButtonText("Update")
        init()
        initValidation()
    }

    /** The confirmed scope. */
    val scope: UpdateScope? get() = sync().scope()

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(JBLabel("Update to the trunk as of ${cutoff.localText} (${cutoff.utcText}, as the branch indicator shows it)."))
        .addComponent(wholeButton)
        .addComponent(folderButton)
        .addComponent(folderField)
        .panel

    override fun doValidate(): ValidationInfo? = sync().validate()?.let { ValidationInfo(it, folderField) }

    private fun sync(): UpdateScopeModel = model.apply {
        wholeProject = wholeButton.isSelected
        folder = folderField.text
    }
}
