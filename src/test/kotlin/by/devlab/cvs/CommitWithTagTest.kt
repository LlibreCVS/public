package by.devlab.cvs

import by.devlab.cvs.tagging.WeekTag
import com.intellij.cvsSupport2.checkinProject.AdditionalOptionsPanel
import com.intellij.cvsSupport2.config.CvsConfiguration
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * Capability cvs-operations, requirement "Commit with a tag" (design decision 12): the commit goes through
 * `CvsCheckinEnvironment.commit` with the commit dialog's tag-after-commit option set; the server state is read
 * back through the plugin (a fresh checkout, by tag where it matters), never through a native client.
 */
@TestApplication
class CommitWithTagTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("Commit creates new revisions")
    fun commitCreatesNewRevisions() {
        val copy = workingCopy("commit-revisions")
        TestData.edit(copy.resolve("один.txt"), "один, правка\n".toByteArray())
        TestData.edit(copy.resolve("два.txt"), "два, правка\n".toByteArray())

        session.commit(project.get(), listOf(copy.resolve("один.txt"), copy.resolve("два.txt")), "правка двух файлов")

        val fresh = session.checkout("bob", "commit-revisions", session.newDirectory("commit-revisions-fresh"))
        assertEquals("один, правка\n", TestData.text(fresh.resolve("один.txt"), Charsets.UTF_8))
        assertEquals("два, правка\n", TestData.text(fresh.resolve("два.txt"), Charsets.UTF_8))
        assertEquals("1.2", session.revision(fresh.resolve("один.txt")))
        assertEquals("1.2", session.revision(fresh.resolve("два.txt")))
    }

    @Test
    @DisplayName("Tag marks exactly the committed revisions")
    fun tagMarksExactlyTheCommittedRevisions() {
        val copy = workingCopy("commit-tag")
        TestData.edit(copy.resolve("один.txt"), "один, под тегом\n".toByteArray())
        TestData.edit(copy.resolve("два.txt"), "два, под тегом\n".toByteArray())

        session.commitWithTag(project.get(), listOf(copy.resolve("один.txt"), copy.resolve("два.txt")), "правка с тегом", "RELEASE_1")

        val tagged = session.checkout("bob", "commit-tag", session.newDirectory("commit-tag-tagged"), tag = "RELEASE_1")
        assertEquals("один, под тегом\n", TestData.text(tagged.resolve("один.txt"), Charsets.UTF_8))
        assertEquals("два, под тегом\n", TestData.text(tagged.resolve("два.txt"), Charsets.UTF_8))
        assertEquals("1.2", session.revision(tagged.resolve("один.txt")))
        assertEquals("1.2", session.revision(tagged.resolve("два.txt")))
        assertFalse(tagged.resolve("три.txt").exists(), "три.txt was not committed and must not carry the tag")
    }

    @Test
    @DisplayName("New binary file is committed as binary")
    fun newBinaryFileIsCommittedAsBinary() {
        val copy = workingCopy("commit-binary")
        val binary = copy.resolve("схема.bin")
        TestData.write(binary, TestData.binary())
        session.add(project.get(), binary)

        session.commit(project.get(), listOf(binary), "двоичный файл")

        val fresh = session.checkout("bob", "commit-binary", session.newDirectory("commit-binary-fresh"))
        assertArrayEquals(TestData.binary(), fresh.resolve("схема.bin").readBytes())
        assertEquals("-kb", session.keywordSubstitution(fresh.resolve("схема.bin")))
    }

    // Review focus: CRLF must be stored as LF and come back as the same lines in a second working copy.
    @Test
    @DisplayName("CRLF text file round-trips")
    fun crlfTextFileRoundTrips() {
        val copy = workingCopy("commit-crlf")
        TestData.edit(copy.resolve("один.txt"), "строка 1\r\nстрока 2\r\n".toByteArray())

        session.commit(project.get(), listOf(copy.resolve("один.txt")), "CRLF")

        val fresh = session.checkout("bob", "commit-crlf", session.newDirectory("commit-crlf-fresh"))
        assertEquals("строка 1\nстрока 2\n", TestData.text(fresh.resolve("один.txt"), Charsets.UTF_8))
        val stored = Files.readAllBytes(session.server.repositoryFile("commit-crlf/один.txt,v"))
        assertTrue(stored.none { it == '\r'.code.toByte() }, "the repository file holds CR bytes")
    }

    /** Imports a three-file module, checks it out as `alice` and maps the copy to CVS. */
    @Test
    @DisplayName("Week tag marks the committed revisions")
    fun weekTagMarksTheCommittedRevisions() {
        val copy = workingCopy("commit-week")
        TestData.edit(copy.resolve("один.txt"), "один, неделя\n".toByteArray())
        TestData.edit(copy.resolve("два.txt"), "два, неделя\n".toByteArray())

        commitWithWeekTag(listOf(copy.resolve("один.txt"), copy.resolve("два.txt")), "правка недели", otherTag = "OTHER_TAG")

        val week = WeekTag.current()
        assertEquals("1.2", session.tagOf("commit-week/один.txt", week))
        assertEquals("1.2", session.tagOf("commit-week/два.txt", week))
        assertNull(session.tagOf("commit-week/три.txt", week), "три.txt was not committed and must not carry $week")
        for (file in listOf("один.txt", "два.txt", "три.txt")) {
            assertNull(session.tagOf("commit-week/$file", "OTHER_TAG"), "the tag-after-commit name must not be used")
        }
    }

    @Test
    @DisplayName("A second commit in the same week moves the week tag")
    fun aSecondCommitInTheSameWeekMovesTheWeekTag() {
        val copy = workingCopy("commit-week-move")
        TestData.edit(copy.resolve("один.txt"), "один, первая\n".toByteArray())
        TestData.edit(copy.resolve("два.txt"), "два, первая\n".toByteArray())
        commitWithWeekTag(listOf(copy.resolve("один.txt"), copy.resolve("два.txt")), "первая правка недели")
        TestData.edit(copy.resolve("один.txt"), "один, вторая\n".toByteArray())

        commitWithWeekTag(listOf(copy.resolve("один.txt")), "вторая правка недели")

        val week = WeekTag.current()
        assertEquals("1.3", session.tagOf("commit-week-move/один.txt", week))
        assertEquals("1.2", session.tagOf("commit-week-move/два.txt", week))
    }

    @Test
    @DisplayName("The week option is remembered")
    fun theWeekOptionIsRemembered() {
        val configuration = CvsConfiguration.getInstance(project.get())
        val previous = configuration.TAG_WITH_CURRENT_WEEK
        try {
            configuration.TAG_WITH_CURRENT_WEEK = false
            runInEdtAndWait {
                val panel = AdditionalOptionsPanel(configuration, emptyList(), project.get())
                panel.restoreState()
                assertFalse(panel.weekTagCheckBox().isSelected)
                panel.weekTagCheckBox().isSelected = true
                panel.saveState()

                val shownAgain = AdditionalOptionsPanel(configuration, emptyList(), project.get())
                shownAgain.restoreState()
                assertTrue(shownAgain.weekTagCheckBox().isSelected)
            }
            assertTrue(configuration.TAG_WITH_CURRENT_WEEK)
        } finally {
            configuration.TAG_WITH_CURRENT_WEEK = previous
        }
    }

    /** Commits [files] with "Commit and tag with the current week" selected and the tag-after-commit fields set to [otherTag]. */
    private fun commitWithWeekTag(files: List<Path>, message: String, otherTag: String? = null) {
        val configuration = CvsConfiguration.getInstance(project.get())
        val previous = Triple(configuration.TAG_WITH_CURRENT_WEEK, configuration.TAG_AFTER_PROJECT_COMMIT, configuration.TAG_AFTER_PROJECT_COMMIT_NAME)
        configuration.TAG_WITH_CURRENT_WEEK = true
        configuration.TAG_AFTER_PROJECT_COMMIT = otherTag != null
        configuration.TAG_AFTER_PROJECT_COMMIT_NAME = otherTag.orEmpty()
        try {
            session.commit(project.get(), files, message)
        } finally {
            configuration.TAG_WITH_CURRENT_WEEK = previous.first
            configuration.TAG_AFTER_PROJECT_COMMIT = previous.second
            configuration.TAG_AFTER_PROJECT_COMMIT_NAME = previous.third
        }
    }

    private fun workingCopy(module: String): Path {
        session.useEncoding(Charsets.UTF_8)
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve("один.txt"), "один\n".toByteArray())
        TestData.write(seed.resolve("два.txt"), "два\n".toByteArray())
        TestData.write(seed.resolve("три.txt"), "три\n".toByteArray())
        session.importModule("alice", seed, module, binaryExtensions = listOf("bin"))
        val copy = session.checkout("alice", module, session.newDirectory("$module-a"))
        session.mapToCvs(project.get(), copy)
        return copy
    }
}
