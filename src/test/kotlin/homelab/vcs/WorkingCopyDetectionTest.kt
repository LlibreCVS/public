package homelab.vcs

import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.VcsRootError
import com.intellij.openapi.vcs.roots.VcsRootDetector
import com.intellij.openapi.vcs.roots.VcsRootErrorsFinder
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.junit5.fixture.testFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Capability working-copy-detection, through the IDE's own root detection and root-problem finder.
 *
 * Layout: `<outer>/project` is the project directory and the content root of its only module. `<outer>` lies
 * above the project and is a working copy only in "Project directory inside a working copy". Only the children
 * of the content root are reloaded in the VFS, so the scanner meets directories that the VFS has not loaded yet.
 */
@TestApplication
class WorkingCopyDetectionTest {

    private val outerPath = tempPathFixture()
    private val projectPath = testFixture<Path> { _ ->
        initialized(outerPath.init().resolve("project").createDirectories()) {}
    }
    private val project = projectFixture(projectPath, openAfterCreation = true)
    private val module = project.moduleFixture(projectPath, addPathToSourceRoot = true)

    private val repository = ":pserver:anonymous@cvs.example.org:/cvsroot"
    private val otherRepository = ":ext:dev@cvs.example.net:/var/cvs"

    @Test
    @DisplayName("Project directory at the top of a working copy")
    fun projectDirectoryAtTheTopOfAWorkingCopy() {
        val top = FakeWorkingCopy.write(projectDir(), repository)
        FakeWorkingCopy.write(top.resolve("src").createDirectories(), repository)

        assertEquals(setOf(top.toRealPath()), detectedCvsRoots())
    }

    @Test
    @DisplayName("Several working copies in a project")
    fun severalWorkingCopiesInAProject() {
        val first = FakeWorkingCopy.write(projectDir().resolve("first").createDirectories(), repository)
        FakeWorkingCopy.write(first.resolve("src").createDirectories(), repository)
        val nested = FakeWorkingCopy.write(first.resolve("lib").createDirectories(), otherRepository)
        FakeWorkingCopy.write(nested.resolve("core").createDirectories(), otherRepository)
        val second = FakeWorkingCopy.write(projectDir().resolve("second").createDirectories(), repository)

        assertEquals(setOf(first.toRealPath(), nested.toRealPath(), second.toRealPath()), detectedCvsRoots())
    }

    @Test
    @DisplayName("Project directory inside a working copy")
    fun projectDirectoryInsideAWorkingCopy() {
        val top = FakeWorkingCopy.write(outerPath.get(), repository)
        FakeWorkingCopy.write(projectDir(), repository)

        assertEquals(setOf(top.toRealPath()), detectedCvsRoots())
    }

    @Test
    @DisplayName("Project without working copies")
    fun projectWithoutWorkingCopies() {
        projectDir().resolve("src").createDirectories()

        assertEquals(emptySet<Path>(), detectedCvsRoots())
    }

    @Test
    @DisplayName("Unregistered working copy is reported")
    fun unregisteredWorkingCopyIsReported() {
        val top = FakeWorkingCopy.write(projectDir().resolve("wc").createDirectories(), repository)

        val unregistered = cvsRootErrors().filter { it.type == VcsRootError.Type.UNREGISTERED_ROOT }

        assertEquals(listOf(top.toRealPath()), unregistered.map { Path.of(it.mapping.directory).toRealPath() })
    }

    @Test
    @DisplayName("Mapped working copy is not reported")
    fun mappedWorkingCopyIsNotReported() {
        val top = FakeWorkingCopy.write(projectDir().resolve("wc").createDirectories(), repository)
        mapToCvs(top)

        assertEquals(emptyList<VcsRootError>(), cvsRootErrors())
    }

    @Test
    @DisplayName("Top directory is versioned by CVS")
    fun topDirectoryIsVersionedByCvs() {
        val top = FakeWorkingCopy.write(projectDir().resolve("wc").createDirectories(), repository)
        val sub = FakeWorkingCopy.write(top.resolve("src").createDirectories(), repository)
        val vcsManager = ProjectLevelVcsManager.getInstance(project.get())

        assertEquals("CVS", vcsManager.findVersioningVcs(refreshed(top))?.name)
        assertNotEquals("CVS", vcsManager.findVersioningVcs(refreshed(sub))?.name)
    }

