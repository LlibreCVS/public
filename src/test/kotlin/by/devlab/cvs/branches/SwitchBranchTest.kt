package by.devlab.cvs.branches

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.CvsTestSession.UpdateOptions
import by.devlab.cvs.TestData
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.nio.file.Path

/**
 * Capability cvs-branches, requirement "Switch a working copy between branches and the trunk" (design decisions 8,
 * 12, 13): `alice` switches her trunk working copy through the update environment with the Update dialog's
 * settings; `bob` commits on the branches from working copies checked out at them.
 */
@TestApplication
class SwitchBranchTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        const val ONE = "один.txt"
        const val TWO = "Папка/два.txt"
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("Switch to a branch")
    fun switchToABranch() {
        val module = "switch-to"
        val a = trunkCopy(module)
        session.branch(module, "BR")
        commitOnBranch(module, "BR", ONE, "один на ветке\n")

        session.updateWith(project.get(), a, UpdateOptions(branch = "BR"))

        assertEquals("один на ветке\n", text(a, ONE))
        assertEquals("два\n", text(a, TWO))
        assertSticky(a, "BR")
    }

    // Review focus: the reset-sticky flag must survive into the update, for a file in a subdirectory too.
    @Test
    @DisplayName("Switch back to the trunk")
    fun switchBackToTheTrunk() {
        val module = "switch-back"
        val a = trunkCopy(module)
        session.branch(module, "BR")
        commitOnBranch(module, "BR", ONE, "один на ветке\n")
        session.updateWith(project.get(), a, UpdateOptions(branch = "BR"))
        assertSticky(a, "BR")
        // The trunk moves on while A is on the branch.
        val trunk = session.checkout("bob", module, session.newDirectory("$module-trunk"))
        session.mapToCvs(project.get(), trunk)
        TestData.edit(trunk.resolve(TWO), "два на стволе\n".toByteArray())
        session.commit(project.get(), listOf(trunk.resolve(TWO)), "bob меняет два на стволе")

        session.updateWith(project.get(), a, UpdateOptions(resetSticky = true))

        assertEquals("один\n", text(a, ONE))
        assertEquals("два на стволе\n", text(a, TWO))
        assertSticky(a, "")
    }

    @Test
    @DisplayName("Switch from one branch to another")
    fun switchFromOneBranchToAnother() {
        val module = "switch-across"
        val a = trunkCopy(module)
        session.branch(module, "BR_ONE")
        session.branch(module, "BR_TWO")
        commitOnBranch(module, "BR_ONE", ONE, "один на первой ветке\n")
        commitOnBranch(module, "BR_TWO", ONE, "один на второй ветке\n")
        session.updateWith(project.get(), a, UpdateOptions(branch = "BR_ONE"))
        assertEquals("один на первой ветке\n", text(a, ONE))

        session.updateWith(project.get(), a, UpdateOptions(branch = "BR_TWO"))

        assertEquals("один на второй ветке\n", text(a, ONE))
        assertSticky(a, "BR_TWO")
    }

    // Review focus: a directory that exists only on the branch goes away on the trunk and comes back.
    @Test
    @DisplayName("File added on a branch follows the switch")
    fun fileAddedOnABranchFollowsTheSwitch() {
        val module = "switch-added"
        val a = trunkCopy(module)
        session.branch(module, "BR")
        val b = session.checkout("bob", module, session.newDirectory("$module-br"), tag = "BR")
        session.mapToCvs(project.get(), b)
        val directory = b.resolve("Новая папка")
        val added = directory.resolve("только на ветке.txt")
        TestData.write(added, "добавлено на ветке\n".toByteArray())
        session.add(project.get(), directory, added)
        session.commit(project.get(), listOf(added), "bob добавляет файл на ветке")
        session.updateWith(project.get(), a, UpdateOptions(branch = "BR"))
        assertEquals("добавлено на ветке\n", text(a, "Новая папка/только на ветке.txt"))

        session.updateWith(project.get(), a, UpdateOptions(resetSticky = true))

        assertFalse(Files.exists(a.resolve("Новая папка/только на ветке.txt")), "the branch file on the trunk")
        assertFalse(Files.exists(a.resolve("Новая папка")), "the branch directory on the trunk")

        session.updateWith(project.get(), a, UpdateOptions(branch = "BR"))

        assertEquals("добавлено на ветке\n", text(a, "Новая папка/только на ветке.txt"))
        assertSticky(a, "BR")
    }

    @Test
    @DisplayName("Switch keeps an uncommitted edit")
    fun switchKeepsAnUncommittedEdit() {
        val module = "switch-edit"
        val a = trunkCopy(module)
        session.branch(module, "BR")
        commitOnBranch(module, "BR", ONE, "один на ветке\n")
        TestData.edit(a.resolve(TWO), "два, правка alice, не закоммичена\n".toByteArray())

        session.updateWith(project.get(), a, UpdateOptions(branch = "BR"))

        assertEquals("два, правка alice, не закоммичена\n", text(a, TWO))
        assertEquals("один на ветке\n", text(a, ONE))
        assertSticky(a, "BR")
    }

    /** Imports the module (a file at the top, one in a Cyrillic subdirectory) and checks it out as alice. */
    private fun trunkCopy(module: String): Path {
        session.useEncoding(Charsets.UTF_8)
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve(ONE), "один\n".toByteArray())
        TestData.write(seed.resolve(TWO), "два\n".toByteArray())
        session.importModule("alice", seed, module)
        val a = session.checkout("alice", module, session.newDirectory("$module-a"))
        session.mapToCvs(project.get(), a)
        return a
    }

    /** bob checks out [branch], writes [content] into [file] and commits it there. */
    private fun commitOnBranch(module: String, branch: String, file: String, content: String) {
        val b = session.checkout("bob", module, session.newDirectory("$module-$branch"), tag = branch)
        session.mapToCvs(project.get(), b)
        TestData.edit(b.resolve(file), content.toByteArray())
        session.commit(project.get(), listOf(b.resolve(file)), "bob меняет $file на $branch")
    }

    private fun text(root: Path, file: String): String = TestData.text(root.resolve(file), Charsets.UTF_8)

    private fun assertSticky(root: Path, tag: String) {
        val files = session.workingFiles(root)
        assertFalse(files.isEmpty())
        for (file in files) assertEquals(tag, session.stickyTag(file), "sticky tag of $file")
    }
}
