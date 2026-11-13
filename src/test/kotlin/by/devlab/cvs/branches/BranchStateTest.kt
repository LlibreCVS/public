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
import java.nio.charset.Charset
import java.nio.file.Files
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
    private val windows1251 = Charset.forName("windows-1251")

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

    // Review focus: a Cyrillic file name written in windows-1251 must not break the scan.
    @Test
    @DisplayName("Mixed working copy")
    fun mixedWorkingCopy() {
        val root = workingCopy()
        FakeWorkingCopy.fileEntry(root, "top.txt")
        val src = subdirectory(root, "src")
        FakeWorkingCopy.fileEntry(src, "на ветке.txt", "TBR_RELEASE", charset = windows1251)
        FakeWorkingCopy.fileEntry(src, "на стволе.txt", charset = windows1251)

        val status = RootStatus(BranchState.read(root), MixedScan.count(root))

        assertEquals("trunk *", status.text)
        assertTrue("1 file is on another branch" in status.tooltip, status.tooltip)

        // Decision 18: a file pinned to a revision differs from a branch root.
        val branch = FakeWorkingCopy.tag(workingCopy("wc-branch"), "TBR_RELEASE")
        FakeWorkingCopy.fileEntry(branch, "a.txt", "TBR_RELEASE")
        FakeWorkingCopy.fileEntry(branch, "pinned.txt", "T1.3", revision = "1.3")

        assertEquals(1, MixedScan.count(branch))
        assertEquals("BR_RELEASE *", RootStatus(BranchState.read(branch), MixedScan.count(branch)).text)
    }

    @Test
    @DisplayName("Uniform working copy")
    fun uniformWorkingCopy() {
        val root = FakeWorkingCopy.tag(workingCopy(), "TBR_RELEASE")
        FakeWorkingCopy.fileEntry(root, "a.txt", "TBR_RELEASE")
        val src = FakeWorkingCopy.tag(subdirectory(root, "src"), "TBR_RELEASE")
        FakeWorkingCopy.fileEntry(src, "b.txt", "TBR_RELEASE")
        // Decision 18: a nested working copy the root does not track (no D/ entry) does not mark the root.
        val nested = FakeWorkingCopy.tag(
            FakeWorkingCopy.write(root.resolve("nested").createDirectories(), repository, "module/nested"), "TOTHER",
        )
        FakeWorkingCopy.fileEntry(nested, "c.txt", "TOTHER")

        val status = RootStatus(BranchState.read(root), MixedScan.count(root))

        assertEquals("BR_RELEASE", status.text)
        assertEquals(0, status.mixedFiles)

        // Decision 18: CVS writes a plain tag as T in Entries; the root's N must not make every file differ.
        val tagged = FakeWorkingCopy.tag(workingCopy("wc-tag"), "NRELEASE_1")
        FakeWorkingCopy.fileEntry(tagged, "a.txt", "TRELEASE_1")
        val taggedSrc = FakeWorkingCopy.tag(subdirectory(tagged, "src"), "NRELEASE_1")
        FakeWorkingCopy.fileEntry(taggedSrc, "b.txt", "TRELEASE_1")

        assertEquals("RELEASE_1 (tag)", RootStatus(BranchState.read(tagged), MixedScan.count(tagged)).text)
    }

    @Test
    @DisplayName("Working copy unchanged")
    fun workingCopyUnchanged() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val cvsRoot = ":pserver:anonymous@127.0.0.1:${server.localPort}/cvsroot"
            val root = FakeWorkingCopy.write(temp.resolve("wc").createDirectories(), cvsRoot, "module")
            FakeWorkingCopy.tag(root, "TBR_RELEASE")
            FakeWorkingCopy.fileEntry(root, "a.txt", "TBR_RELEASE")
            val src = FakeWorkingCopy.track(root, FakeWorkingCopy.write(root.resolve("src").createDirectories(), cvsRoot, "module/src"))
            FakeWorkingCopy.fileEntry(src, "b.txt", "TOTHER")
            val before = FakeWorkingCopy.snapshot(root)

            BranchState.read(root)
            MixedScan.count(root)

            assertEquals(before, FakeWorkingCopy.snapshot(root))
            server.soTimeout = 200
            assertThrows(SocketTimeoutException::class.java, { server.accept().close() }, "a connection was opened")
        }
    }

    /** A working copy [name] of module `module` on the trunk. */
    private fun workingCopy(name: String = "wc"): Path =
        FakeWorkingCopy.write(temp.resolve(name).createDirectories(), repository, "module")

    /** A subdirectory [name] of [parent] that [parent] tracks. */
    private fun subdirectory(parent: Path, name: String): Path {
        val repositoryPath = Files.readString(parent.resolve("CVS/Repository")).trim()
        return FakeWorkingCopy.track(
            parent, FakeWorkingCopy.write(parent.resolve(name).createDirectories(), repository, "$repositoryPath/$name"),
        )
    }
}
