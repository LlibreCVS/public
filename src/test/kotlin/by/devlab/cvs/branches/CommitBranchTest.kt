package by.devlab.cvs.branches

import by.devlab.cvs.branches.BranchFixtures.mapToCvs
import by.devlab.cvs.branches.BranchFixtures.refreshed
import by.devlab.cvs.branches.BranchFixtures.workingCopy
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.vcs.branch.BranchStateProvider
import com.intellij.vcsUtil.VcsUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Capability cvs-branch-indicator, requirement "Commit window names the branch": the branch-state provider the
 * plugin registers with the platform, which the commit window asks for the branch of the files being committed.
 */
@TestApplication
class CommitBranchTest {

    private val temp = tempPathFixture()
    private val project = projectFixture(temp, openAfterCreation = true)

    @Test
    @DisplayName("Commit on a branch")
    fun commitOnABranch() {
        val root = workingCopy(temp.get(), "wc", "TBR_RELEASE")
        val file = Files.writeString(root.resolve("a.txt"), "a\n")
        val outside = Files.writeString(temp.get().resolve("outside.txt"), "not under CVS\n")
        mapToCvs(project.get(), root)
        refreshed(project.get())
        val provider = BranchStateProvider.EP_NAME.getExtensions(project.get()).filterIsInstance<CvsBranchStateProvider>().singleOrNull()
        checkNotNull(provider) { "no CvsBranchStateProvider registered" }

        val branch = checkNotNull(provider.getCurrentBranch(VcsUtil.getFilePath(file, false)))

        assertEquals("BR_RELEASE", branch.branchName)
        assertEquals("wc", branch.presentableRootName)
        assertNull(provider.getCurrentBranch(VcsUtil.getFilePath(outside, false)))
    }
}
