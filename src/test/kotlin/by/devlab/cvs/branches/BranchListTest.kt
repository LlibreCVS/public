package by.devlab.cvs.branches

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.TestData
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path

/**
 * Capability cvs-branches, requirement "List the branches" (design decision 16): the list is loaded the way the
 * branch chooser loads it. Capability cvs-branch-popup, requirements "Branch lists are remembered" and "Branch lists
 * are loaded in the background" (design decisions 4, 5, 13, 18): the lists of [BranchListService].
 */
@TestApplication
class BranchListTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()
    }

    private val temp = tempPathFixture()
    private val project = projectFixture(temp, openAfterCreation = true)

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

    // Review focus: a list stored for a backslash path is found for the VFS's forward-slash path after the round trip.
    @Test
    @DisplayName("List kept across restarts")
    fun listKeptAcrossRestarts() {
        val root = temp.get().resolve("module-a")
        val backslash = Path.of(root.toString().replace('/', '\\'))
        val service = BranchListService(project.get())
        service.set(backslash, listOf("BR_RELEASE", "BR_FIX"), 1_700_000_000_000L, files = 5, coverage = mapOf("BR_FIX" to 2, "BR_RELEASE" to 5))

        val stored = XmlSerializer.serialize(service.state)
        println("stored state: ${com.intellij.openapi.util.JDOMUtil.write(stored)}")
        val restarted = BranchListService(project.get())
        restarted.loadState(XmlSerializer.deserialize(stored, BranchListService.ListsState::class.java))

        val list = restarted.list(Path.of(root.toString().replace('\\', '/')))
        assertNotNull(list)
        assertEquals(listOf("BR_FIX", "BR_RELEASE"), list!!.branches)
        assertEquals(1_700_000_000_000L, list.loadedAt)
        // The file counts survive too (design decision 20): BR_FIX covers part of the root, BR_RELEASE all of it.
        assertEquals(BranchListService.Coverage(2, 5), list.partial("BR_FIX"))
        assertNull(list.partial("BR_RELEASE"))
    }

    @Test
    @DisplayName("Refresh branch list")
    fun refreshBranchList() {
        val module = "refresh-list"
        val root = checkout(module)
        session.branch(module, "BR_RELEASE")
        val service = BranchListService.getInstance(project.get())
        service.load(listOf(root))
        service.awaitLoad()
        val first = checkNotNull(service.list(root)) { "no list after the first load" }
        assertTrue("BR_RELEASE" in first.branches, "${first.branches}")
        assertFalse("BR_NEW" in first.branches, "${first.branches}")

        session.branch(module, "BR_NEW")
        service.load(listOf(root))
        service.awaitLoad()

        val second = checkNotNull(service.list(root))
        println("refreshed list: ${second.branches}, loaded ${first.loadedAt} then ${second.loadedAt}")
        assertTrue("BR_NEW" in second.branches, "${second.branches}")
        assertTrue(second.loadedAt > first.loadedAt, "${first.loadedAt} -> ${second.loadedAt}")
        assertFalse(service.isLoading(root))
    }

    @Test
    @DisplayName("Plain tags are not listed")
    fun plainTagsAreNotListed() {
        val module = "plain-tags"
        val root = checkout(module)
        session.branch(module, "BR_RELEASE")
        session.tag(module, "RELEASE_1", "1.1")
        val service = BranchListService.getInstance(project.get())

        service.load(listOf(root))
        service.awaitLoad()

        val branches = checkNotNull(service.list(root)).branches
        println("list with a plain tag: $branches")
        assertTrue("BR_RELEASE" in branches, "$branches")
        assertFalse("RELEASE_1" in branches, "$branches")
    }

    /** [module] imported with two files and checked out as `alice`, mapped to CVS in the test project. */
    private fun checkout(module: String): Path {
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve("one.txt"), "one\n".toByteArray())
        TestData.write(seed.resolve("dir/two.txt"), "two\n".toByteArray())
        session.importModule("alice", seed, module)
        val root = session.checkout("alice", module, session.newDirectory("$module-a"))
        session.mapToCvs(project.get(), root)
        return root
    }
}
