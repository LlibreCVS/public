package by.devlab.cvs.branches

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.CvsTestSession.UpdateOptions
import by.devlab.cvs.TestCvsServer
import by.devlab.cvs.TestData
import by.devlab.cvs.branches.BranchFixtures.refreshed
import com.intellij.cvsSupport2.config.CvsApplicationLevelConfiguration
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.netbeans.lib.cvsclient.connection.PServerPasswordScrambler
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Capability cvs-branch-popup, requirements "Switching and going back to the trunk over the roots" and "One report per
 * action" (design decisions 7, 9, 14, 19): [BranchSwitcher] over two modules checked out from the throwaway server.
 */
@TestApplication
class BranchSwitchTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        const val ONE = "one.txt"
        const val TWO = "dir/two.txt"
        const val GONE = "gone.txt"
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)
    private val prompts = TestPrompts()

    @Test
    @DisplayName("Switch all roots")
    fun switchAllRoots() {
        val (a, b) = roots("all", branches = listOf("BR_RELEASE"))

        val report = switcher().switch(SwitchTarget.Branch("BR_RELEASE"), listOf(a, b))

        println(report?.text())
        assertSticky(a, "BR_RELEASE")
        assertSticky(b, "BR_RELEASE")
        assertEquals(listOf(a.fileName.toString(), b.fileName.toString()), report!!.switched)
        assertTrue(report.failed.isEmpty(), "${report.failed}")
    }

    @Test
    @DisplayName("Switch one root")
    fun switchOneRoot() {
        val (a, b) = roots("one", branches = listOf("BR_FIX"))

        switcher().switch(SwitchTarget.Branch("BR_FIX"), listOf(a))

        assertSticky(a, "BR_FIX")
        assertSticky(b, "")
    }

    // Review focus: a root that lacks the branch keeps its files.
    @Test
    @DisplayName("Root without the branch is left as it is")
    fun rootWithoutTheBranchIsLeftAsItIs() {
        val (a, b) = roots("lacks", branches = emptyList())
        session.branch(a.fileName.toString(), "BR_FIX")
        BranchListService.getInstance(project.get()).set(a, listOf("BR_FIX"), System.currentTimeMillis())
        val filesBefore = session.workingFiles(b)

        val report = switcher().switch(SwitchTarget.Branch("BR_FIX"), listOf(a, b))

        println(report?.text())
        assertSticky(a, "BR_FIX")
        assertEquals(filesBefore, session.workingFiles(b))
        assertSticky(b, "")
        assertEquals(listOf(b.fileName.toString() to "lacks the branch BR_FIX"), report!!.skipped)
    }

    @Test
    @DisplayName("Back to trunk")
    fun backToTrunk() {
        val (a, b) = roots("trunk", branches = listOf("BR_RELEASE"))
        session.updateWith(project.get(), a, UpdateOptions(branch = "BR_RELEASE"))
        refreshed(project.get())

        val report = switcher().switch(SwitchTarget.Trunk, listOf(a, b))

        println(report?.text())
        assertSticky(a, "")
        assertSticky(b, "")
        assertEquals(listOf(a.fileName.toString()), report!!.switched)
        assertEquals(listOf(b.fileName.toString() to "already on the trunk"), report.skipped)
    }

    @Test
    @DisplayName("Mixed root is switched")
    fun mixedRootIsSwitched() {
        val (a, _) = roots("mixed", branches = listOf("BR_RELEASE"))
        session.updateWith(project.get(), a, UpdateOptions(branch = "BR_RELEASE"))
        session.updateWith(project.get(), a.resolve(ONE), UpdateOptions(resetSticky = true))
        assertEquals("", session.stickyTag(a.resolve(ONE)))
        refreshed(project.get())

        val report = switcher().switch(SwitchTarget.Branch("BR_RELEASE"), listOf(a))

        println(report?.text())
        assertSticky(a, "BR_RELEASE")
        assertEquals(listOf(a.fileName.toString()), report!!.switched)
    }

    @Test
    @DisplayName("Failure in one root")
    fun failureInOneRoot() {
        val (a, b) = roots("failure", branches = listOf("BR_RELEASE"))
        unreachable(a)

        val report = switcher().switch(SwitchTarget.Branch("BR_RELEASE"), listOf(a, b))

        println(report?.text())
        assertSticky(b, "BR_RELEASE")
        assertEquals(listOf(b.fileName.toString()), report!!.switched)
        assertEquals(listOf(a.fileName.toString()), report.failed.map { it.first })
    }

    @Test
    @DisplayName("Report of a switch")
    fun reportOfASwitch() {
        val (a, b) = roots("report", branches = listOf("BR_RELEASE"))
        val module = a.fileName.toString()
        // bob changes the line on the branch, alice has her own uncommitted edit of it: the switch merges with a conflict.
        val bob = session.checkout("bob", module, session.newDirectory("$module-bob"), tag = "BR_RELEASE")
        session.mapToCvs(project.get(), bob)
        TestData.edit(bob.resolve(ONE), "one on the branch\n".toByteArray())
        session.commit(project.get(), listOf(bob.resolve(ONE)), "bob changes one on the branch")
        TestData.edit(a.resolve(ONE), "one, alice's edit\n".toByteArray())
        session.updateWith(project.get(), b, UpdateOptions(branch = "BR_RELEASE"))
        refreshed(project.get())

        val (report, notification) = notified { switcher().switch(SwitchTarget.Branch("BR_RELEASE"), listOf(a, b)) }

        println("${notification.title}\n${notification.content}")
        assertEquals(listOf(module), report!!.switched)
        assertEquals(listOf(b.fileName.toString() to "already on BR_RELEASE"), report.skipped)
        assertEquals(listOf("$module/$ONE"), report.conflicts)
        val text = "${notification.title}\n${notification.content}"
        assertTrue(text.contains("Switched: $module"), text)
        assertTrue(text.contains("${b.fileName}: already on BR_RELEASE"), text)
        assertTrue(text.contains("Conflicts: $module/$ONE"), text)
    }

    @Test
    @DisplayName("Warning before a switch")
    fun warningBeforeASwitch() {
        val (a, b) = roots("warn", branches = listOf("BR_RELEASE"))
        TestData.edit(a.resolve(ONE), "one, alice's edit\n".toByteArray())
        TestData.edit(a.resolve(TWO), "two, alice's edit\n".toByteArray())
        val stickyWhenAsked = mutableListOf<String>()
        prompts.onAsk = { stickyWhenAsked += listOf(a, b).map { session.stickyTag(it.resolve(ONE)) } }

        switcher().switch(SwitchTarget.Branch("BR_RELEASE"), listOf(a, b))

        println(prompts.asked)
        assertEquals(listOf("switch to BR_RELEASE: {${a.fileName}=2}"), prompts.asked)
        assertEquals(listOf("", ""), stickyWhenAsked, "no root is switched before the answer")
        assertSticky(a, "BR_RELEASE")
        assertSticky(b, "BR_RELEASE")
    }

    @Test
    @DisplayName("Switch cancelled")
    fun switchCancelled() {
        val (a, b) = roots("cancel", branches = listOf("BR_RELEASE"))
        TestData.edit(a.resolve(ONE), "one, alice's edit\n".toByteArray())
        TestData.edit(a.resolve(TWO), "two, alice's edit\n".toByteArray())
        prompts.confirmSwitch = false

        val report = switcher().switch(SwitchTarget.Branch("BR_RELEASE"), listOf(a, b))

        println(prompts.asked)
        assertEquals(1, prompts.asked.size, "${prompts.asked}")
        assertEquals(null, report)
        assertSticky(a, "")
        assertSticky(b, "")
        assertEquals("one, alice's edit\n", TestData.text(a.resolve(ONE), Charsets.UTF_8))
        assertEquals("two, alice's edit\n", TestData.text(a.resolve(TWO), Charsets.UTF_8))
    }

    @Test
    @DisplayName("No warning without changes")
    fun noWarningWithoutChanges() {
        val (a, b) = roots("quiet", branches = listOf("BR_RELEASE"))

        switcher().switch(SwitchTarget.Branch("BR_RELEASE"), listOf(a, b))

        assertEquals(emptyList<String>(), prompts.asked)
        assertSticky(a, "BR_RELEASE")
        assertSticky(b, "BR_RELEASE")
    }

    @Test
    @DisplayName("Partial branch confirmed")
    fun partialBranchConfirmed() {
        val (a, _) = partialRoots("partial")
        TestData.edit(a.resolve(TWO), "two, alice's edit\n".toByteArray())
        val stickyWhenAsked = mutableListOf<String>()
        prompts.onAsk = { stickyWhenAsked += session.stickyTag(a.resolve(TWO)) }

        val report = switcher().switch(SwitchTarget.Branch("BR_SUB"), listOf(a))

        println("${prompts.asked}\n${report?.text()}")
        // The partial branch is asked first, then the uncommitted changes (design decision 20).
        assertEquals(listOf("partial BR_SUB: {${a.fileName}=1 of 2}", "switch to BR_SUB: {${a.fileName}=1}"), prompts.asked)
        assertEquals(listOf("", ""), stickyWhenAsked, "nothing is switched before the answers")
        assertSticky(a, "BR_SUB")
        assertFalse(Files.exists(a.resolve(ONE)), "a file without the branch leaves the working copy")
    }

    // Review focus: a branch on part of a root keeps the other files unless confirmed.
    @Test
    @DisplayName("Partial root skipped")
    fun partialRootSkipped() {
        val (a, b) = partialRoots("skip")
        val filesBefore = session.workingFiles(a)
        prompts.partial = PartialBranchChoice.SKIP

        val report = switcher().switch(SwitchTarget.Branch("BR_SUB"), listOf(a, b))

        println("${prompts.asked}\n${report?.text()}")
        // module-b's removed file (in the Attic) is not one of its files: BR_SUB covers all of module-b.
        assertEquals(listOf("partial BR_SUB: {${a.fileName}=1 of 2}"), prompts.asked)
        assertSticky(b, "BR_SUB")
        assertEquals(filesBefore, session.workingFiles(a))
        assertSticky(a, "")
        assertEquals(listOf(b.fileName.toString()), report!!.switched)
        assertEquals(listOf(a.fileName.toString() to "branch covers only part of the root"), report.skipped)
    }

    @Test
    @DisplayName("Partial switch cancelled")
    fun partialSwitchCancelled() {
        val (a, b) = partialRoots("partialcancel")
        val filesBefore = session.workingFiles(a)
        prompts.partial = PartialBranchChoice.CANCEL

        val report = switcher().switch(SwitchTarget.Branch("BR_SUB"), listOf(a, b))

        println(prompts.asked)
        assertEquals(1, prompts.asked.size, "${prompts.asked}")
        assertEquals(null, report)
        assertEquals(filesBefore, session.workingFiles(a))
        assertSticky(a, "")
        assertSticky(b, "")
    }

    @Test
    @DisplayName("Branch without counts covers the root")
    fun branchWithoutCountsCoversTheRoot() {
        val (a, _) = partialRoots("nocounts")
        // A list without counts: remembered before the counts, or a branch made through New Branch….
        BranchListService.getInstance(project.get()).set(a, listOf("BR_SUB"), System.currentTimeMillis())

        switcher().switch(SwitchTarget.Branch("BR_SUB"), listOf(a))

        assertEquals(emptyList<String>(), prompts.asked)
        assertSticky(a, "BR_SUB")
    }

    @Test
    @DisplayName("Files added after branching do not make a branch partial")
    fun filesAddedAfterBranchingDoNotMakeABranchPartial() {
        val (a, _) = roots("added", branches = listOf("BR_RELEASE"))
        val added = a.resolve("dir/new.txt")
        TestData.write(added, "new on the trunk\n".toByteArray())
        BranchFixtures.virtualFile(a).refresh(false, true)
        session.add(project.get(), added)
        session.commit(project.get(), listOf(added), "alice adds new on the trunk")
        val lists = BranchListService.getInstance(project.get())
        lists.load(listOf(a))
        lists.awaitLoad()
        println("list: ${lists.list(a)}")
        refreshed(project.get())

        switcher().switch(SwitchTarget.Branch("BR_RELEASE"), listOf(a))

        assertEquals(emptyList<String>(), prompts.asked)
        assertSticky(a, "BR_RELEASE")
    }

    @Test
    @DisplayName("Second operation refused")
    fun secondOperationRefused() {
        val (a, b) = roots("refused", branches = listOf("BR_RELEASE"))
        val operations = BranchOperations.getInstance(project.get())
        assertTrue(operations.tryStart())
        try {
            for (start in listOf<() -> Unit>(
                { switcher().start(SwitchTarget.Branch("BR_RELEASE"), listOf(a, b)) },
                { BranchCreator(project.get(), prompts).start(listOf(a, b)) },
            )) {
                val (_, notification) = notified(start)
                val text = "${notification.title}\n${notification.content}"
                println(text)
                assertTrue(text.contains("CVS branch operation is already running"), text)
            }
            assertEquals(emptyList<String>(), prompts.asked)
            assertSticky(a, "")
            assertSticky(b, "")
            assertTrue(operations.isRunning, "the running operation keeps the flag")
        } finally {
            operations.finish()
        }
    }

    // Review focus: the operation flag is always released, also after a cancel.
    @Test
    @DisplayName("Operation flag is released")
    fun operationFlagIsReleased() {
        val (a, b) = roots("released", branches = listOf("BR_RELEASE"))
        val operations = BranchOperations.getInstance(project.get())
        TestData.edit(a.resolve(ONE), "one, alice's edit\n".toByteArray())
        val runningWhenAsked = mutableListOf<Boolean>()
        prompts.onAsk = { runningWhenAsked += operations.isRunning }

        switcher().start(SwitchTarget.Branch("BR_RELEASE"), listOf(a, b))
        awaitIdle(operations)

        assertEquals(listOf(true), runningWhenAsked, "the flag is held while the switch runs")
        assertSticky(b, "BR_RELEASE")

        // Cancelled at the question: the flag is released all the same.
        prompts.onAsk = { throw ProcessCanceledException() }
        runCatching { switcher().start(SwitchTarget.Trunk, listOf(a, b)) }
        awaitIdle(operations)
        assertFalse(operations.isRunning)
        assertSticky(b, "BR_RELEASE")
        assertTrue(operations.tryStart(), "a new operation can start")
        operations.finish()
    }

    private fun awaitIdle(operations: BranchOperations) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (operations.isRunning) {
            check(System.nanoTime() < deadline) { "the branch operation did not finish" }
            Thread.sleep(50)
        }
    }

    private fun switcher() = BranchSwitcher(project.get(), prompts)

    /**
     * Two modules `<prefix>-a` and `<prefix>-b` with [ONE] and [TWO], checked out as alice and mapped to CVS;
     * `<prefix>-b` also had `gone.txt`, removed on the trunk before branching (a file in the Attic). `BR_SUB` is branched
     * on `<prefix>-a/dir` only and on all of `<prefix>-b`; both lists are loaded from the server.
     */
    private fun partialRoots(prefix: String): Pair<Path, Path> {
        val parent = session.newDirectory("$prefix-wc")
        val (a, b) = listOf("$prefix-a", "$prefix-b").map { module ->
            val seed = session.newDirectory("$module-seed")
            TestData.write(seed.resolve(ONE), "one\n".toByteArray())
            TestData.write(seed.resolve(TWO), "two\n".toByteArray())
            if (module.endsWith("-b")) TestData.write(seed.resolve(GONE), "gone\n".toByteArray())
            session.importModule("alice", seed, module)
            session.checkout("alice", module, parent)
        }
        session.mapToCvs(project.get(), a, b)
        refreshed(project.get())
        session.removeAndCommit(project.get(), b.resolve(GONE), "alice removes gone")
        session.branch("${a.fileName}/dir", "BR_SUB")
        session.branch(b.fileName.toString(), "BR_SUB")
        val lists = BranchListService.getInstance(project.get())
        lists.load(listOf(a, b))
        lists.awaitLoad()
        println("lists: ${lists.list(a)}, ${lists.list(b)}")
        refreshed(project.get())
        return a to b
    }

    /**
     * Two modules `<prefix>-a` and `<prefix>-b` with [ONE] and [TWO], [branches] made on both, checked out side by side
     * as alice, mapped to CVS, with both lists remembered as [branches].
     */
    private fun roots(prefix: String, branches: List<String>): Pair<Path, Path> {
        val parent = session.newDirectory("$prefix-wc")
        val roots = listOf("$prefix-a", "$prefix-b").map { module ->
            val seed = session.newDirectory("$module-seed")
            TestData.write(seed.resolve(ONE), "one\n".toByteArray())
            TestData.write(seed.resolve(TWO), "two\n".toByteArray())
            session.importModule("alice", seed, module)
            branches.forEach { session.branch(module, it) }
            session.checkout("alice", module, parent)
        }
        session.mapToCvs(project.get(), *roots.toTypedArray())
        refreshed(project.get())
        roots.forEach { BranchListService.getInstance(project.get()).set(it, branches, System.currentTimeMillis()) }
        return roots[0] to roots[1]
    }

    /** Points every `CVS/Root` of [root] at a closed local port, with a password for it so that no login is asked. */
    private fun unreachable(root: Path) {
        val port = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }
        val cvsRoot = ":pserver:alice@127.0.0.1:$port/cvsroot"
        Files.walk(root).use { paths ->
            paths.filter { it.fileName.toString() == "Root" && it.parent.fileName.toString() == "CVS" }
                .forEach { Files.writeString(it, "$cvsRoot\n") }
        }
        // OpenCVS reads CVS/Root through the VFS, so the IDE must see the new files.
        BranchFixtures.virtualFile(root).refresh(false, true)
        val scrambled = PServerPasswordScrambler.getInstance().scramble(TestCvsServer.PASSWORD)
        val passwordFile = Path.of(CvsApplicationLevelConfiguration.getInstance().PATH_TO_PASSWORD_FILE)
        Files.writeString(passwordFile, "$cvsRoot $scrambled\n", StandardOpenOption.APPEND)
    }

    /** Runs [action] and returns its result with the one IntelliJCVS notification it shows. */
    private fun <T> notified(action: () -> T): Pair<T, Notification> {
        val shown = LinkedBlockingQueue<Notification>()
        val disposable = Disposer.newDisposable()
        try {
            project.get().messageBus.connect(disposable).subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId == "IntelliJCVS") shown += notification
                }
            })
            val result = action()
            val notification = checkNotNull(shown.poll(60, TimeUnit.SECONDS)) { "no IntelliJCVS notification" }
            assertTrue(shown.poll(1, TimeUnit.SECONDS) == null, "more than one notification")
            return result to notification
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun assertSticky(root: Path, tag: String) {
        val files = session.workingFiles(root)
        assertFalse(files.isEmpty())
        for (file in files) assertEquals(tag, session.stickyTag(file), "sticky tag of $file")
    }
}
