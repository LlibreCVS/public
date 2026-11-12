package by.devlab.cvs.branches

import by.devlab.vcs.FakeWorkingCopy
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Capability cvs-branch-indicator, requirements "Indicator names the state of a working copy" and "Indicator only
 * reads", on working copies written with [FakeWorkingCopy] (design decisions 6, 14).
 */
@TestApplication
class BranchStateTest {

    @TempDir
    lateinit var temp: Path

    private val repository = ":pserver:anonymous@cvs.example.invalid:/cvsroot"

    @Test
    @DisplayName("Trunk working copy")
    fun trunkWorkingCopy() {
        val root = workingCopy()

        val state = BranchState.read(root)

        assertEquals(BranchKind.TRUNK, state.kind)
        assertEquals("trunk", state.text)
    }

    @Test
    @DisplayName("Branch working copy")
    fun branchWorkingCopy() {
        val root = FakeWorkingCopy.tag(workingCopy(), "TBR_RELEASE")

        val state = BranchState.read(root)

        assertEquals(BranchKind.BRANCH, state.kind)
        assertEquals("BR_RELEASE", state.text)
    }

    @Test
    @DisplayName("Tag working copy warns")
    fun tagWorkingCopyWarns() {
        val root = FakeWorkingCopy.tag(workingCopy(), "NRELEASE_1")

        val state = BranchState.read(root)

        assertEquals(BranchKind.TAG, state.kind)
        assertEquals("RELEASE_1 (tag)", state.text)
        assertTrue("commit is not possible" in state.tooltip, state.tooltip)
    }

    @Test
    @DisplayName("Date working copy warns")
    fun dateWorkingCopyWarns() {
        val root = FakeWorkingCopy.tag(workingCopy(), "D2026.11.01.00.00.00")

        val state = BranchState.read(root)

        assertEquals(BranchKind.DATE, state.kind)
        assertEquals("2026-11-01 00:00:00 (date)", state.text)
        assertTrue("commit is not possible" in state.tooltip, state.tooltip)
    }

    @Test
    @DisplayName("Working copy unchanged")
    fun workingCopyUnchanged() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val root = FakeWorkingCopy.write(
                temp.resolve("wc").createDirectories(), ":pserver:anonymous@127.0.0.1:${server.localPort}/cvsroot", "module",
            )
            FakeWorkingCopy.tag(root, "TBR_RELEASE")
            val before = FakeWorkingCopy.snapshot(root)

            BranchState.read(root)

            assertEquals(before, FakeWorkingCopy.snapshot(root))
            server.soTimeout = 200
            assertThrows(SocketTimeoutException::class.java, { server.accept().close() }, "a connection was opened")
        }
    }

    /** A working copy `wc` of module `module` on the trunk. */
    private fun workingCopy(): Path = FakeWorkingCopy.write(temp.resolve("wc").createDirectories(), repository, "module")
}
