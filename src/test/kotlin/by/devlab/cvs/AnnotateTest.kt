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
 * Capability cvs-operations, requirement "Annotate attributes lines to revisions" (design decisions 12, 13):
 * `alice` commits a 4-line file, `bob` changes lines 2 and 3, and bob's copy is annotated through the VCS's
 * annotation provider.
 */
@TestApplication
class AnnotateTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        val WINDOWS_1251: Charset = Charset.forName("windows-1251")
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("Annotate attributes lines to their revisions")
    fun annotateAttributesLinesToTheirRevisions() {
        val file = twoRevisions("annotate-revisions", Charsets.UTF_8, listOf("one", "two", "three", "four"), listOf("one", "TWO", "THREE", "four"))

        val lines = session.annotate(project.get(), file)

        assertEquals(
            listOf(
                CvsTestSession.AnnotatedLine("1.1", "alice", "one"),
                CvsTestSession.AnnotatedLine("1.2", "bob", "TWO"),
                CvsTestSession.AnnotatedLine("1.2", "bob", "THREE"),
                CvsTestSession.AnnotatedLine("1.1", "alice", "four"),
            ),
            lines,
        )
    }

    @Test
    @DisplayName("Annotate keeps Cyrillic lines")
    fun annotateKeepsCyrillicLines() {
        val before = listOf("Первая строка", "Вторая строка", "Третья строка", "Четвёртая строка")
        val after = listOf("Первая строка", "Вторая, изменена bob: ёЁ щЩ ъЪ", "Третья, изменена bob", "Четвёртая строка")
        val file = twoRevisions("annotate-cp1251", WINDOWS_1251, before, after)

        val lines = session.annotate(project.get(), file)

        assertEquals(after, lines.map { it.text })
        assertEquals(listOf("1.1", "1.2", "1.2", "1.1"), lines.map { it.revision })
    }

    /**
     * With the CVS encoding [charset]: `alice` adds `Документ.txt` with [first] lines, `bob` commits [second].
     * Returns the file in bob's working copy, which is at the second revision.
     */
    private fun twoRevisions(module: String, charset: Charset, first: List<String>, second: List<String>): Path {
        session.useEncoding(charset)
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve("readme.txt"), "module\n".toByteArray())
        session.importModule("alice", seed, module)
        val a = session.checkout("alice", module, session.newDirectory("$module-a"))
        session.mapToCvs(project.get(), a)
        val created = a.resolve("Документ.txt")
        TestData.write(created, first.joinToString("\n", postfix = "\n").toByteArray(charset))
        session.add(project.get(), created)
        session.commit(project.get(), listOf(created), "alice: four lines")

        val b = session.checkout("bob", module, session.newDirectory("$module-b"))
        session.mapToCvs(project.get(), b)
        TestData.edit(b.resolve("Документ.txt"), second.joinToString("\n", postfix = "\n").toByteArray(charset))
        session.commit(project.get(), listOf(b.resolve("Документ.txt")), "bob: lines 2 and 3")
        return b.resolve("Документ.txt")
    }
}
