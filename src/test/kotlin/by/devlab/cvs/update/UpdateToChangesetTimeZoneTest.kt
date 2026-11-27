package by.devlab.cvs.update

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.TestData
import by.devlab.cvs.branches.BranchFixtures.refreshed
import by.devlab.cvs.branches.BranchSwitcher
import by.devlab.cvs.branches.SwitchTarget
import by.devlab.cvs.branches.TestPrompts
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * Capability changeset-update, scenario "Cut-off independent of the time zones" (design decisions 8, 14, 19): the
 * throwaway server reads a date without a zone in the Windows system zone (proved by `TestServerTest`), and the IDE runs
 * in another non-UTC zone.
 */
@TestApplication
class UpdateToChangesetTimeZoneTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        const val FILE = "file.txt"
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("Cut-off independent of the time zones")
    fun cutoffIndependentOfTheTimeZones() {
        val system = ZoneId.systemDefault()
        val systemOffset = system.rules.getOffset(Instant.now())
        assertNotEquals(
            ZoneOffset.UTC, systemOffset,
            "the server reads zone-less dates in the system zone ($system), which must not be UTC for this test",
        )
        val tokyo = ZoneId.of("Asia/Tokyo")
        val ide = if (tokyo.rules.getOffset(Instant.now()) == systemOffset) ZoneId.of("America/New_York") else tokyo
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ide))
        try {
            val module = "zone-m"
            val seed = session.newDirectory("$module-seed")
            TestData.write(seed.resolve(FILE), "first\n".toByteArray())
            session.importModule("alice", seed, module)
            val root = session.checkout("alice", module, session.newDirectory("zone-wc"))
            session.mapToCvs(project.get(), root)
            refreshed(project.get())
            Thread.sleep(2_100)
            TestData.edit(root.resolve(FILE), "changeset\n".toByteArray())
            session.commit(project.get(), listOf(root.resolve(FILE)), "the changeset")
            Thread.sleep(3_000)
            TestData.edit(root.resolve(FILE), "later\n".toByteArray())
            session.commit(project.get(), listOf(root.resolve(FILE)), "a later commit")
            val cutoff = ChangesetCutoff.of(session.changesets(project.get(), root).single { it.comment.trim() == "the changeset" })

            val report = BranchSwitcher(project.get(), TestPrompts()).switch(SwitchTarget.Date(cutoff), listOf(root))

            println("system $system, IDE $ide, ${report?.title}, sent ${cutoff.utcText}")
            assertEquals("changeset\n", TestData.text(root.resolve(FILE), Charsets.UTF_8))
            assertFalse(Files.readString(root.resolve(FILE)).contains("later"))
            val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val shown = LocalDateTime.parse(report!!.title.removePrefix("Update to "), format).atZone(ide).toInstant()
            val sent = LocalDateTime.parse(cutoff.utcText.removeSuffix(" UTC"), format).toInstant(ZoneOffset.UTC)
            assertEquals(sent, shown, "the title and the sent date name the same instant")
            val lines = session.server.requestLines()
            val dates = lines.indices.filter { lines[it] == "Argument -D" }.map { lines[it + 1] }
            assertTrue(dates.isNotEmpty() && dates.all { it == "Argument ${cutoff.utcText}" }, "sent: $dates")
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
