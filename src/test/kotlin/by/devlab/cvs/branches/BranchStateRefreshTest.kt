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
import by.devlab.vcs.FakeWorkingCopy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    // Requirement "Mixed working copy": the mark follows an Entries change, and the root's state never waits for a
    // running mixed scan (code review of cvs-branch-indicator, issues 1, 4 and 7).
    @Test
    @DisplayName("Mark follows the working copy")
    fun stateDoesNotWaitForTheMixedScan() {
        val root = workingCopy(temp.get(), "wc")
        FakeWorkingCopy.fileEntry(root, "a.txt", "TBR_RELEASE")
        mapToCvs(project.get(), root)
        val service = refreshed(project.get())
        assertEquals("trunk *", checkNotNull(service.indicator(null)).text)

        // Another file on the branch arrives through the VFS: the scan starts and is held at its first directory.
        val gate = CountDownLatch(1)
        val scanStarted = CountDownLatch(1)
        service.onScanDirectory = {
            scanStarted.countDown()
            gate.await(30, TimeUnit.SECONDS)
        }
        try {
            FakeWorkingCopy.fileEntry(root, "b.txt", "TBR_RELEASE")
            virtualFile(root).refresh(false, true)
            assertTrue(scanStarted.await(30, TimeUnit.SECONDS), "the mixed scan did not start")

            // While that scan is held, the root is switched to the branch: the new state shows at once, without the
            // mark counted for the old state.
            Files.writeString(root.resolve("CVS/Tag"), "TBR_RELEASE\n")
            virtualFile(root).refresh(false, true)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (service.indicator(null)?.text != "BR_RELEASE" && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals("BR_RELEASE", checkNotNull(service.indicator(null)).text)
        } finally {
            service.onScanDirectory = {}
            gate.countDown()
        }
        service.awaitRefresh()
        assertEquals("BR_RELEASE", checkNotNull(service.indicator(null)).text)

        // A third file back on the trunk is counted against the new state.
        FakeWorkingCopy.fileEntry(root, "c.txt")
        virtualFile(root).refresh(false, true)
        service.awaitRefresh()
        assertEquals("BR_RELEASE *", checkNotNull(service.indicator(null)).text)
    }

    // Code review of cvs-branch-indicator, issue 2: CVS directories outside the CVS mappings never make the service
    // look for roots again.
    @Test
    @DisplayName("Roots are looked for only inside the mappings")
    fun rootsAreLookedForOnlyInsideTheMappings() {
        val root = workingCopy(temp.get(), "module-a")
        mapToCvs(project.get(), root)
        val service = refreshed(project.get())
        val discoveries = service.discoveries.get()

        becomeWorkingCopy(temp.get().resolve("elsewhere"), "other")
        service.awaitRefresh()
        assertEquals(discoveries, service.discoveries.get())

        // Control: the same event inside the mapping (a new directory from `update -d`) looks for the roots again.
        becomeWorkingCopy(root, "sub")
        service.awaitRefresh()
        assertEquals(discoveries + 1, service.discoveries.get())
        assertEquals(listOf("module-a"), service.roots().map { it.name })
    }

    /** Makes the directory [name] under [parent], already loaded in the VFS, a working copy the VFS reports. */
    private fun becomeWorkingCopy(parent: java.nio.file.Path, name: String) {
        val dir = Files.createDirectories(parent.resolve(name))
        virtualFile(dir).children
        workingCopy(parent, name)
        virtualFile(dir).refresh(false, true)
    }
}
