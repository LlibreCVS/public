package by.devlab.cvs.branches

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
import java.nio.file.Path

/**
 * Capability cvs-branches, requirement "Commit on a branch": commits go through the plugin's checkin environment
 * from a working copy checked out at the branch; the server is read with the fixture's own `cvs rlog` and with a
 * fresh checkout through the plugin.
 */
@TestApplication
class BranchCommitTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        const val ONE = "один.txt"
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("Commit creates a branch revision")
    fun commitCreatesABranchRevision() {
        val module = "commit-branch"
        trunkAt12(module)
        session.branch(module, "BR")
        val a = branchCopy(module, "alice", "BR")
        TestData.edit(a.resolve(ONE), "один на ветке\n".toByteArray())

        session.commit(project.get(), listOf(a.resolve(ONE)), "alice меняет один на ветке")

        val revision = session.revision(a.resolve(ONE))
        // 1.2 is the trunk head the branch starts from; a branch revision has four or more parts.
        assertTrue(revision.startsWith("1.2.") && revision.split('.').size >= 4, revision)
        val log = session.server.cvs("rlog", "-r$revision", "$module/$ONE")
        assertTrue(log.lines().any { it == "revision $revision" }, log)
        val fresh = session.checkout("bob", module, session.newDirectory("$module-fresh"), tag = "BR")
        assertEquals("один на ветке\n", TestData.text(fresh.resolve(ONE), Charsets.UTF_8))
        assertEquals(revision, session.revision(fresh.resolve(ONE)))
    }

    @Test
    @DisplayName("Trunk is untouched by a branch commit")
    fun trunkIsUntouchedByABranchCommit() {
        val module = "commit-trunk"
        val trunk = trunkAt12(module)
        session.branch(module, "BR")
        val a = branchCopy(module, "alice", "BR")
        TestData.edit(a.resolve(ONE), "один на ветке\n".toByteArray())
        session.commit(project.get(), listOf(a.resolve(ONE)), "alice меняет один на ветке")

        session.update(project.get(), trunk)

        assertEquals("один на стволе\n", TestData.text(trunk.resolve(ONE), Charsets.UTF_8))
        assertEquals("1.2", session.revision(trunk.resolve(ONE)))
        assertEquals("", session.stickyTag(trunk.resolve(ONE)))
    }

    /** Imports the module and commits [ONE] once more from bob's trunk working copy, which is returned (head 1.2). */
    private fun trunkAt12(module: String): Path {
        session.useEncoding(Charsets.UTF_8)
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve(ONE), "один\n".toByteArray())
        TestData.write(seed.resolve("Папка/два.txt"), "два\n".toByteArray())
        session.importModule("bob", seed, module)
        val trunk = session.checkout("bob", module, session.newDirectory("$module-trunk"))
        session.mapToCvs(project.get(), trunk)
        TestData.edit(trunk.resolve(ONE), "один на стволе\n".toByteArray())
        session.commit(project.get(), listOf(trunk.resolve(ONE)), "bob меняет один на стволе")
        assertEquals("1.2", session.revision(trunk.resolve(ONE)))
        return trunk
    }

    private fun branchCopy(module: String, user: String, branch: String): Path {
        val copy = session.checkout(user, module, session.newDirectory("$module-$user"), tag = branch)
        session.mapToCvs(project.get(), copy)
        return copy
    }
}