    @Test
    @DisplayName("Mapping of a working-copy subdirectory is valid")
    fun mappingOfAWorkingCopySubdirectoryIsValid() {
        val top = FakeWorkingCopy.write(projectDir().resolve("wc").createDirectories(), repository)
        val sub = FakeWorkingCopy.write(top.resolve("src").createDirectories(), repository)
        mapToCvs(sub)

        val problems = cvsRootErrors().filter { Path.of(it.mapping.directory).toRealPath() == sub.toRealPath() }

        assertEquals(emptyList<VcsRootError>(), problems)
    }

    @Test
    @DisplayName("Mapping of a directory without CVS/Root is invalid")
    fun mappingOfADirectoryWithoutCvsRootIsInvalid() {
        val plain = projectDir().resolve("plain").createDirectories()
        mapToCvs(plain)

        val extra = cvsRootErrors().filter { it.type == VcsRootError.Type.EXTRA_MAPPING }

        assertEquals(listOf(plain.toRealPath()), extra.map { Path.of(it.mapping.directory).toRealPath() })
    }

    @Test
    @DisplayName("Detection leaves the working copy unchanged")
    fun detectionLeavesTheWorkingCopyUnchanged() {
        val top = FakeWorkingCopy.write(projectDir().resolve("wc").createDirectories(), repository)
        FakeWorkingCopy.write(top.resolve("src").createDirectories(), repository)
        Files.writeString(top.resolve("src/Main.java"), "class Main {}\n")
        val before = FakeWorkingCopy.snapshot(top)

        detectedCvsRoots()
        cvsRootErrors()

        assertEquals(before, FakeWorkingCopy.snapshot(top))
    }

    // Review focus 3.
    @Test
    @DisplayName("A broken working copy next to a valid one does not stop detection")
    fun brokenWorkingCopyNextToAValidOne() {
        val broken = projectDir().resolve("broken").createDirectories()
        broken.resolve("CVS/Root").createDirectories()
        val valid = FakeWorkingCopy.write(projectDir().resolve("valid").createDirectories(), repository)

        assertEquals(setOf(valid.toRealPath()), detectedCvsRoots())
    }

    // Review focus 4.
    @Test
    @DisplayName("A working copy of 2,000 directories is detected as one root")
    fun largeWorkingCopyIsOneRoot() {
        val top = FakeWorkingCopy.write(projectDir().resolve("big").createDirectories(), repository)
        for (i in 0 until 40) {
            val group = FakeWorkingCopy.write(top.resolve("group$i").createDirectories(), repository)
            for (j in 0 until 49) {
                FakeWorkingCopy.write(group.resolve("dir$j").createDirectories(), repository)
            }
        }

        val started = System.nanoTime()
        val roots = detectedCvsRoots()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        println("Detection over 2,001 working-copy directories took $elapsedMillis ms")

        assertEquals(setOf(top.toRealPath()), roots)
    }

    private fun projectDir(): Path {
        module.get()
        return projectPath.get()
    }

    /**
     * Loads the content tree into the VFS, as indexing does for an open project. The platform's scanner skips
     * the children of directories that the VFS has not loaded: it sees them as transient files that are not in
     * the project content. The CVS admin files themselves are still read only by java.nio.
     */
    private fun refreshContentRoot() {
        val outer = refreshed(outerPath.get())
        VfsUtil.markDirtyAndRefresh(false, true, true, outer)
        VfsUtilCore.visitChildrenRecursively(outer, object : VirtualFileVisitor<Unit>() {})
    }

    /** Runs the IDE's root detection; returns the CVS roots' real paths. */
    private fun detectedCvsRoots(): Set<Path> {
        refreshContentRoot()
        return VcsRootDetector.getInstance(project.get()).detect()
            .filter { it.vcs?.name == "CVS" }
            .map { it.path.toNioPath().toRealPath() }
            .toSet()
    }

    private fun cvsRootErrors(): List<VcsRootError> {
        refreshContentRoot()
        return VcsRootErrorsFinder.getInstance(project.get()).find().filter { it.mapping.vcs == "CVS" }
    }

    private fun mapToCvs(dir: Path) {
        ProjectLevelVcsManager.getInstance(project.get())
            .setDirectoryMappings(listOf(VcsDirectoryMapping(refreshed(dir).path, "CVS")))
    }

    private fun refreshed(path: Path): VirtualFile =
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)) { "no VFS file for $path" }
}
