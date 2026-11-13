package by.devlab.cvs.branches

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.TestData
import by.devlab.cvs.branches.BranchFixtures.mapToCvs
import by.devlab.cvs.branches.BranchFixtures.refreshed
import by.devlab.cvs.branches.BranchFixtures.workingCopy
import by.devlab.cvs.branches.BranchPopupModel.Item
import by.devlab.cvs.branches.BranchPopupModel.Submenu
import by.devlab.vcs.FakeWorkingCopy
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Path

/**
 * Capability cvs-branch-popup, requirements "Branch popup lists actions, common branches and roots", "Current branch
 * is marked and entries can be searched", "Branch lists are remembered" and "Branch lists are loaded in the
 * background" (design decisions 5, 6, 15, 16, 19): the popup's model, read as a plain structure.
 */
@TestApplication
class BranchPopupTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        const val NEW_BRANCH = "New Branch…"
        const val BACK_TO_TRUNK = "Back to Trunk"
    }

    private val temp = tempPathFixture()
    private val project = projectFixture(temp, openAfterCreation = true)

    @Test
    @DisplayName("Popup with several roots")
    fun popupWithSeveralRoots() {
        val a = workingCopy(temp.get(), "module-a")
        val b = workingCopy(temp.get(), "module-b")
        mapToCvs(project.get(), a, b)
        refreshed(project.get())
        lists(a to listOf("BR_RELEASE", "BR_FIX"), b to listOf("BR_RELEASE"))

        val model = BranchPopupModel.build(project.get(), opening = true)
        println(model.render())

        assertEquals(listOf(NEW_BRANCH, BACK_TO_TRUNK, "BR_RELEASE"), items(model.entries).dropLast(1).map { it.text })
        assertEquals(BranchChoice.Switch("BR_RELEASE", listOf(a, b)), item(model.entries, "BR_RELEASE").choice)
        assertEquals(BranchChoice.Trunk(listOf(a, b)), item(model.entries, BACK_TO_TRUNK).choice)
        assertEquals(BranchChoice.NewBranch(listOf(a, b)), item(model.entries, NEW_BRANCH).choice)
        val submenus = model.entries.filterIsInstance<Submenu>()
        assertEquals(listOf("module-a: trunk", "module-b: trunk"), submenus.map { it.text })
        assertEquals(listOf(NEW_BRANCH, BACK_TO_TRUNK, "BR_FIX", "BR_RELEASE"), items(submenus[0].entries).map { it.text })
        assertEquals(listOf(NEW_BRANCH, BACK_TO_TRUNK, "BR_RELEASE"), items(submenus[1].entries).map { it.text })
        assertEquals(BranchChoice.Switch("BR_FIX", listOf(a)), item(submenus[0].entries, "BR_FIX").choice)
        assertEquals(BranchChoice.Trunk(listOf(b)), item(submenus[1].entries, BACK_TO_TRUNK).choice)
        assertEquals(BranchChoice.NewBranch(listOf(a)), item(submenus[0].entries, NEW_BRANCH).choice)
        assertTrue(items(model.entries).last().text.startsWith("Refresh Branch List (updated "), model.render())
    }

    @Test
    @DisplayName("Popup with one root")
    fun popupWithOneRoot() {
        val a = workingCopy(temp.get(), "module-a")
        mapToCvs(project.get(), a)
        refreshed(project.get())
        lists(a to listOf("BR_RELEASE", "BR_FIX"))

        val model = BranchPopupModel.build(project.get(), opening = true)
        println(model.render())

        assertTrue(model.entries.none { it is Submenu }, model.render())
        assertEquals(listOf(NEW_BRANCH, BACK_TO_TRUNK, "BR_FIX", "BR_RELEASE"), items(model.entries).dropLast(1).map { it.text })
        assertEquals(BranchChoice.Switch("BR_FIX", listOf(a)), item(model.entries, "BR_FIX").choice)
    }

    @Test
    @DisplayName("Current branch is marked")
    fun currentBranchIsMarked() {
        val a = workingCopy(temp.get(), "module-a", "TBR_RELEASE")
        val b = workingCopy(temp.get(), "module-b", "TBR_RELEASE")
        mapToCvs(project.get(), a, b)
        refreshed(project.get())
        lists(a to listOf("BR_RELEASE", "BR_FIX"), b to listOf("BR_RELEASE"))

        val marked = BranchPopupModel.build(project.get(), opening = true)
        println(marked.render())
        val current = item(marked.entries, "BR_RELEASE")
        assertTrue(current.current, marked.render())
        assertNull(current.choice, "choosing the current branch must switch nothing")
        assertEquals(listOf("module-a: BR_RELEASE", "module-b: BR_RELEASE"), marked.entries.filterIsInstance<Submenu>().map { it.text })
        assertFalse(item(marked.entries.filterIsInstance<Submenu>()[0].entries, "BR_FIX").current)

        // A file of module-a on another branch: the root is mixed, so the branch is not current (decision 19).
        FakeWorkingCopy.fileEntry(a, "stray.txt", "TOTHER")
        refreshed(project.get())

        val mixed = BranchPopupModel.build(project.get(), opening = true)
        println(mixed.render())
        assertFalse(item(mixed.entries, "BR_RELEASE").current, mixed.render())
        assertEquals(BranchChoice.Switch("BR_RELEASE", listOf(a, b)), item(mixed.entries, "BR_RELEASE").choice)
        assertEquals("module-a: BR_RELEASE *", mixed.entries.filterIsInstance<Submenu>()[0].text)
    }

    // Review focus: opening the popup never contacts the server once lists are remembered.
    @Test
    @DisplayName("Popup opens from the remembered list")
    fun popupOpensFromTheRememberedList() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val root = ":pserver:anonymous@127.0.0.1:${server.localPort}/cvsroot"
            val a = FakeWorkingCopy.write(temp.get().resolve("module-a").also { it.toFile().mkdirs() }, root, "module-a")
            val b = FakeWorkingCopy.write(temp.get().resolve("module-b").also { it.toFile().mkdirs() }, root, "module-b")
            mapToCvs(project.get(), a, b)
            refreshed(project.get())
            lists(a to listOf("BR_RELEASE", "BR_FIX"), b to listOf("BR_RELEASE"))

            val model = BranchPopupModel.build(project.get(), opening = true)
            println(model.render())

            assertEquals(emptyList<Path>(), model.loading)
            assertTrue("BR_RELEASE" in items(model.entries).map { it.text }, model.render())
            assertFalse(model.render().contains("Loading"), model.render())
            BranchListService.getInstance(project.get()).awaitLoad()
            server.soTimeout = 500
            assertThrows(SocketTimeoutException::class.java) { server.accept().close() }
        }
    }

    @Test
    @DisplayName("First opening loads the list")
    fun firstOpeningLoadsTheList() {
        val module = "first-opening"
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve("one.txt"), "one\n".toByteArray())
        session.importModule("alice", seed, module)
        session.branch(module, "BR_RELEASE")
        val a = session.checkout("alice", module, session.newDirectory("$module-a"))
        mapToCvs(project.get(), a)
        refreshed(project.get())
        val service = BranchListService.getInstance(project.get())

        val loading = BranchPopupModel.build(project.get(), opening = true)
        println(loading.render())
        assertEquals(listOf(a), loading.loading)
        assertTrue(items(loading.entries).any { it.text == "Loading branches…" && !it.enabled }, loading.render())
        assertFalse(items(loading.entries).any { it.text == "BR_RELEASE" }, loading.render())

        service.awaitLoad()
        val loaded = BranchPopupModel.build(project.get(), opening = false)
        println(loaded.render())
        assertEquals(emptyList<Path>(), loaded.loading)
        assertEquals(BranchChoice.Switch("BR_RELEASE", listOf(a)), item(loaded.entries, "BR_RELEASE").choice)
        assertFalse(loaded.render().contains("Loading"), loaded.render())
    }

    /** Sets the remembered lists of the roots. */
    private fun lists(vararg lists: Pair<Path, List<String>>) {
        val service = BranchListService.getInstance(project.get())
        lists.forEach { (root, branches) -> service.set(root, branches, System.currentTimeMillis()) }
    }

    private fun items(entries: List<BranchPopupModel.Entry>): List<Item> = entries.filterIsInstance<Item>()

    private fun item(entries: List<BranchPopupModel.Entry>, text: String): Item =
        items(entries).single { it.text == text }
}
