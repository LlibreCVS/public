package by.devlab.cvs.branches

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.TestData
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Capability cvs-branches, requirement "Check out a branch": the checkout dialog's "branch or tag" field, through
 * the existing `checkout(..., tag)` helper.
 */
@TestApplication
class CheckoutBranchTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("Checkout of a branch")
    fun checkoutOfABranch() {
        val module = "checkout-branch"
        session.useEncoding(Charsets.UTF_8)
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve("один.txt"), "один\n".toByteArray())
        TestData.write(seed.resolve("Папка/два.txt"), "два\n".toByteArray())
        session.importModule("alice", seed, module)
        session.branch(module, "BR")
        val b = session.checkout("bob", module, session.newDirectory("$module-b"), tag = "BR")
        session.mapToCvs(project.get(), b)
        TestData.edit(b.resolve("один.txt"), "один на ветке\n".toByteArray())
        session.commit(project.get(), listOf(b.resolve("один.txt")), "bob меняет один на ветке")

        val a = session.checkout("alice", module, session.newDirectory("$module-a"), tag = "BR")

        assertEquals("один на ветке\n", TestData.text(a.resolve("один.txt"), Charsets.UTF_8))
        assertEquals("два\n", TestData.text(a.resolve("Папка/два.txt"), Charsets.UTF_8))
        val files = session.workingFiles(a)
        assertFalse(files.isEmpty())
        for (file in files) assertEquals("BR", session.stickyTag(file), "sticky tag of $file")
    }
}
