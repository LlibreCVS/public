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
 * background" (design decisions 5, 6, 15, 16, 19; cvs-branch-merge decision 10: branch submenus with Switch and
 * Merge into Current, Merge Trunk into Current): the popup's model, read as a plain structure.
 */
@TestApplication
class BranchPopupTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        const val NEW_BRANCH = "New Branch…"
        const val BACK_TO_TRUNK = "Back to Trunk"
        const val MERGE_TRUNK = "Merge Trunk into Current"
        const val SWITCH = "Switch"
        const val MERGE_INTO_CURRENT = "Merge into Current"
    }

    private val temp = tempPathFixture()
    private val project = projectFixture(temp, openAfterCreation = true)

    @Test
    @DisplayName("Popup with several roots")
    fun popupWithSeveralRoots() {
        // On a branch outside the lists, so that Merge Trunk into Current is offered.
        val a = workingCopy(temp.get(), "module-a", "TBR_OTHER")
        val b = workingCopy(temp.get(), "module-b", "TBR_OTHER")
        mapToCvs(project.get(), a, b)
        refreshed(project.get())
        lists(a to listOf("BR_RELEASE", "BR_FIX"), b to listOf("BR_RELEASE"))

        val model = BranchPopupModel.build(project.get(), opening = true)
        println(model.render())

        assertEquals(listOf(NEW_BRANCH, BACK_TO_TRUNK, MERGE_TRUNK), items(model.entries).dropLast(1).map { it.text })
        assertEquals(listOf("BR_RELEASE"), branches(model.entries).map { it.text })
        assertEquals(BranchChoice.Switch("BR_RELEASE", listOf(a, b)), switchOf(model.entries, "BR_RELEASE").choice)
        assertEquals(BranchChoice.Trunk(listOf(a, b)), item(model.entries, BACK_TO_TRUNK).choice)
        assertEquals(BranchChoice.NewBranch(listOf(a, b)), item(model.entries, NEW_BRANCH).choice)
        assertEquals(BranchChoice.Merge(SwitchTarget.Trunk, listOf(a, b)), item(model.entries, MERGE_TRUNK).choice)
        val submenus = roots(model.entries)
        assertEquals(listOf("module-a: BR_OTHER", "module-b: BR_OTHER"), submenus.map { it.text })
        assertEquals(listOf(NEW_BRANCH, BACK_TO_TRUNK, MERGE_TRUNK), items(submenus[0].entries).map { it.text })
        assertEquals(listOf("BR_FIX", "BR_RELEASE"), branches(submenus[0].entries).map { it.text })
        assertEquals(listOf("BR_RELEASE"), branches(submenus[1].entries).map { it.text })
        assertEquals(BranchChoice.Switch("BR_FIX", listOf(a)), switchOf(submenus[0].entries, "BR_FIX").choice)
        assertEquals(BranchChoice.Merge(SwitchTarget.Branch("BR_FIX"), listOf(a)), mergeOf(submenus[0].entries, "BR_FIX").choice)
        assertEquals(BranchChoice.Trunk(listOf(b)), item(submenus[1].entries, BACK_TO_TRUNK).choice)
        assertEquals(BranchChoice.Merge(SwitchTarget.Trunk, listOf(b)), item(submenus[1].entries, MERGE_TRUNK).choice)
        assertEquals(BranchChoice.NewBranch(listOf(a)), item(submenus[0].entries, NEW_BRANCH).choice)
        assertTrue(items(model.entries).last().text.startsWith("Refresh Branch List (updated "), model.render())
    }

    @Test
    @DisplayName("Popup with one root")
    fun popupWithOneRoot() {
        val a = workingCopy(temp.get(), "module-a", "TBR_OTHER")
        mapToCvs(project.get(), a)
        refreshed(project.get())
        lists(a to listOf("BR_RELEASE", "BR_FIX"))

        val model = BranchPopupModel.build(project.get(), opening = true)
        println(model.render())

        assertTrue(roots(model.entries).isEmpty(), model.render())
        assertEquals(listOf(NEW_BRANCH, BACK_TO_TRUNK, MERGE_TRUNK), items(model.entries).dropLast(1).map { it.text })
        assertEquals(listOf("BR_FIX", "BR_RELEASE"), branches(model.entries).map { it.text })
        assertEquals(BranchChoice.Switch("BR_FIX", listOf(a)), switchOf(model.entries, "BR_FIX").choice)
        assertEquals(BranchChoice.Merge(SwitchTarget.Trunk, listOf(a)), item(model.entries, MERGE_TRUNK).choice)
    }

    @Test
    @DisplayName("Branch submenu")
    fun branchSubmenu() {
        val a = workingCopy(temp.get(), "module-a")
        val b = workingCopy(temp.get(), "module-b")
        mapToCvs(project.get(), a, b)
        refreshed(project.get())
        lists(a to listOf("BR_RELEASE"), b to listOf("BR_RELEASE"))

        val model = BranchPopupModel.build(project.get(), opening = true)
        println(model.render())

        val branch = branches(model.entries).single { it.text == "BR_RELEASE" }
        assertEquals(listOf(SWITCH, MERGE_INTO_CURRENT), items(branch.entries).map { it.text })
        assertEquals(BranchChoice.Switch("BR_RELEASE", listOf(a, b)), switchOf(model.entries, "BR_RELEASE").choice)
        assertEquals(BranchChoice.Merge(SwitchTarget.Branch("BR_RELEASE"), listOf(a, b)), mergeOf(model.entries, "BR_RELEASE").choice)
    }

    @Test
    @DisplayName("No trunk merge on the trunk")
    fun noTrunkMergeOnTheTrunk() {
        val a = workingCopy(temp.get(), "module-a")
        val b = workingCopy(temp.get(), "module-b")
        mapToCvs(project.get(), a, b)
        refreshed(project.get())
        lists(a to listOf("BR_RELEASE"), b to listOf("BR_RELEASE"))

        val model = BranchPopupModel.build(project.get(), opening = true)
        println(model.render())

        assertFalse(model.render().contains(MERGE_TRUNK), model.render())
        assertTrue(model.render().contains(MERGE_INTO_CURRENT), model.render())
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
        val current = branches(marked.entries).single { it.text == "BR_RELEASE" }
        assertTrue(current.current, marked.render())
        assertNull(switchOf(marked.entries, "BR_RELEASE").choice, "Switch of the current branch must switch nothing")
        assertEquals(listOf(SWITCH), items(current.entries).map { it.text }, "the current branch offers no Merge into Current")
        assertEquals(listOf("module-a: BR_RELEASE", "module-b: BR_RELEASE"), roots(marked.entries).map { it.text })
        assertFalse(branches(roots(marked.entries)[0].entries).single { it.text == "BR_FIX" }.current)

        // A file of module-a on another branch: the root is mixed, so the branch is not current (decision 19).
        FakeWorkingCopy.fileEntry(a, "stray.txt", "TOTHER")
        refreshed(project.get())

        val mixed = BranchPopupModel.build(project.get(), opening = true)
        println(mixed.render())
        assertFalse(branches(mixed.entries).single { it.text == "BR_RELEASE" }.current, mixed.render())
        assertEquals(BranchChoice.Switch("BR_RELEASE", listOf(a, b)), switchOf(mixed.entries, "BR_RELEASE").choice)
        assertEquals("module-a: BR_RELEASE *", roots(mixed.entries)[0].text)
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
            assertTrue("BR_RELEASE" in branches(model.entries).map { it.text }, model.render())
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
        assertFalse(branches(loading.entries).any { it.text == "BR_RELEASE" }, loading.render())

        service.awaitLoad()
        val loaded = BranchPopupModel.build(project.get(), opening = false)
        println(loaded.render())
        assertEquals(emptyList<Path>(), loaded.loading)
        assertEquals(BranchChoice.Switch("BR_RELEASE", listOf(a)), switchOf(loaded.entries, "BR_RELEASE").choice)
        assertFalse(loaded.render().contains("Loading"), loaded.render())
    }

    @Test
    @DisplayName("Popup during an operation")
    fun popupDuringAnOperation() {
        val a = workingCopy(temp.get(), "module-a")
        val b = workingCopy(temp.get(), "module-b")
        mapToCvs(project.get(), a, b)
        refreshed(project.get())
        lists(a to listOf("BR_RELEASE", "BR_FIX"), b to listOf("BR_RELEASE"))
        val operations = BranchOperations.getInstance(project.get())
        assertTrue(operations.tryStart())
        try {
            val model = BranchPopupModel.build(project.get(), opening = true)
            println(model.render())

            val first = items(model.entries).first()
            assertEquals(BranchOperations.IN_PROGRESS, first.text)
            assertFalse(first.enabled)
            val actions = allItems(model.entries)
            val refresh = actions.filter { it.choice is BranchChoice.Refresh }
            assertEquals(1, refresh.size, model.render())
            assertTrue(refresh.single().enabled, "Refresh Branch List stays enabled")
            for (entry in actions.filter { it.choice != null && it.choice !is BranchChoice.Refresh }) {
                assertFalse(entry.enabled, "${entry.text} must be disabled during an operation")
            }
            assertTrue(actions.count { it.text == NEW_BRANCH } == 3, model.render())
            assertTrue(actions.count { it.text == MERGE_INTO_CURRENT } >= 3, "merge entries are listed (disabled): ${model.render()}")
        } finally {
            operations.finish()
        }
        val after = BranchPopupModel.build(project.get(), opening = true)
        assertFalse(after.render().contains(BranchOperations.IN_PROGRESS), after.render())
        assertTrue(switchOf(after.entries, "BR_RELEASE").enabled)
        assertTrue(mergeOf(after.entries, "BR_RELEASE").enabled)
    }

    @Test
    @DisplayName("Unfinished merge is shown")
    fun unfinishedMergeIsShown() {
        val a = workingCopy(temp.get(), "module-a")
        val b = workingCopy(temp.get(), "module-b")
        mapToCvs(project.get(), a, b)
        refreshed(project.get())
        lists(a to listOf("BR_FIX"), b to listOf("BR_FIX"))
        MergeService.getInstance(project.get()).record(
            a, MergeService.UnfinishedMerge("BR_FIX", "TRUNK", mapOf("one.txt" to "1.1", "dir/two.txt" to "1.1"), emptyMap()),
        )

        val model = BranchPopupModel.build(project.get(), opening = true)
        println(model.render())

        val rootA = roots(model.entries).single { it.text.startsWith("module-a") }
        val entry = item(rootA.entries, "merging BR_FIX: 2 files left")
        assertFalse(entry.enabled)
        assertEquals(BranchChoice.CompleteMerge(a), item(rootA.entries, "Complete Merge").choice)
        assertEquals(BranchChoice.ForgetMerge(a), item(rootA.entries, "Forget Merge").choice)
        val rootB = roots(model.entries).single { it.text.startsWith("module-b") }
        assertFalse(rootB.entries.filterIsInstance<Item>().any { it.text.startsWith("merging") || it.text == "Complete Merge" }, model.render())

        // With the flag held, Complete and Forget are disabled too.
        val operations = BranchOperations.getInstance(project.get())
        assertTrue(operations.tryStart())
        try {
            val busy = BranchPopupModel.build(project.get(), opening = true)
            val busyA = roots(busy.entries).single { it.text.startsWith("module-a") }
            assertFalse(item(busyA.entries, "Complete Merge").enabled)
            assertFalse(item(busyA.entries, "Forget Merge").enabled)
        } finally {
            operations.finish()
        }
    }

    // Code review: the popup must not act on a change list that may be stale; building it only reads the merges.
    @Test
    @DisplayName("Building the popup does not change unfinished merges")
    fun buildingThePopupDoesNotChangeUnfinishedMerges() {
        val a = workingCopy(temp.get(), "module-a")
        mapToCvs(project.get(), a)
        refreshed(project.get())
        lists(a to listOf("BR_FIX"))
        // An added file no longer in CVS/Entries would count as rolled back by a refresh.
        val merge = MergeService.UnfinishedMerge("BR_FIX", "TRUNK", mapOf("added.txt" to "0"), emptyMap())
        MergeService.getInstance(project.get()).record(a, merge)

        val model = BranchPopupModel.build(project.get(), opening = true)
        println(model.render())

        // A synchronous refresh would have dropped the merge before the entries were built; a later one may.
        assertTrue(items(model.entries).any { it.text == "merging BR_FIX: 1 file left" }, model.render())
    }

    @Test
    @DisplayName("Load error shown by its first line")
    fun loadErrorShownByItsFirstLine() {
        val a = workingCopy(temp.get(), "module-a")
        mapToCvs(project.get(), a)
        refreshed(project.get())
        BranchListService.getInstance(project.get()).setError(a, "cannot reach the server\n\tat some.Frame(Frame.java:1)")

        val model = BranchPopupModel.build(project.get(), opening = false)
        println(model.render())

        assertTrue(items(model.entries).any { it.text == "Cannot load branches: cannot reach the server" && !it.enabled }, model.render())
        assertFalse(model.render().contains("some.Frame"), model.render())
    }

    /** Sets the remembered lists of the roots. */
    private fun lists(vararg lists: Pair<Path, List<String>>) {
        val service = BranchListService.getInstance(project.get())
        lists.forEach { (root, branches) -> service.set(root, branches, System.currentTimeMillis()) }
    }

    private fun items(entries: List<BranchPopupModel.Entry>): List<Item> = entries.filterIsInstance<Item>()

    private fun item(entries: List<BranchPopupModel.Entry>, text: String): Item =
        items(entries).single { it.text == text }

    /** The root submenus: named `<root>: <state>`. */
    private fun roots(entries: List<BranchPopupModel.Entry>): List<Submenu> =
        entries.filterIsInstance<Submenu>().filter { ": " in it.text }

    /** The branch submenus. */
    private fun branches(entries: List<BranchPopupModel.Entry>): List<Submenu> =
        entries.filterIsInstance<Submenu>().filter { ": " !in it.text }

    private fun switchOf(entries: List<BranchPopupModel.Entry>, branch: String): Item =
        item(branches(entries).single { it.text == branch }.entries, SWITCH)

    private fun mergeOf(entries: List<BranchPopupModel.Entry>, branch: String): Item =
        item(branches(entries).single { it.text == branch }.entries, MERGE_INTO_CURRENT)

    /** Every item, also inside submenus. */
    private fun allItems(entries: List<BranchPopupModel.Entry>): List<Item> =
        entries.flatMap { if (it is Submenu) allItems(it.entries) else listOfNotNull(it as? Item) }
}
