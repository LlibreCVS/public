package by.devlab

import by.devlab.vcs.FakeWorkingCopy
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Capability vcs-registration: the plugin as the IDE loaded it, and CVS as a version control system.
 */
@TestApplication
class VcsRegistrationTest {

    private val projectPath = tempPathFixture()
    private val project = projectFixture(projectPath, openAfterCreation = true)
    private val contentPath = tempPathFixture()
    private val module = project.moduleFixture(contentPath, addPathToSourceRoot = true)

    @Test
    @DisplayName("Plugin is loaded with its identity")
    fun pluginIsLoadedWithItsIdentity() {
        val plugin = loadedPlugin()

        assertEquals("librecvs", plugin.pluginId.idString)
        assertEquals("LibreCVS", plugin.name)
        assertEquals("1.0.0", plugin.version)
        assertEquals("Developer Lab", plugin.vendor)
        assertEquals("https://devlab.by", plugin.url)
        // The descriptor is OpenCVS's: nothing of upstream's identity may survive the build's patching.
        assertTrue(plugin.vendorEmail.isNullOrEmpty(), "vendor email: ${plugin.vendorEmail}")
        assertTrue(plugin.vendorUrl.isNullOrEmpty(), "vendor url: ${plugin.vendorUrl}")
        assertFalse(plugin.description.orEmpty().contains("jieyuexing"), plugin.description)
    }

    @Test
    @DisplayName("Description states the commitment and the differences")
    fun descriptionStatesTheCommitmentAndTheDifferences() {
        val description = loadedPlugin().description.orEmpty()

        // Starting with this statement also proves that OpenCVS's own description did not survive the patching.
        assertTrue(
            description.startsWith("<p><b>LibreCVS is truly committed to the Open Source movement and recognizes no sanctions.</b></p>"),
            description,
        )
        assertFalse(description.contains("<img"), description)
        assertTrue(description.contains("OpenCVS"), description)
        assertTrue(description.contains("<th>Area</th><th>OpenCVS</th><th>LibreCVS</th>"), description)
    }

    @Test
    @DisplayName("Compatibility starts at build 261 and has no upper bound")
    fun compatibilityStartsAtBuild261AndHasNoUpperBound() {
        val plugin = loadedPlugin()

        assertEquals("261", plugin.sinceBuild)
        assertNull(plugin.untilBuild)
    }

    @Test
    @DisplayName("CVS is listed among version control systems")
    fun cvsIsListedAmongVersionControlSystems() {
        module.get()
        val vcsManager = ProjectLevelVcsManager.getInstance(project.get())

        val cvs = vcsManager.getAllSupportedVcss().filter { it.name == "CVS" }

        assertEquals(1, cvs.size)
        assertEquals("CVS", cvs.single().displayName)
        assertEquals(cvs.single(), vcsManager.findVcsByName("CVS"))
    }

    @Test
    @DisplayName("File under a CVS-mapped directory belongs to CVS")
    fun fileUnderCvsMappedDirectoryBelongsToCvs() {
        val (mappedFile, _) = mapOneOfTwoDirectoriesToCvs()

        assertEquals("CVS", ProjectLevelVcsManager.getInstance(project.get()).getVcsFor(mappedFile)?.name)
    }

    @Test
    @DisplayName("File outside every CVS-mapped directory has no version control")
    fun fileOutsideEveryCvsMappedDirectoryHasNoVersionControl() {
        val (_, siblingFile) = mapOneOfTwoDirectoriesToCvs()

        assertNull(ProjectLevelVcsManager.getInstance(project.get()).getVcsFor(siblingFile))
    }

    /** Maps `mapped` (a CVS working directory) to CVS and leaves its sibling unmapped; returns a file in each. */
    private fun mapOneOfTwoDirectoriesToCvs(): Pair<VirtualFile, VirtualFile> {
        module.get()
        val content = contentPath.get()
        val mapped = FakeWorkingCopy.write(
            Files.createDirectories(content.resolve("mapped")), ":pserver:anonymous@cvs.example.invalid:/cvsroot",
        )
        Files.writeString(mapped.resolve("Main.txt"), "mapped\n")
        val sibling = Files.createDirectories(content.resolve("sibling"))
        Files.writeString(sibling.resolve("Other.txt"), "sibling\n")

        val mappedDir = refreshed(mapped)
        ProjectLevelVcsManager.getInstance(project.get())
            .setDirectoryMappings(listOf(VcsDirectoryMapping(mappedDir.path, "CVS")))
        return refreshed(mapped.resolve("Main.txt")) to refreshed(sibling.resolve("Other.txt"))
    }

    private fun refreshed(path: Path): VirtualFile =
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)) { "no VFS file for $path" }

    private fun loadedPlugin(): IdeaPluginDescriptor {
        val id = PluginId.getId("librecvs")
        // getPlugin also returns installed plugins that are disabled; isLoaded proves the IDE loaded it.
        check(PluginManagerCore.isLoaded(id)) { "the IDE did not load plugin librecvs" }
        return checkNotNull(PluginManagerCore.getPlugin(id))
    }
}
