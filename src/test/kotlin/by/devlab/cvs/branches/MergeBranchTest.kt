package by.devlab.cvs.branches

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.CvsTestSession.UpdateOptions
import by.devlab.cvs.TestData
import com.intellij.cvsSupport2.config.CvsConfiguration
import com.intellij.openapi.vcs.update.FileGroup
import com.intellij.openapi.vcs.update.UpdatedFiles
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.nio.file.Path

/**
 * Capability cvs-branches, requirement "Merge a branch" (design decisions 12, 14, 20): `alice` merges through the
 * update environment with the Update dialog's merge settings; a conflict is read from the returned update result,
 * never through the merge dialog. `bob` commits on the branch from a working copy checked out at it.
 */
@TestApplication
class MergeBranchTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        const val ONE = "один.txt"
        const val TWO = "Папка/два.txt"
        const val ADDED = "добавлен на ветке.txt"
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    // Review focus: the merge settings must not leak into the next plain update.
    @Test
    @DisplayName("Merge a branch into the trunk")
    fun mergeABranchIntoTheTrunk() {
        val module = "merge-into-trunk"
        val a = trunkCopy(module)
        session.branch(module, "BR")
        val b = branchCopy(module, "BR")
        TestData.edit(b.resolve(ONE), "один\nстрока с ветки\n".toByteArray())
        TestData.write(b.resolve(ADDED), "новый файл ветки\n".toByteArray())
        session.add(project.get(), b.resolve(ADDED))
        session.commit(project.get(), listOf(b.resolve(ONE), b.resolve(ADDED)), "bob меняет один и добавляет файл")

        session.updateWith(project.get(), a, UpdateOptions(merge1 = "BR"))

        assertEquals("один\nстрока с ветки\n", text(a, ONE))
        assertEquals("новый файл ветки\n", text(a, ADDED))
        assertEquals("два\n", text(a, TWO))
        assertEquals("", session.stickyTag(a.resolve(ONE)))
        // Nothing reaches the trunk on the server before a commit.
        val before = session.checkout("bob", module, session.newDirectory("$module-before"))
        assertEquals("один\n", text(before, ONE))
        assertFalse(Files.exists(before.resolve(ADDED)), "the branch file on the server's trunk")

        session.commit(project.get(), listOf(a.resolve(ONE), a.resolve(ADDED)), "alice сливает ветку BR")
        val after = session.checkout("bob", module, session.newDirectory("$module-after"))
        assertEquals("один\nстрока с ветки\n", text(after, ONE))
        assertEquals("новый файл ветки\n", text(after, ADDED))

        // The trunk drops the branch line again: a merge setting left behind would bring it back.
        TestData.edit(a.resolve(ONE), "один\n".toByteArray())
        session.commit(project.get(), listOf(a.resolve(ONE)), "alice убирает строку ветки")

        val plain = session.updateWith(project.get(), a)

        assertEquals(emptyList<String>(), files(plain), "a plain update after the merge commit")
        assertEquals("один\n", text(a, ONE))
        assertEquals(CvsConfiguration.DO_NOT_MERGE, CvsConfiguration.getInstance(project.get()).MERGING_MODE)
    }

    @Test
    @DisplayName("Merge conflict is reported")
    fun mergeConflictIsReported() {
        val module = "merge-conflict"
        val a = trunkCopy(module)
        session.branch(module, "BR")
        val b = branchCopy(module, "BR")
        TestData.edit(b.resolve(ONE), "один на ветке\n".toByteArray())
        session.commit(project.get(), listOf(b.resolve(ONE)), "bob меняет строку на ветке")
        TestData.edit(a.resolve(ONE), "один на стволе\n".toByteArray())
        session.commit(project.get(), listOf(a.resolve(ONE)), "alice меняет строку на стволе")

        val updated = session.updateWith(project.get(), a, UpdateOptions(merge1 = "BR"))

        val lines = text(a, ONE).lines()
        assertTrue(lines.any { it.startsWith("<<<<<<<") }, "$lines")
        assertTrue(lines.any { it.startsWith(">>>>>>>") }, "$lines")
        assertTrue("один на стволе" in lines, "$lines")
        assertTrue("один на ветке" in lines, "$lines")
        val conflicts = checkNotNull(updated.getGroupById(FileGroup.MERGED_WITH_CONFLICT_ID)).files
        assertTrue(conflicts.any { Path.of(it).fileName.toString() == ONE }, "conflict group: $conflicts")
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

    private fun branchCopy(module: String, branch: String): Path {
        val b = session.checkout("bob", module, session.newDirectory("$module-$branch"), tag = branch)
        session.mapToCvs(project.get(), b)
        return b
    }

    private fun text(root: Path, file: String): String = TestData.text(root.resolve(file), Charsets.UTF_8)

    /** Every file the update result lists, in any group, as `group: path`. */
    private fun files(updated: UpdatedFiles): List<String> {
        fun collect(group: FileGroup): List<String> =
            group.files.map { "${group.id}: $it" } + group.children.flatMap { collect(it) }
        return updated.topLevelGroups.flatMap { collect(it) }
    }
}
