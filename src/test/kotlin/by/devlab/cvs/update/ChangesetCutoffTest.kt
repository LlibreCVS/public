package by.devlab.cvs.update

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.TestData
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The cut-off of a changeset (design decisions 7, 8, 11): the latest commit time among its file revisions, sent as
 * `yyyy-MM-dd HH:mm:ss UTC`. Foundation of capability changeset-update; the scenarios are proved in
 * [UpdateToChangesetTest] and [UpdateToChangesetTimeZoneTest].
 */
@TestApplication
class ChangesetCutoffTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("Cut-off is the latest file revision time of the changeset")
    fun cutoffIsTheLatestFileRevisionTimeOfTheChangeset() {
        val module = "cutoff-two"
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve("one.txt"), "one\n".toByteArray())
        TestData.write(seed.resolve("two.txt"), "two\n".toByteArray())
        session.importModule("alice", seed, module)
        val copy = session.checkout("alice", module, session.newDirectory(module))
        session.mapToCvs(project.get(), copy)
        TestData.edit(copy.resolve("one.txt"), "one, edited\n".toByteArray())
        session.commit(project.get(), listOf(copy.resolve("one.txt")), "spread edit")
        Thread.sleep(2_100)
        TestData.edit(copy.resolve("two.txt"), "two, edited\n".toByteArray())
        session.commit(project.get(), listOf(copy.resolve("two.txt")), "spread edit")

        val changeset = session.changesets(project.get(), copy).single { it.comment.trim() == "spread edit" }
        val cutoff = ChangesetCutoff.of(changeset)

        val later = revisionTime("$module/two.txt", "1.2")
        assertTrue(later.isAfter(revisionTime("$module/one.txt", "1.2")), "the files were committed in the same second")
        assertEquals(
            later.toInstant(ZoneOffset.UTC).toEpochMilli(),
            changeset.fileRevisions.single { it.path() == "$module/two.txt" }.time(),
        )
        assertEquals(later.toInstant(ZoneOffset.UTC), cutoff.time)
        assertEquals(later.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " UTC", cutoff.utcText)
    }

    /** The commit time (UTC) `rlog` records for [revision] of the repository file [path]. */
    private fun revisionTime(path: String, revision: String): LocalDateTime {
        val log = session.server.cvs("rlog", "-r$revision", path)
        val date = Regex("""date: (\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2});""").find(log)?.groupValues?.get(1)
            ?: throw AssertionError("no date in $log")
        return LocalDateTime.parse(date, DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
    }
}
