package by.devlab.cvs

import by.devlab.cvs.CvsTestSession.UpdateOptions
import com.intellij.cvsSupport2.CvsVcs2
import com.intellij.cvsSupport2.cvsstatuses.CvsStatusProvider
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.charset.Charset

/**
 * Capability cvs-operations, requirement "Merge conflicts are resolved in the IDE's merge dialog": `alice` (trunk)
 * and `bob` (branch BR) change the same line of a windows-1251 file; merging BR into alice's copy leaves a conflict,
 * which is then loaded and resolved through the CVS VCS's merge provider.
 */
@TestApplication
class MergeResolveTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        val WINDOWS_1251: Charset = Charset.forName("windows-1251")
        const val FILE = "индекс.jsp"
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("CVS offers a merge provider")
    fun cvsOffersAMergeProvider() {
        val vcs = CvsVcs2.getInstance(project.get())

        val provider = vcs.mergeProvider

        assertNotNull(provider, "the CVS VCS offers no merge provider")
        assertSame(provider, vcs.mergeProvider)
    }

    @Test
    @DisplayName("Conflict sides are loaded in the file's encoding")
    fun conflictSidesAreLoadedInTheFilesEncoding() {
        val file = conflicted("merge-resolve-sides")
        val provider = checkNotNull(CvsVcs2.getInstance(project.get()).mergeProvider) { "the CVS VCS offers no merge provider" }

        val data = provider.loadRevisions(file)

        val current = String(data.CURRENT, WINDOWS_1251)
        val last = String(data.LAST, WINDOWS_1251)
        assertEquals("заголовок\nстрока ствола\n", current)
        assertEquals("заголовок\nстрока ветки\n", last)
        assertFalse(listOf(current, last).any { it.contains("<<<<<<<") || it.contains(">>>>>>>") || it.contains("=======") })
    }

    @Test
    @DisplayName("Resolved file leaves the conflict status")
    fun resolvedFileLeavesTheConflictStatus() {
        val file = conflicted("merge-resolve-status")
        assertEquals(FileStatus.MERGED_WITH_CONFLICTS, CvsStatusProvider.getStatus(file))
        val provider = checkNotNull(CvsVcs2.getInstance(project.get()).mergeProvider) { "the CVS VCS offers no merge provider" }

        provider.conflictResolvedForFile(file)

        assertNotEquals(FileStatus.MERGED_WITH_CONFLICTS, CvsStatusProvider.getStatus(file))
    }

    /** Alice's copy of [FILE] after merging BR, with one conflicting line; the file's charset is windows-1251. */
    private fun conflicted(module: String): VirtualFile {
        session.useEncoding(WINDOWS_1251)
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve(FILE), "заголовок\nстрока\n".toByteArray(WINDOWS_1251))
        session.importModule("alice", seed, module)
        val a = session.checkout("alice", module, session.newDirectory("$module-a"))
        session.mapToCvs(project.get(), a)
        session.branch(module, "BR")
        val b = session.checkout("bob", module, session.newDirectory("$module-BR"), tag = "BR")
        session.mapToCvs(project.get(), b)
        TestData.edit(b.resolve(FILE), "заголовок\nстрока ветки\n".toByteArray(WINDOWS_1251))
        session.commit(project.get(), listOf(b.resolve(FILE)), "bob меняет строку на ветке")
        TestData.edit(a.resolve(FILE), "заголовок\nстрока ствола\n".toByteArray(WINDOWS_1251))
        session.commit(project.get(), listOf(a.resolve(FILE)), "alice меняет строку на стволе")

        session.updateWith(project.get(), a, UpdateOptions(merge1 = "BR"))

        val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(a.resolve(FILE)))
        file.charset = WINDOWS_1251
        check(TestData.text(a.resolve(FILE), WINDOWS_1251).contains("<<<<<<<")) { "the merge left no conflict" }
        return file
    }
}
