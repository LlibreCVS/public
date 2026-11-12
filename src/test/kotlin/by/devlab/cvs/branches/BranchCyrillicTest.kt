package by.devlab.cvs.branches

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.CvsTestSession.UpdateOptions
import by.devlab.cvs.TestData
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo

/**
 * Capability cvs-branches, requirement "Branches keep Cyrillic names and text": the whole branch cycle on the
 * Cyrillic seed module, with the plugin's CVS encoding set to the repository's. Review focus: in windows-1251 the
 * switch and the merge also run, so that the branch-specific protocol lines pass the fixture's transcoder.
 */
@TestApplication
class BranchCyrillicTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        val WINDOWS_1251: Charset = Charset.forName("windows-1251")

        const val BRANCH = "BR_CYR"
        const val ORIGINAL = "Первая строка отчёта\nВторая строка: ёЁ, щЩ, ъЪ\nТретья строка\n"
        const val TRUNK = "Первая строка отчёта, правка ствола\nВторая строка: ёЁ, щЩ, ъЪ\nТретья строка\n"
        const val BRANCH_TEXT = "Первая строка отчёта\nВторая строка: ёЁ, щЩ, ъЪ\nТретья строка\nСтрока с ветки: ёЁ щЩ ъЪ\n"
        const val MERGED = "Первая строка отчёта, правка ствола\nВторая строка: ёЁ, щЩ, ъЪ\nТретья строка\nСтрока с ветки: ёЁ щЩ ъЪ\n"
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("Branch work keeps UTF-8 Cyrillic")
    fun branchWorkKeepsUtf8Cyrillic() {
        assertBranchWorkKeepsCyrillic("branch-utf8", Charsets.UTF_8)
    }

    @Test
    @DisplayName("Branch work keeps windows-1251 Cyrillic")
    fun branchWorkKeepsWindows1251Cyrillic() {
        assertBranchWorkKeepsCyrillic("branch-cp1251", WINDOWS_1251)
    }

    private fun assertBranchWorkKeepsCyrillic(module: String, charset: Charset) {
        session.useEncoding(charset)
        val seed = TestData.seed(session.newDirectory("$module-seed"), charset)
        session.importModule("alice", seed, module, binaryExtensions = listOf("bin"))
        val expectedNames = names(seed)
        val cyrillic = TestData.CYRILLIC_TEXT

        // Create the branch on alice's working copy, switch to it, commit a Cyrillic line there.
        val onBranch = copy("alice", module, "branch")
        session.createBranch(project.get(), onBranch, BRANCH, switch = true)
        assertEquals(BRANCH, session.stickyTag(onBranch.resolve(cyrillic), charset))
        TestData.edit(onBranch.resolve(cyrillic), BRANCH_TEXT.toByteArray(charset))
        session.commit(project.get(), listOf(onBranch.resolve(cyrillic)), "строка с ветки")

        // The trunk changes another line of the same file.
        val trunk = copy("bob", module, "trunk")
        TestData.edit(trunk.resolve(cyrillic), TRUNK.toByteArray(charset))
        session.commit(project.get(), listOf(trunk.resolve(cyrillic)), "правка ствола")

        // A second working copy switches to the branch and back.
        val switching = copy("bob", module, "switch")
        session.updateWith(project.get(), switching, UpdateOptions(branch = BRANCH))
        assertEquals(BRANCH_TEXT, TestData.text(switching.resolve(cyrillic), charset))
        for (file in session.workingFiles(switching)) assertEquals(BRANCH, session.stickyTag(file, charset), "$file")
        assertEquals(expectedNames, names(switching))
        session.updateWith(project.get(), switching, UpdateOptions(resetSticky = true))
        assertEquals(TRUNK, TestData.text(switching.resolve(cyrillic), charset))
        for (file in session.workingFiles(switching)) assertEquals("", session.stickyTag(file, charset), "$file")

        // The branch is merged into the trunk working copy.
        session.updateWith(project.get(), trunk, UpdateOptions(merge1 = BRANCH))

        assertEquals(MERGED, TestData.text(trunk.resolve(cyrillic), charset))
        assertEquals(expectedNames, names(onBranch))
        assertEquals(expectedNames, names(trunk))
        assertEquals(expectedNames, names(switching))
        // The round trip alone would hide a plugin that ignored its encoding (see CheckoutTest).
        for (file in TestData.TEXTS + TestData.BINARY) {
            assertTrue(Files.isRegularFile(session.server.repositoryFile("$module/$file,v")), "$file on the server")
        }
        assertEquals(ORIGINAL, TestData.text(seed.resolve(cyrillic), charset))
    }

    private fun copy(user: String, module: String, name: String): Path {
        val copy = session.checkout(user, module, session.newDirectory("$module-$name"))
        session.mapToCvs(project.get(), copy)
        return copy
    }

    /** Relative paths of the files below [top], CVS administrative files and merge backups left out, sorted. */
    private fun names(top: Path): List<String> =
        session.workingFiles(top).map { it.relativeTo(top).toString().replace('\\', '/') }
}
