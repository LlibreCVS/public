package homelab.vcs

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Requirement "Working-copy root recognition" of capability working-copy-detection, on plain java.nio paths.
 */
class WorkingCopyRootTest {

    @TempDir
    lateinit var temp: Path

    private val repository = ":pserver:anonymous@cvs.example.org:/cvsroot"
    private val otherRepository = ":ext:dev@cvs.example.net:/var/cvs"

    @Test
    @DisplayName("Top directory of a working copy is a root")
    fun topDirectoryOfAWorkingCopyIsARoot() {
        val top = FakeWorkingCopy.write(Files.createDirectories(temp.resolve("wc")), repository)

        assertTrue(CvsWorkingCopy.isRoot(top))
    }

    @Test
    @DisplayName("Subdirectory with the same repository is not a root")
    fun subdirectoryWithTheSameRepositoryIsNotARoot() {
        val top = FakeWorkingCopy.write(Files.createDirectories(temp.resolve("wc")), repository)
        val sub = FakeWorkingCopy.write(Files.createDirectories(top.resolve("src")), repository)

        assertFalse(CvsWorkingCopy.isRoot(sub))
    }

    @Test
    @DisplayName("Line endings do not change the repository")
    fun lineEndingsDoNotChangeTheRepository() {
        val top = FakeWorkingCopy.write(Files.createDirectories(temp.resolve("wc")), repository, lineEnding = "\r\n")
        val sub = FakeWorkingCopy.write(Files.createDirectories(top.resolve("src")), repository, lineEnding = "\n")

        assertFalse(CvsWorkingCopy.isRoot(sub))
    }

    @Test
    @DisplayName("Nested working copy of another repository is a root")
    fun nestedWorkingCopyOfAnotherRepositoryIsARoot() {
        val top = FakeWorkingCopy.write(Files.createDirectories(temp.resolve("wc")), repository)
        val nested = FakeWorkingCopy.write(Files.createDirectories(top.resolve("vendor-lib")), otherRepository)

        assertTrue(CvsWorkingCopy.isRoot(nested))
    }

    @Test
    @DisplayName("Directory without CVS/Root is not a root")
    fun directoryWithoutCvsRootIsNotARoot() {
        val plain = Files.createDirectories(temp.resolve("plain"))
        val adminWithoutRoot = Files.createDirectories(temp.resolve("partial"))
        Files.createDirectories(adminWithoutRoot.resolve("CVS"))
        Files.writeString(adminWithoutRoot.resolve("CVS/Entries"), "D\n")

        assertFalse(CvsWorkingCopy.isRoot(plain))
        assertFalse(CvsWorkingCopy.isRoot(adminWithoutRoot))
    }

    @Test
    @DisplayName("Blank or unreadable CVS/Root is not a root")
    fun blankOrUnreadableCvsRootIsNotARoot() {
        val empty = FakeWorkingCopy.writeRootFile(Files.createDirectories(temp.resolve("empty")), "")
        val blankFirstLine = FakeWorkingCopy.writeRootFile(
            Files.createDirectories(temp.resolve("blank")), "   \n$repository\n",
        )
        val rootIsDirectory = Files.createDirectories(temp.resolve("dir"))
        Files.createDirectories(rootIsDirectory.resolve("CVS/Root"))

        assertFalse(CvsWorkingCopy.isRoot(empty))
        assertFalse(CvsWorkingCopy.isRoot(blankFirstLine))
        assertFalse(CvsWorkingCopy.isRoot(rootIsDirectory))
    }

    @Test
    @DisplayName("Only the trimmed first line of CVS/Root names the repository")
    fun onlyTheTrimmedFirstLineOfCvsRootNamesTheRepository() {
        val top = FakeWorkingCopy.write(Files.createDirectories(temp.resolve("wc")), repository)
        val sub = FakeWorkingCopy.writeRootFile(
            Files.createDirectories(top.resolve("src")), "  $repository \t\nsecond line\n",
        )

        assertFalse(CvsWorkingCopy.isRoot(sub))
    }
}
