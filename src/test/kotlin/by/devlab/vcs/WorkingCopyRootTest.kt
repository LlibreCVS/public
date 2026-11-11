package by.devlab.vcs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vcs.VcsRootChecker
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Requirements "Working-copy roots follow the CVS entries", "Tracked subdirectories belong to the parent's
 * root" and "Non-ASCII directory names match in the usual CVS encodings" of capability working-copy-detection,
 * through the CVS root checker that the plugin registers with the platform.
 */
@TestApplication
class WorkingCopyRootTest {

    @TempDir
    lateinit var temp: Path

    private val repository = ":pserver:anonymous@cvs.example.org:/cvsroot"
    private val otherRepository = ":ext:dev@cvs.example.net:/var/cvs"
    private val windows1251 = Charset.forName("windows-1251")

    @Test
    @DisplayName("Top directory of a working copy is a root")
    fun topDirectoryOfAWorkingCopyIsARoot() {
        val top = topWorkingCopy()

        assertTrue(isRoot(top))
    }

    @Test
    @DisplayName("Untracked nested checkout of the same repository is a root")
    fun untrackedNestedCheckoutOfTheSameRepositoryIsARoot() {
        val top = topWorkingCopy()
        val nested = FakeWorkingCopy.write(top.resolve("src").createDirectories(), repository, "module/src")

        assertTrue(isRoot(nested))
    }

    @Test
    @DisplayName("Nested working copy of another repository is a root")
    fun nestedWorkingCopyOfAnotherRepositoryIsARoot() {
        val top = topWorkingCopy()
        val nested = FakeWorkingCopy.write(top.resolve("lib").createDirectories(), otherRepository, "module/lib")
        FakeWorkingCopy.track(top, nested)

        assertTrue(isRoot(nested))
    }

    @Test
    @DisplayName("Subdirectory of another module path is a root")
    fun subdirectoryOfAnotherModulePathIsARoot() {
        val top = topWorkingCopy()
        val other = FakeWorkingCopy.write(top.resolve("lib").createDirectories(), repository, "vendor/lib")
        FakeWorkingCopy.track(top, other)

        assertTrue(isRoot(other))
    }

    @Test
    @DisplayName("Directory without complete CVS admin files is not a root")
    fun directoryWithoutCompleteCvsAdminFilesIsNotARoot() {
        val plain = temp.resolve("plain").createDirectories()
        val withoutEntries = FakeWorkingCopy.write(temp.resolve("no-entries").createDirectories(), repository)
        Files.delete(withoutEntries.resolve("CVS/Entries"))
        val withoutRoot = FakeWorkingCopy.write(temp.resolve("no-root").createDirectories(), repository)
        Files.delete(withoutRoot.resolve("CVS/Root"))
        val withoutRepository = FakeWorkingCopy.write(temp.resolve("no-repository").createDirectories(), repository)
        Files.delete(withoutRepository.resolve("CVS/Repository"))
        val rootIsDirectory = FakeWorkingCopy.write(temp.resolve("root-dir").createDirectories(), repository)
        Files.delete(rootIsDirectory.resolve("CVS/Root"))
        rootIsDirectory.resolve("CVS/Root").createDirectories()

        for (dir in listOf(plain, withoutEntries, withoutRoot, withoutRepository, rootIsDirectory)) {
            assertFalse(isRoot(dir), "$dir")
        }
    }

    @Test
    @DisplayName("Tracked subdirectory is not a root")
    fun trackedSubdirectoryIsNotARoot() {
        val top = topWorkingCopy()
        val src = FakeWorkingCopy.track(top, FakeWorkingCopy.write(top.resolve("src").createDirectories(), repository, "module/src"))

        assertFalse(isRoot(src))
    }

    @Test
    @DisplayName("Line endings and trailing slashes do not matter")
    fun lineEndingsAndTrailingSlashesDoNotMatter() {
        val top = FakeWorkingCopy.write(temp.resolve("wc").createDirectories(), repository, "module/", lineEnding = "\r\n")
        val src = FakeWorkingCopy.write(top.resolve("src").createDirectories(), repository, "module/src", lineEnding = "\n")
        FakeWorkingCopy.track(top, src)

        assertFalse(isRoot(src))
    }

