package by.devlab.cvs.branches

import by.devlab.cvs.branches.BranchFixtures.mapToCvs
import by.devlab.cvs.branches.BranchFixtures.refreshed
import by.devlab.cvs.branches.BranchFixtures.virtualFile
import by.devlab.cvs.branches.BranchFixtures.workingCopy
import by.devlab.vcs.FakeWorkingCopy
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Capability cvs-branch-indicator, requirement "Several CVS roots": two fake working copies `module-a` and
 * `module-b` mapped to CVS (design decisions 8, 17, 19).
 */
@TestApplication
class BranchStateServiceTest {

    private val temp = tempPathFixture()
    private val project = projectFixture(temp, openAfterCreation = true)

    @AfterEach
    fun closeEditors() {
        runInEdtAndWait { FileEditorManagerEx.getInstanceEx(project.get()).closeAllFiles() }
    }

    @Test
    @DisplayName("Roots on one branch")
    fun rootsOnOneBranch() {
        val modules = temp.get().resolve("modules")
        val a = workingCopy(modules, "module-a", "TBR_RELEASE")
        val b = workingCopy(modules, "module-b", "TBR_RELEASE")
        mapToCvs(project.get(), a, b)

        val indicator = checkNotNull(refreshed(project.get()).indicator(null))

        assertEquals("BR_RELEASE", indicator.text)
        assertTrue("module-a: BR_RELEASE" in indicator.tooltip.lines(), indicator.tooltip)
        assertTrue("module-b: BR_RELEASE" in indicator.tooltip.lines(), indicator.tooltip)

        // Decision 17: a mapped parent that is not a working copy contributes the working copies below it.
        mapToCvs(project.get(), modules)
        val service = refreshed(project.get())
        assertEquals(listOf("module-a", "module-b"), service.roots().map { it.name })

        // Decision 19: the mark of module-b is not a different state; the shown root module-a has none.
        FakeWorkingCopy.fileEntry(b, "x.txt", "TOTHER")
        val mixed = checkNotNull(refreshed(project.get()).indicator(null))
        assertEquals("BR_RELEASE", mixed.text)
        assertTrue("module-b: BR_RELEASE *" in mixed.tooltip.lines(), mixed.tooltip)
    }

    // Review focus: with no CVS file in the active editor the first root by path is shown.
    @Test
    @DisplayName("Roots on different branches")
    fun rootsOnDifferentBranches() {
        val modules = temp.get().resolve("modules")
        val a = workingCopy(modules, "module-a", "TBR_RELEASE")
        val b = workingCopy(modules, "module-b")
        val fileOfB = Files.writeString(b.resolve("b.txt"), "b\n")
        val fileOfA = Files.writeString(a.resolve("a.txt"), "a\n")
        mapToCvs(project.get(), a, b)
        val service = refreshed(project.get())

        runInEdtAndWait { FileEditorManager.getInstance(project.get()).openFile(virtualFile(fileOfA), true) }
        val indicator = checkNotNull(service.indicator())

        assertEquals("BR_RELEASE +1", indicator.text)
        assertTrue("module-a: BR_RELEASE" in indicator.tooltip.lines(), indicator.tooltip)
        assertTrue("module-b: trunk" in indicator.tooltip.lines(), indicator.tooltip)

        runInEdtAndWait { FileEditorManager.getInstance(project.get()).openFile(virtualFile(fileOfB), true) }
        assertEquals("trunk +1", checkNotNull(service.indicator()).text)

        runInEdtAndWait { FileEditorManagerEx.getInstanceEx(project.get()).closeAllFiles() }
        assertEquals("BR_RELEASE +1", checkNotNull(service.indicator()).text)
        val outside = Files.writeString(temp.get().resolve("outside.txt"), "not under CVS\n")
        assertEquals("BR_RELEASE +1", checkNotNull(service.indicator(virtualFile(outside))).text)
    }
}
