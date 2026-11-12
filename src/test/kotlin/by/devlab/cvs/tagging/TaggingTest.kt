package by.devlab.cvs.tagging

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.TestData
import com.intellij.cvsSupport2.changeBrowser.CvsChangeList
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.streams.toList

/**
 * Capability changeset-tagging, requirement "Tagging puts the tag on the latest trunk revisions of the changeset's
 * files" (design decisions 3, 6, 7, 16, 19): a changeset loaded from the throwaway server is tagged through
 * [ChangesetTagger]; tag positions are read from the server's RCS files with the fixture's own `cvs rlog -h`.
 */
@TestApplication
class TaggingTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("New tag on the changeset's files")
    fun newTagOnTheChangesetsFiles() {
        val copy = module("tag-new", "one.txt", "two.txt", "three.txt")
        edit(copy, "one.txt", "two.txt", message = "edit of two files")

        val result = tag(copy, "edit of two files", "TEST", move = true)

        assertEquals("1.2", session.tagOf("tag-new/one.txt", "TEST"))
        assertEquals("1.2", session.tagOf("tag-new/two.txt", "TEST"))
        assertNull(session.tagOf("tag-new/three.txt", "TEST"))
        assertEquals(TagResult("TEST", 2, emptyList(), emptyList(), null), result)
    }

    @Test
    @DisplayName("File changed after the commit gets its latest revision")
    fun fileChangedAfterTheCommitGetsItsLatestRevision() {
        val copy = module("tag-latest", "one.txt", "two.txt")
        edit(copy, "one.txt", "two.txt", message = "first edit")
        edit(copy, "one.txt", message = "second edit")

        tag(copy, "first edit", "TEST", move = true)

        assertEquals("1.3", session.tagOf("tag-latest/one.txt", "TEST"))
        assertEquals("1.2", session.tagOf("tag-latest/two.txt", "TEST"))
    }

    @Test
    @DisplayName("Existing tag moved when Move is on")
    fun existingTagMovedWhenMoveIsOn() {
        val copy = module("tag-move", "one.txt", "two.txt")
        editWithTag(copy, "TEST", "one.txt", "two.txt", message = "tagged edit")
        edit(copy, "one.txt", "two.txt", message = "later edit")

        val result = tag(copy, "later edit", "TEST", move = true)

        assertEquals("1.3", session.tagOf("tag-move/one.txt", "TEST"))
        assertEquals("1.3", session.tagOf("tag-move/two.txt", "TEST"))
        assertEquals(TagResult("TEST", 2, emptyList(), emptyList(), null), result)
    }

    @Test
    @DisplayName("Existing tag kept when Move is off")
    fun existingTagKeptWhenMoveIsOff() {
        val copy = module("tag-keep", "one.txt", "two.txt")
        editWithTag(copy, "TEST", "one.txt", "two.txt", message = "tagged edit")
        edit(copy, "one.txt", "two.txt", message = "later edit")

        val result = tag(copy, "later edit", "TEST", move = false)

        assertEquals("1.2", session.tagOf("tag-keep/one.txt", "TEST"))
        assertEquals("1.2", session.tagOf("tag-keep/two.txt", "TEST"))
        assertEquals(listOf("tag-keep/one.txt", "tag-keep/two.txt"), result.notMoved.sorted(), "$result")
        assertEquals(2, result.unchangedMessages.size, "${result.unchangedMessages}")
        assertNull(result.error)
    }

    @Test
    @DisplayName("File deleted after the commit keeps its tag")
    fun fileDeletedAfterTheCommitKeepsItsTag() {
        val copy = module("tag-deleted", "one.txt", "two.txt")
        editWithTag(copy, "TEST", "one.txt", "two.txt", message = "tagged edit")
        edit(copy, "one.txt", "two.txt", message = "later edit")
        session.removeAndCommit(project.get(), copy.resolve("two.txt"), "removal of two")

        val result = tag(copy, "later edit", "TEST", move = true)

        assertEquals("1.3", session.tagOf("tag-deleted/one.txt", "TEST"))
        assertEquals("1.2", session.tagOf("tag-deleted/two.txt", "TEST"))
        assertEquals(TagResult("TEST", 1, emptyList(), emptyList(), null), result)
    }

    @Test
    @DisplayName("Working copy is untouched")
    fun workingCopyIsUntouched() {
        val copy = module("tag-untouched", "one.txt", "two.txt")
        edit(copy, "one.txt", "two.txt", message = "edit of two files")
        val changeset = changeset(copy, "edit of two files")
        val before = snapshot(copy)

        ChangesetTagger(project.get()).tag(changeset, "TEST", move = true)

        assertEquals(before, snapshot(copy))
        assertEquals("1.2", session.tagOf("tag-untouched/one.txt", "TEST"))
    }

    @Test
    @DisplayName("Branch tag is not moved")
    fun branchTagIsNotMoved() {
        val copy = module("tag-branch", "one.txt", "two.txt")
        edit(copy, "one.txt", "two.txt", message = "edit before the branch")
        session.server.cvs("rtag", "-b", "BR", "tag-branch")
        edit(copy, "one.txt", "two.txt", message = "edit after the branch")
        val branch = session.tagOf("tag-branch/one.txt", "BR")

        val result = tag(copy, "edit after the branch", "BR", move = true)

        assertEquals("1.2.0.2", branch)
        assertEquals(branch, session.tagOf("tag-branch/one.txt", "BR"))
        assertEquals(branch, session.tagOf("tag-branch/two.txt", "BR"))
        assertTrue(result.unchangedMessages.isNotEmpty(), "$result")
        assertTrue(result.unchangedMessages.all { "BR" in it }, "$result")
        assertEquals(0, result.taggedCount, "$result")
        assertNull(result.error, "$result")
    }

    @Test
    @DisplayName("Cyrillic names tagged in UTF-8")
    fun cyrillicNamesTaggedInUtf8() = cyrillicNamesTagged("tag-utf8", Charsets.UTF_8)

    @Test
    @DisplayName("Cyrillic names tagged in windows-1251")
    fun cyrillicNamesTaggedInWindows1251() = cyrillicNamesTagged("tag-cp1251", Charset.forName("windows-1251"))

    /**
     * With the CVS encoding [charset]: the Cyrillic seed module is imported, two of its Cyrillic files (one in a
     * Cyrillic directory, with spaces in the name) are committed together, and that changeset is tagged.
     */
    private fun cyrillicNamesTagged(name: String, charset: Charset) {
        session.useEncoding(charset)
        val seed = TestData.seed(session.newDirectory("$name-seed"), charset)
        session.importModule("alice", seed, name, binaryExtensions = listOf("bin"))
        val copy = session.checkout("alice", name, session.newDirectory(name))
        session.mapToCvs(project.get(), copy)
        val files = listOf(TestData.CYRILLIC_TEXT, TestData.EMPTY)
        files.forEach { TestData.edit(copy.resolve(it), "правка\n".toByteArray(charset)) }
        session.commit(project.get(), files.map { copy.resolve(it) }, "правка двух файлов")

        val result = tag(copy, "правка двух файлов", "TEST", move = true)

        assertEquals("1.2", session.tagOf("$name/${TestData.CYRILLIC_TEXT}", "TEST"), "$result")
        assertEquals("1.2", session.tagOf("$name/${TestData.EMPTY}", "TEST"), "$result")
        assertNull(session.tagOf("$name/${TestData.NO_FINAL_NEWLINE}", "TEST"))
        assertEquals(TagResult("TEST", 2, emptyList(), emptyList(), null), result)
    }

    /** Imports [files] as [name], checks the module out as `alice` and maps the working copy to CVS. */
    private fun module(name: String, vararg files: String): Path {
        val seed = session.newDirectory("$name-seed")
        files.forEach { TestData.write(seed.resolve(it), "$it\n".toByteArray()) }
        session.importModule("alice", seed, name)
        val copy = session.checkout("alice", name, session.newDirectory(name))
        session.mapToCvs(project.get(), copy)
        return copy
    }

    private fun edit(copy: Path, vararg files: String, message: String) {
        files.forEach { TestData.edit(copy.resolve(it), "$message\n".toByteArray()) }
        session.commit(project.get(), files.map { copy.resolve(it) }, message)
    }

    private fun editWithTag(copy: Path, tag: String, vararg files: String, message: String) {
        files.forEach { TestData.edit(copy.resolve(it), "$message\n".toByteArray()) }
        session.commitWithTag(project.get(), files.map { copy.resolve(it) }, message, tag)
    }

    private fun changeset(copy: Path, message: String): CvsChangeList {
        val changesets = session.changesets(project.get(), copy)
        return changesets.singleOrNull { it.comment.trim() == message }
            ?: throw AssertionError("no single changeset '$message' in ${changesets.map { "${it.comment.trim()} ${it.fileRevisions}" }}")
    }

    private fun tag(copy: Path, message: String, tag: String, move: Boolean): TagResult =
        ChangesetTagger(project.get()).tag(changeset(copy, message), tag, move)

    /** Every file below [root], CVS administrative files included: relative path → content and modification time. */
    private fun snapshot(root: Path): Map<String, Pair<List<Byte>, Long>> =
        Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() }.toList().associate {
                root.relativize(it).toString() to (it.readBytes().toList() to Files.getLastModifiedTime(it).toMillis())
            }
        }
}
