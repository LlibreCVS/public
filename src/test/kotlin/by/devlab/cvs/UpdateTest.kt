package by.devlab.cvs

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.charset.Charset
import java.nio.file.Path

/**
 * Capability cvs-operations, requirement "Update brings in changes committed by others" (design decisions 12, 13):
 * `alice` works in copy A, `bob` ("another user") in copy B; B commits, A updates.
 */
@TestApplication
class UpdateTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        val WINDOWS_1251: Charset = Charset.forName("windows-1251")
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("Update brings another user's change")
    fun updateBringsAnotherUsersChange() {
        val (a, b) = twoWorkingCopies("update-change", Charsets.UTF_8)
        TestData.write(b.resolve("заметки.txt"), "первая строка\nстрока от bob\n".toByteArray())
        session.commit(project.get(), listOf(b.resolve("заметки.txt")), "bob меняет заметки")

        session.update(project.get(), a)

        assertEquals("первая строка\nстрока от bob\n", TestData.text(a.resolve("заметки.txt"), Charsets.UTF_8))
        assertEquals("1.2", session.revision(a.resolve("заметки.txt")))
    }

    @Test
    @DisplayName("Update brings a newly added file")
    fun updateBringsANewlyAddedFile() {
        val (a, b) = twoWorkingCopies("update-added", Charsets.UTF_8)
        val added = b.resolve("Новый файл.txt")
        TestData.write(added, "добавлено bob\n".toByteArray())
        session.add(project.get(), added)
        session.commit(project.get(), listOf(added), "bob добавляет файл")

        session.update(project.get(), a)

        assertEquals("добавлено bob\n", TestData.text(a.resolve("Новый файл.txt"), Charsets.UTF_8))
    }

    // Review focus: a directory with a windows-1251 name, added by another user, must arrive.
    @Test
    @DisplayName("Update brings a newly added file (windows-1251, new Cyrillic directory)")
    fun updateBringsANewlyAddedFileInANewWindows1251Directory() {
        val (a, b) = twoWorkingCopies("update-added-cp1251", WINDOWS_1251)
        val directory = b.resolve("Новая папка")
        val added = directory.resolve("Приказ №1.txt")
        TestData.write(added, "текст приказа\n".toByteArray(WINDOWS_1251))
        session.add(project.get(), directory, added)
        session.commit(project.get(), listOf(added), "bob добавляет приказ")

        session.update(project.get(), a)

        assertEquals("текст приказа\n", TestData.text(a.resolve("Новая папка/Приказ №1.txt"), WINDOWS_1251))
    }

    @Test
    @DisplayName("Update keeps local edits")
    fun updateKeepsLocalEdits() {
        val (a, b) = twoWorkingCopies("update-local", Charsets.UTF_8)
        TestData.write(a.resolve("заметки.txt"), "первая строка\nправка alice, не закоммичена\n".toByteArray())
        TestData.write(b.resolve("план.txt"), "план от bob\n".toByteArray())
        session.commit(project.get(), listOf(b.resolve("план.txt")), "bob меняет план")

        session.update(project.get(), a)

        assertEquals("первая строка\nправка alice, не закоммичена\n", TestData.text(a.resolve("заметки.txt"), Charsets.UTF_8))
        assertEquals("план от bob\n", TestData.text(a.resolve("план.txt"), Charsets.UTF_8))
    }

    /** Imports a two-file module, checks it out as `alice` (A) and `bob` (B), and maps both copies to CVS. */
    private fun twoWorkingCopies(module: String, charset: Charset): Pair<Path, Path> {
        session.useEncoding(charset)
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve("заметки.txt"), "первая строка\n".toByteArray(charset))
        TestData.write(seed.resolve("план.txt"), "план\n".toByteArray(charset))
        session.importModule("alice", seed, module)
        val a = session.checkout("alice", module, session.newDirectory("$module-a"))
        val b = session.checkout("bob", module, session.newDirectory("$module-b"))
        session.mapToCvs(project.get(), a, b)
        return a to b
    }
}
