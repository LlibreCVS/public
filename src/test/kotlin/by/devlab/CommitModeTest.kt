package by.devlab

import com.intellij.cvsSupport2.CvsVcs2
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.vcs.commit.CommitMode
import com.intellij.vcs.commit.CommitModeManager
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** Capability vcs-registration, requirement "CVS projects commit from the Commit tool window". */
@TestApplication
class CommitModeTest {

    private val projectPath = tempPathFixture()
    private val project = projectFixture(projectPath, openAfterCreation = true)

    @Test
    @DisplayName("CVS forces the non-modal commit mode")
    fun cvsForcesTheNonModalCommitMode() {
        val forced = CvsVcs2.getInstance(project.get()).getForcedCommitMode(CommitMode.ModalCommitMode)

        assertTrue(forced is CommitMode.NonModalCommitMode, "forced commit mode: $forced")
    }

    @Test
    @DisplayName("A CVS project uses the non-modal commit mode")
    fun aCvsProjectUsesTheNonModalCommitMode() {
        val root = Files.createDirectories(projectPath.get().resolve("wc"))
        Files.createDirectories(root.resolve("CVS"))
        Files.writeString(root.resolve("CVS/Root"), ":pserver:alice@127.0.0.1:2401/cvs\n")
        Files.writeString(root.resolve("CVS/Repository"), "wc\n")
        Files.writeString(root.resolve("CVS/Entries"), "D\n")
        ProjectLevelVcsManager.getInstance(project.get()).setDirectoryMappings(listOf(VcsDirectoryMapping(root.toString(), "CVS")))

        // The manager recomputes the mode on its own coroutine when the mappings change; wait for it.
        val manager = CommitModeManager.getInstance(project.get())
        val deadline = System.currentTimeMillis() + 10_000
        while (manager.getCurrentCommitMode() !is CommitMode.NonModalCommitMode && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }

        assertTrue(manager.getCurrentCommitMode() is CommitMode.NonModalCommitMode, "commit mode: ${manager.getCurrentCommitMode()}")
    }
}
