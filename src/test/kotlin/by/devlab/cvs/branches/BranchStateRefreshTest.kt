package by.devlab.cvs.branches

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.CvsTestSession.UpdateOptions
import by.devlab.cvs.TestData
import by.devlab.cvs.branches.BranchFixtures.mapToCvs
import by.devlab.cvs.branches.BranchFixtures.refreshed
import by.devlab.cvs.branches.BranchFixtures.virtualFile
import by.devlab.cvs.branches.BranchFixtures.workingCopy
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files

/**
 * Capability cvs-branch-indicator, requirement "Indicator follows the working copy" (design decision 11): the
 * service's cache follows a switch made by the plugin against the throwaway server, and files changed on disk by
 * another program once the IDE has refreshed them. No test asks the service to refresh after the change.
 */
@TestApplication
class BranchStateRefreshTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()
    }

    private val temp = tempPathFixture()
    private val project = projectFixture(temp, openAfterCreation = true)

    @Test
    @DisplayName("Update in the IDE")
    fun updateInTheIde() {
        val module = "indicator-update"
        session.useEncoding(Charsets.UTF_8)
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve("один.txt"), "один\n".toByteArray())
        session.importModule("alice", seed, module)
        session.branch(module, "BR_RELEASE")
        val a = session.checkout("alice", module, session.newDirectory("$module-a"))
        session.mapToCvs(project.get(), a)
        val service = refreshed(project.get())
        assertEquals("trunk", checkNotNull(service.indicator(null)).text)

        session.updateWith(project.get(), a, UpdateOptions(branch = "BR_RELEASE"))
        service.awaitRefresh()

        assertEquals("BR_RELEASE", checkNotNull(service.indicator(null)).text)
    }

    // Review focus: a root switched without any change to CVS/Entries (update -A of an unchanged copy).
    @Test
    @DisplayName("Change outside the IDE")
    fun changeOutsideTheIde() {
        val root = workingCopy(temp.get(), "wc", "TBR_RELEASE")
        mapToCvs(project.get(), root)
        val service = refreshed(project.get())
        assertEquals("BR_RELEASE", checkNotNull(service.indicator(null)).text)
        val entries = Files.readAllBytes(root.resolve("CVS/Entries"))
        val entriesModified = Files.getLastModifiedTime(root.resolve("CVS/Entries"))

        Files.writeString(root.resolve("CVS/Tag"), "NRELEASE_1\n")
        virtualFile(root).refresh(false, true)
        service.awaitRefresh()

        assertEquals("RELEASE_1 (tag)", checkNotNull(service.indicator(null)).text)

        Files.delete(root.resolve("CVS/Tag"))
        virtualFile(root).refresh(false, true)
        service.awaitRefresh()

        assertEquals("trunk", checkNotNull(service.indicator(null)).text)
        assertEquals(entries.toList(), Files.readAllBytes(root.resolve("CVS/Entries")).toList())
        assertEquals(entriesModified, Files.getLastModifiedTime(root.resolve("CVS/Entries")))
    }
}
