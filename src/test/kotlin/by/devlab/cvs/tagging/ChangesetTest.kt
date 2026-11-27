package by.devlab.cvs.tagging

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.TestData
import com.intellij.cvsSupport2.changeBrowser.CvsCommittedChangesProvider
import com.intellij.cvsSupport2.CvsVcs2
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Capability changeset-tagging, requirement "Repository history shows commits as changesets" (design decisions 2,
 * 13, 15): the changesets come from upstream's committed-changes provider, loaded from the throwaway server.
 */
@TestApplication
class ChangesetTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("Commit of two files is one changeset")
    fun commitOfTwoFilesIsOneChangeset() {
        val module = "changeset-two"
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve("one.txt"), "one\n".toByteArray())
        TestData.write(seed.resolve("two.txt"), "two\n".toByteArray())
        TestData.write(seed.resolve("three.txt"), "three\n".toByteArray())
        session.importModule("alice", seed, module)
        val copy = session.checkout("alice", module, session.newDirectory(module))
        session.mapToCvs(project.get(), copy)
        TestData.edit(copy.resolve("one.txt"), "one, edited\n".toByteArray())
        TestData.edit(copy.resolve("two.txt"), "two, edited\n".toByteArray())
        session.commit(project.get(), listOf(copy.resolve("one.txt"), copy.resolve("two.txt")), "edit of two files")

        val changesets = session.changesets(project.get(), copy).filter { it.comment.trim() == "edit of two files" }

        val changeset = changesets.single()
        assertEquals("alice", changeset.committerName)
        assertEquals(2, changeset.changes.size)
        assertEquals(
            setOf(Triple("$module/one.txt", "1.2", false), Triple("$module/two.txt", "1.2", false)),
            changeset.fileRevisions.map { Triple(it.path(), it.number(), it.removal()) }.toSet(),
        )
        assertEquals(session.server.cvsRoot("alice"), changeset.environment.cvsRootAsString)

        // The Repository view caches changesets on disk: one read back must carry the same environment and paths.
        val provider = CvsVcs2.getInstance(project.get()).committedChangesProvider as CvsCommittedChangesProvider
        val bytes = ByteArrayOutputStream().also { provider.writeChangeList(DataOutputStream(it), changeset) }.toByteArray()
        val location = session.repositoryLocation(project.get(), copy)
        val cached = provider.readChangeList(location, DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(changeset.fileRevisions, cached.fileRevisions)
        assertEquals(changeset.environment.cvsRootAsString, cached.environment.cvsRootAsString)
    }
}
