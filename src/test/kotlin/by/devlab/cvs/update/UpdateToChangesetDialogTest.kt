package by.devlab.cvs.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Capability changeset-update, requirement "The scope is chosen in a dialog" (design decisions 3, 13, 20): the scope
 * dialog's model, without showing the dialog.
 */
class UpdateToChangesetDialogTest {

    @TempDir
    lateinit var temp: Path

    @Test
    @DisplayName("Whole project is the default scope")
    fun wholeProjectIsTheDefaultScope() {
        val model = UpdateScopeModel(listOf(workingCopyFolder(temp.resolve("a"))))

        assertEquals(UpdateScope.WholeProject, model.scope())
        assertNull(model.validate())
    }

    @Test
    @DisplayName("Folder outside the CVS roots is rejected")
    fun folderOutsideTheCvsRootsIsRejected() {
        val root = workingCopyFolder(temp.resolve("root"))
        val inside = workingCopyFolder(root.resolve("dir"))
        // A working copy too, but not one of the roots offered (another repository, decision 20).
        val outside = workingCopyFolder(temp.resolve("other"))
        val model = UpdateScopeModel(listOf(root))
        model.wholeProject = false

        model.folder = outside.toString()
        assertNotNull(model.validate())
        assertNull(model.scope(), "an invalid choice cannot be confirmed")

        model.folder = ""
        assertNotNull(model.validate(), "an empty folder is rejected")

        model.folder = Files.createDirectories(root.resolve("untracked")).toString()
        assertNotNull(model.validate(), "a folder CVS does not track is rejected")

        model.folder = inside.toString()
        assertNull(model.validate())
        assertEquals(UpdateScope.Folder(inside), model.scope())

        model.folder = root.toString()
        assertNull(model.validate(), "a root itself is a folder inside the roots")
    }

    /** [dir] with the `CVS/Entries` a working-copy folder has. */
    private fun workingCopyFolder(dir: Path): Path {
        Files.createDirectories(dir.resolve("CVS"))
        Files.writeString(dir.resolve("CVS").resolve("Entries"), "D\n")
        return dir
    }
}
