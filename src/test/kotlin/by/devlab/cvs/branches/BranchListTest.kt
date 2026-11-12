package by.devlab.cvs.branches

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.TestData
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Capability cvs-branches, requirement "List the branches" (design decision 16): the list is loaded the way the
 * branch chooser loads it.
 */
@TestApplication
class BranchListTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("Branch list names every branch")
    fun branchListNamesEveryBranch() {
        val module = "branch-list"
        session.useEncoding(Charsets.UTF_8)
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve("один.txt"), "один\n".toByteArray())
        TestData.write(seed.resolve("Папка/два.txt"), "два\n".toByteArray())
        session.importModule("alice", seed, module)
        session.branch(module, "BR_REPOSITORY")
        val a = session.checkout("alice", module, session.newDirectory("$module-a"))
        session.mapToCvs(project.get(), a)
        session.createBranch(project.get(), a, "BR_WORKING_COPY", switch = false)

        val branches = session.branches(project.get(), a)

        // Printed for verification.md: upstream collects every symbolic name, plain tags included (decision 16).
        println("branch list: ${branches.sorted()}")
        assertTrue("BR_REPOSITORY" in branches, "$branches")
        assertTrue("BR_WORKING_COPY" in branches, "$branches")
    }
}