    @Test
    @DisplayName("Cyrillic subdirectory with UTF-8 entries is not a root")
    fun cyrillicSubdirectoryWithUtf8EntriesIsNotARoot() {
        val top = topWorkingCopy()
        val sub = FakeWorkingCopy.write(top.resolve("исходники").createDirectories(), repository, "module/исходники")
        FakeWorkingCopy.track(top, sub)

        assertFalse(isRoot(sub))
    }

    @Test
    @DisplayName("Cyrillic subdirectory with windows-1251 entries is not a root")
    fun cyrillicSubdirectoryWithWindows1251EntriesIsNotARoot() {
        val top = topWorkingCopy()
        val sub = FakeWorkingCopy.write(
            top.resolve("исходники").createDirectories(), repository, "module/исходники", charset = windows1251,
        )
        FakeWorkingCopy.track(top, sub, windows1251)

        withCvsEncoding("windows-1251") {
            assertFalse(isRoot(sub))
        }
    }

    // Review focus: mixed encodings.
    @Test
    @DisplayName("Mixed encodings do not break the check")
    fun mixedEncodingsDoNotBreakTheCheck() {
        val top = topWorkingCopy()
        val utf8Entries = FakeWorkingCopy.write(
            top.resolve("исходники").createDirectories(), repository, "module/исходники", charset = windows1251,
        )
        FakeWorkingCopy.track(top, utf8Entries, Charsets.UTF_8)
        val cp1251Entries = FakeWorkingCopy.write(
            top.resolve("документы").createDirectories(), repository, "module/документы", charset = Charsets.UTF_8,
        )
        FakeWorkingCopy.track(top, cp1251Entries, windows1251)

        withCvsEncoding("windows-1251") {
            assertFalse(isRoot(utf8Entries))
            assertFalse(isRoot(cp1251Entries))
        }
    }

    @Test
    @DisplayName("Subdirectory added in Entries.Log is not a root")
    fun subdirectoryAddedInEntriesLogIsNotARoot() {
        val top = topWorkingCopy()
        val src = FakeWorkingCopy.write(top.resolve("src").createDirectories(), repository, "module/src")
        FakeWorkingCopy.logEntry(top, "A D/src////")

        assertFalse(isRoot(src))
    }

    @Test
    @DisplayName("Subdirectory removed in Entries.Log is a root")
    fun subdirectoryRemovedInEntriesLogIsARoot() {
        val top = topWorkingCopy()
        val src = FakeWorkingCopy.track(top, FakeWorkingCopy.write(top.resolve("src").createDirectories(), repository, "module/src"))
        FakeWorkingCopy.logEntry(top, "R D/src////")

        assertTrue(isRoot(src))
    }

    // Review focus: Entries.Log order.
    @Test
    @DisplayName("Last Entries.Log line wins")
    fun lastEntriesLogLineWins() {
        val top = topWorkingCopy()
        val src = FakeWorkingCopy.write(top.resolve("src").createDirectories(), repository, "module/src")
        FakeWorkingCopy.logEntry(top, "A D/src////")
        FakeWorkingCopy.logEntry(top, "R D/src////")
        FakeWorkingCopy.logEntry(top, "A D/src////")

        assertFalse(isRoot(src))
    }

    /** The top directory `wc` of a working copy of module `module`. */
    private fun topWorkingCopy(): Path =
        FakeWorkingCopy.write(temp.resolve("wc").createDirectories(), repository, "module")

    /** Asks the CVS root checker registered with the platform whether [dir] is a working-copy root. */
    private fun isRoot(dir: Path): Boolean {
        val checker = VcsRootChecker.EXTENSION_POINT_NAME.extensionList.single { it.supportedVcs.name == "CVS" }
        val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)) { "no VFS file for $dir" }
        return checker.isRoot(file)
    }

    /** Runs [block] with the plugin's CVS encoding setting set to [encoding], then restores the setting. */
    private fun withCvsEncoding(encoding: String, block: () -> Unit) {
        val configurationClass = Class.forName("com.intellij.cvsSupport2.config.CvsApplicationLevelConfiguration")
        val configuration = ApplicationManager.getApplication().getService(configurationClass)
        val field = configurationClass.getField("ENCODING")
        val previous = field.get(configuration)
        field.set(configuration, encoding)
        try {
            block()
        } finally {
            field.set(configuration, previous)
        }
    }
}
