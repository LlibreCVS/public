package by.devlab.cvs

import com.intellij.cvsSupport2.checkinProject.CvsRollbackEnvironment
import com.intellij.cvsSupport2.config.CvsConfiguration
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutor
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutorCallback
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler
import com.intellij.cvsSupport2.cvsoperations.cvsMessages.CvsMessagesAdapter
import com.intellij.cvsSupport2.cvsoperations.cvsMessages.CvsMessagesListener
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.vcsUtil.VcsUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.netbeans.lib.cvsclient.file.ICvsFileSystem
import java.nio.file.Files
import java.nio.file.Path

/**
 * Capability cvs-operations, requirement "Restoring a locally deleted file reports no false errors": the restore that
 * Rollback runs for a file deleted in the working copy (`CommandCvsHandler.createRestoreFilesHandler`), against the
 * throwaway server.
 */
@TestApplication
class RestoreDeletedFileTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        /** The pieces of a tagged `U file` line (CVS protocol `MT` responses). */
        val TAGGED_FRAGMENTS = listOf("+updated", "text U", "fname ", "newline", "-updated")
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)

    @Test
    @DisplayName("Restoring a locally deleted file reports no errors")
    fun restoringALocallyDeletedFileReportsNoErrors() {
        session.useEncoding(Charsets.UTF_8)
        val seed = session.newDirectory("restore-seed")
        TestData.write(seed.resolve("один.txt"), "один\n".toByteArray())
        TestData.write(seed.resolve("два.txt"), "два\n".toByteArray())
        session.importModule("alice", seed, "restore")
        val copy = session.checkout("alice", "restore", session.newDirectory("restore-a"))
        session.mapToCvs(project.get(), copy)
        val deleted = copy.resolve("один.txt")
        val filePath = VcsUtil.getFilePath(deleted.toFile(), false)
        Files.delete(deleted)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(copy)!!.refresh(false, true)

        val reports = mutableListOf<String>()
        val handler = CommandCvsHandler.createRestoreFilesHandler(arrayOf(filePath), CvsConfiguration.getInstance(project.get()))
        handler.addCvsListener(CvsMessagesListener.STANDARD_OUTPUT)
        handler.addCvsListener(object : CvsMessagesAdapter() {
            override fun addError(message: String, path: String?, fs: ICvsFileSystem?, root: String?, warning: Boolean) {
                reports += "${if (warning) "warning" else "error"}: $message"
            }
        })
        val executor = CvsOperationExecutor(false, project.get(), ModalityState.nonModal())
        executor.setShowErrors(false)
        executor.performActionSync(handler, CvsOperationExecutorCallback.EMPTY)

        assertTrue(Files.exists(deleted), "один.txt was not restored")
        assertEquals("один\n", TestData.text(deleted, Charsets.UTF_8))
        val fragments = reports.filter { report -> TAGGED_FRAGMENTS.any { report.substringAfter(": ").startsWith(it) } }
        assertTrue(fragments.isEmpty(), "protocol fragments reported as errors or warnings: $fragments")
        // What Rollback counts as a failed restore (its balloon reports these, and the IDE log lists them).
        val failures = CvsRollbackEnvironment.restoreFailures(handler)
        assertTrue(failures.isEmpty(), "restore failures: ${failures.map { it.message }}; all reports: $reports")
    }
}
