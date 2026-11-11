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
 * Capability cvs-operations, requirement "History shows the revisions of a file" (design decisions 5, 12, 13):
 * `alice` creates a file, `bob` commits a second revision, and the history comes from the VCS's history provider.
 */
@TestApplication
class HistoryTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        val WINDOWS_1251: Charset = Charset.forName("windows-1251")
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("History lists revisions with author and message")
    fun historyListsRevisionsWithAuthorAndMessage() {
        val file = twoRevisions("history-authors", Charsets.UTF_8, "created by alice", "second revision by bob")

        val history = session.history(project.get(), file)

        assertEquals(
            listOf(
                CvsTestSession.Revision("1.2", "bob", "second revision by bob"),
                CvsTestSession.Revision("1.1", "alice", "created by alice"),
            ),
            history,
        )
    }

    @Test
    @DisplayName("History keeps a UTF-8 Cyrillic message")
    fun historyKeepsAUtf8CyrillicMessage() {
        val file = twoRevisions("history-utf8", Charsets.UTF_8, "Создан отчёт", "Исправлена ошибка: ёЁ, щЩ, ъЪ")

        val history = session.history(project.get(), file)

        assertEquals(listOf("Исправлена ошибка: ёЁ, щЩ, ъЪ", "Создан отчёт"), history.map { it.message })
    }

    @Test
    @DisplayName("History keeps a windows-1251 Cyrillic message")
    fun historyKeepsAWindows1251CyrillicMessage() {
        val file = twoRevisions("history-cp1251", WINDOWS_1251, "Создан отчёт", "Исправлена ошибка: ёЁ, щЩ, ъЪ")

        val history = session.history(project.get(), file)

        assertEquals(listOf("Исправлена ошибка: ёЁ, щЩ, ъЪ", "Создан отчёт"), history.map { it.message })
    }

    /**
     * With the CVS encoding [charset] for the whole test: `alice` adds `Отчёт.txt` with [first], `bob` commits a
     * change with [second]. Returns the file in alice's working copy.
     */
    private fun twoRevisions(module: String, charset: Charset, first: String, second: String): Path {
        session.useEncoding(charset)
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve("readme.txt"), "module\n".toByteArray())
        session.importModule("alice", seed, module)
        val a = session.checkout("alice", module, session.newDirectory("$module-a"))
        session.mapToCvs(project.get(), a)
        val created = a.resolve("Отчёт.txt")
        TestData.write(created, "строка 1\n".toByteArray(charset))
        session.add(project.get(), created)
        session.commit(project.get(), listOf(created), first)

        val b = session.checkout("bob", module, session.newDirectory("$module-b"))
        session.mapToCvs(project.get(), b)
        TestData.edit(b.resolve("Отчёт.txt"), "строка 1\nстрока 2\n".toByteArray(charset))
        session.commit(project.get(), listOf(b.resolve("Отчёт.txt")), second)
        return created
    }
}
